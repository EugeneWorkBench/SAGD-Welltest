/*
 * Property 24: Escalation photo included in push (Android) — task 23.3.
 *
 * **Validates: Requirements 7.6**
 *
 * Design "Property 24: Escalation photo included in push":
 *
 *     For any escalation pushed to the Cloud_Backend, the multipart payload
 *     includes the metadata plus the photo bytes identical to the blob store.
 *
 * ## Approach
 *
 * This drives the real [RestSyncPushClient.upload] — the only component that
 * assembles the escalation multipart request — against the same two seams the
 * example-based [RestSyncPushClientTest] uses, so no socket is opened:
 *
 *   - a recording [HttpTransport] that captures the [HttpRequest] the client
 *     built and returns a fixed 201 acknowledgment, so the test can inspect the
 *     exact [HttpBody.Multipart] the client produced;
 *   - the injectable `blobBytesReader` seam on [RestSyncPushClient], which the
 *     test wires to return a generated photo byte array for the escalation's
 *     blob path — the stand-in for "the bytes in the blob store" — so the test
 *     controls precisely what the blob store would hand back without touching
 *     the filesystem.
 *
 * For every generated photo payload the property asserts that the recorded
 * request's `photo` [MultipartPart] carries bytes **identical** to the bytes the
 * blob reader returned (Req 7.6): same length and same content, element for
 * element. It also confirms the `metadata` part is present alongside the photo,
 * matching the design's "metadata + photo bytes" shape.
 *
 * ## Generators
 *
 * The photo byte arrays span the input space that matters for byte-integrity:
 *   - the empty array (a zero-length blob is still copied verbatim);
 *   - small payloads;
 *   - large payloads (tens of KB), covering buffering/streaming boundaries;
 *   - arbitrary byte values across the full signed-byte range, so no byte is
 *     transformed, dropped, or re-encoded on the way into the part.
 *
 * Pure JVM: the transport and blob reader are plain in-memory fakes; no Android
 * runtime, Robolectric, or instrumentation is required. Runs a minimum of 100
 * iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 24: Escalation photo included in push`.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.merge
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.time.Instant
import java.util.UUID

class EscalationPhotoIncludedInPushPropertyTest : StringSpec({

    val now = Instant.ofEpochSecond(1_700_000_000L)
    val baseUrl = "https://api.example.test"

    fun config() = BackendConfig(baseUrl, object : AuthTokenProvider {
        override suspend fun bearerToken(): String? = "tok-123"
    })

    fun pendingMeta() = SyncMetadata(syncState = SyncState.PENDING, retryCount = 0, createdAt = now)

    // Photo byte arrays spanning the integrity-relevant input space: lengths
    // from 0 (empty blob) through large (~64 KB), with each byte drawn across
    // the full signed-byte range so no value is special-cased. The length arb
    // merges a set of explicit edge lengths (including 0 for the empty blob and
    // 65_536 for a large one) with a uniform spread of small/medium lengths, so
    // every iteration copies a different-sized payload verbatim.
    val photoLength: Arb<Int> =
        Arb.of(0, 1, 2, 16, 255, 1024, 65_536).merge(Arb.int(0, 4096))
    val photoBytes: Arb<ByteArray> = Arb.byteArray(
        length = photoLength,
        content = Arb.byte(),
    )

    "Feature: sagd-welltest-mobile, Property 24: Escalation photo included in push" {
        checkAll(pbtConfig(), photoBytes) { photo ->
            val id = UUID.randomUUID()
            val blobPath = "/blobs/$id.jpg"
            val escalation = Escalation(
                id = id,
                wellId = "WELL-9",
                priority = EscalationPriority.HIGH,
                photoRef = "blob-ref",
                latitude = 55.1,
                longitude = -118.8,
                locationUnavailable = false,
                capturedAt = now,
                sync = pendingMeta(),
            )

            val transport = RecordingTransport(HttpResponse(201, ackBody(id, "remote-esc")))
            val client = RestSyncPushClient(
                config = config(),
                transport = transport,
                payloadSource = EmptyPayloadSource,
                // The blob store seam: hand back the generated bytes for this
                // escalation's blob path (verbatim, as the real store would).
                blobBytesReader = { path -> if (path == blobPath) photo else null },
            )

            val result = client.upload(escalation, blobPath)

            // The upload must have been acknowledged (a request was actually
            // assembled and sent, so there is a recorded multipart to inspect).
            result.shouldBeInstanceOf<SyncPushResult.Acknowledged>()

            val sent = withClue("the client must have sent a request") {
                requireNotNull(transport.lastRequest)
            }
            val body = withClue("escalation push must use a multipart body") {
                sent.body.shouldBeInstanceOf<HttpBody.Multipart>()
            }

            // The design's "metadata + photo" shape: both parts are present.
            val names = body.parts.map { it.name }
            names shouldContain "metadata"
            names shouldContain "photo"

            // The photo part carries the blob bytes identically (Req 7.6):
            // same length AND same content, element for element. Comparing via
            // contentEquals (not reference) proves the bytes were copied through
            // without transformation, truncation, or re-encoding.
            val photoPart = body.parts.first { it.name == "photo" }
            withClue("photo part length must match the blob exactly") {
                photoPart.bytes.size shouldBe photo.size
            }
            withClue("photo part bytes must be identical to the blob bytes") {
                photoPart.bytes.contentEquals(photo) shouldBe true
            }
        }
    }
})

// --- test doubles ---

/** Fixed acknowledgment body matching the `{id, remoteId, status}` contract. */
private fun ackBody(id: UUID, remote: String) =
    """{"id":"$id","remoteId":"$remote","status":"accepted"}"""

/** Captures the last request and returns a fixed response (no socket). */
private class RecordingTransport(private val response: HttpResponse) : HttpTransport {
    var lastRequest: HttpRequest? = null
    override suspend fun send(request: HttpRequest): HttpResponse {
        lastRequest = request
        return response
    }
}

/**
 * A payload source with nothing in it. The multipart upload path resolves the
 * escalation from its argument and its photo via the blob reader, never from the
 * payload source, so this is only here to satisfy the constructor.
 */
private object EmptyPayloadSource : PushPayloadSource {
    override suspend fun wellTest(id: UUID) = null
    override suspend fun wellheadLog(id: UUID) = null
    override suspend fun subcoolReading(id: UUID) = null
}
