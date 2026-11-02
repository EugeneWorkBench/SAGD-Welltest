/*
 * REST implementation of the sync push contract.
 *
 * The concrete [SyncPushClient] + [BlobUploadClient] the sync engine pushes
 * through once the app graph is wired (task 23.2). It is the only component
 * that knows the Cloud_Backend endpoints, the bearer auth header, and the JSON
 * wire format; the engine still sees only the three-way [SyncPushResult]
 * classification (design.md "Cloud Backend API Contract").
 *
 * Responsibilities (task 23.1):
 *   - Resolve a [SyncableRecord] descriptor to its payload via
 *     [PushPayloadSource] and encode it with [JsonCodec].
 *   - Address the correct push endpoint per record type:
 *       WELL_TEST        -> POST /v1/well-tests
 *       WELLHEAD_LOG     -> POST /v1/wellhead-logs
 *       SUBCOOL_READING  -> POST /v1/subcool-readings
 *       ESCALATION       -> POST /v1/escalations (multipart: metadata + photo)
 *   - Attach the `Authorization: Bearer <token>` header from [AuthTokenProvider].
 *   - Classify the response into the engine's three cases:
 *       2xx + valid ack  -> Acknowledged(remoteId)         (Req 2.3)
 *       409 / 422         -> NonRetryableFailure            (poison record)
 *       5xx / timeout /   -> RetryableFailure               (design "5x/timeouts
 *         transport error     are retryable"; counts toward the 5-attempt limit)
 *       other 4xx         -> RetryableFailure (e.g. 401 before a token refresh)
 *
 * Idempotency (design.md): every payload carries the client-generated record
 * `id`, so a retried push of an already-accepted record returns the same
 * `remoteId` without creating a duplicate — the client simply resolves it to
 * [SyncPushResult.Acknowledged] again (supports the sync-idempotency property).
 *
 * The HTTP transport is injected ([HttpTransport]) so the whole class is
 * unit-testable against a recording stub with no real socket (task 23.1 "keep
 * the transport injectable so tests can stub responses").
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.SyncableRecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @param config base URL + bearer-token provider for the Cloud_Backend.
 * @param transport the injectable HTTP transport (production or test stub).
 * @param payloadSource loads a queued record's payload from the local store.
 * @param blobBytesReader reads a photo blob's bytes from its on-disk path;
 *   injectable so tests supply bytes without touching the filesystem. Defaults
 *   to reading the file directly.
 * @param photoContentType the `Content-Type` sent for the photo part. Blobs are
 *   stored as JPEG by the camera integration, so this defaults to `image/jpeg`.
 */
class RestSyncPushClient(
    private val config: BackendConfig,
    private val transport: HttpTransport,
    private val payloadSource: PushPayloadSource,
    private val blobBytesReader: suspend (String) -> ByteArray? = { path ->
        withContext(Dispatchers.IO) {
            File(path).let { if (it.exists()) it.readBytes() else null }
        }
    },
    private val photoContentType: String = "image/jpeg",
) : SyncPushClient, BlobUploadClient {

    // ---- SyncPushClient: JSON push for test/log/subcool records ----

    override suspend fun push(record: SyncableRecord): SyncPushResult {
        val id = record.recordId
        return when (record.type) {
            SyncableRecordType.WELL_TEST -> {
                val payload = payloadSource.wellTest(id)
                    ?: return missingPayload("well test", id.toString())
                postJson(PATH_WELL_TESTS, JsonCodec.encodeWellTest(payload))
            }

            SyncableRecordType.WELLHEAD_LOG -> {
                val payload = payloadSource.wellheadLog(id)
                    ?: return missingPayload("wellhead log", id.toString())
                postJson(PATH_WELLHEAD_LOGS, JsonCodec.encodeWellheadLog(payload))
            }

            SyncableRecordType.SUBCOOL_READING -> {
                val payload = payloadSource.subcoolReading(id)
                    ?: return missingPayload("subcool reading", id.toString())
                postJson(PATH_SUBCOOL_READINGS, JsonCodec.encodeSubcoolReading(payload))
            }

            SyncableRecordType.ESCALATION ->
                // An escalation must go through the multipart path so its photo
                // is included; the engine routes escalations to upload() and
                // never here, but classify defensively rather than drop it.
                SyncPushResult.RetryableFailure(
                    "escalation must be pushed via multipart upload()",
                )
        }
    }

    // ---- BlobUploadClient: multipart escalation push (JSON + photo) ----

    override suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult {
        val photoBytes = blobBytesReader(blobPath)
            ?: return SyncPushResult.RetryableFailure(
                "escalation photo blob unreadable at $blobPath",
            )

        val parts = listOf(
            MultipartPart(
                name = "metadata",
                filename = null,
                contentType = CONTENT_TYPE_JSON,
                bytes = JsonCodec.encodeEscalationMetadata(escalation)
                    .toByteArray(Charsets.UTF_8),
            ),
            MultipartPart(
                name = "photo",
                filename = "${escalation.id}.jpg",
                contentType = photoContentType,
                bytes = photoBytes,
            ),
        )

        val request = HttpRequest(
            method = HttpMethod.POST,
            url = config.url(PATH_ESCALATIONS),
            headers = authHeaders(),
            body = HttpBody.Multipart(parts),
        )
        return execute(request)
    }

    // ---- shared request plumbing ----

    private suspend fun postJson(path: String, json: String): SyncPushResult {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = config.url(path),
            headers = authHeaders(CONTENT_TYPE_JSON),
            body = HttpBody.Json(json),
        )
        return execute(request)
    }

    /**
     * Send the request and classify the outcome. A transport failure (no status
     * received) is retryable; otherwise the status code decides.
     */
    private suspend fun execute(request: HttpRequest): SyncPushResult {
        val response = try {
            transport.send(request)
        } catch (e: HttpTransportException) {
            // Timeout / connection reset / unreachable: retryable (design "5x/
            // timeouts are retryable").
            return SyncPushResult.RetryableFailure(e.message ?: "transport failure")
        }
        return classify(response)
    }

    private fun classify(response: HttpResponse): SyncPushResult {
        val code = response.statusCode
        return when {
            code in 200..299 -> {
                val ack = JsonCodec.decodeAcknowledgment(response.body)
                if (ack != null) {
                    // 2xx with a valid acknowledgment: synchronized (Req 2.3).
                    SyncPushResult.Acknowledged(ack.remoteId)
                } else {
                    // A 2xx without a parseable remoteId cannot be treated as an
                    // acknowledgment; retry rather than fabricate an id.
                    SyncPushResult.RetryableFailure(
                        "2xx response missing acknowledgment contract",
                    )
                }
            }

            // Definitive client rejections: retrying the same payload can't
            // succeed, so park the record toward FAILED (design "409/422 ...
            // non-retryable"). 409 = stale duplicate conflict; 422 = validation.
            code == 409 || code == 422 ->
                SyncPushResult.NonRetryableFailure("backend rejected record: HTTP $code")

            // Server errors are transient; retry with backoff (design "5x").
            code in 500..599 ->
                SyncPushResult.RetryableFailure("server error: HTTP $code")

            // Any other 4xx (e.g. 401 before a token refresh, 408 request
            // timeout, 429 throttling): retryable so a later cycle can succeed.
            else ->
                SyncPushResult.RetryableFailure("unexpected status: HTTP $code")
        }
    }

    /**
     * Build the request headers, always including the bearer `Authorization`
     * header when a token is available. [contentType] is added only for bodies
     * the transport does not frame itself (JSON); the multipart boundary's
     * Content-Type is set by the transport.
     */
    private suspend fun authHeaders(contentType: String? = null): Map<String, String> =
        buildMap {
            config.tokenProvider.bearerToken()?.let { put("Authorization", "Bearer $it") }
            contentType?.let { put("Content-Type", it) }
        }

    private fun missingPayload(label: String, id: String): SyncPushResult =
        // The descriptor is queued but the row is gone from the store: treat as
        // retryable so it is never acknowledged, and it parks as FAILED after
        // the retry limit rather than looping.
        SyncPushResult.RetryableFailure("$label payload not found for id $id")

    companion object {
        const val PATH_WELL_TESTS = "/v1/well-tests"
        const val PATH_WELLHEAD_LOGS = "/v1/wellhead-logs"
        const val PATH_SUBCOOL_READINGS = "/v1/subcool-readings"
        const val PATH_ESCALATIONS = "/v1/escalations"

        private const val CONTENT_TYPE_JSON = "application/json"
    }
}
