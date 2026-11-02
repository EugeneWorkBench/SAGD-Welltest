/*
 * Unit tests for the REST sync push client (Android) — task 23.1.
 *
 * Exercises the concrete [RestSyncPushClient] against a stub [HttpTransport]
 * and an in-memory [PushPayloadSource], so nothing here opens a socket — this
 * is exactly the interface seam the task calls for ("keep the transport
 * injectable so tests can stub responses"). The focus is the behaviour the sync
 * engine depends on:
 *
 *   - the right endpoint + JSON body is built per record type and carries the
 *     bearer auth header and the client-generated id (idempotency);
 *   - the response → [SyncPushResult] classification matches the design:
 *       2xx + ack  -> Acknowledged(remoteId)   (Req 2.3)
 *       409 / 422  -> NonRetryableFailure
 *       5xx        -> RetryableFailure
 *       transport  -> RetryableFailure
 *   - escalations go out as multipart (metadata JSON + photo bytes) (Req 7.6).
 *
 * Example-based (not a property test); Property 24 — the escalation photo being
 * byte-identical in the push — is covered separately by task 23.3.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.SyncableRecordType
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class RestSyncPushClientTest : StringSpec({

    val now = Instant.ofEpochSecond(1_700_000_000L)
    val baseUrl = "https://api.example.test"

    fun config(token: String? = "tok-123") =
        BackendConfig(baseUrl, object : AuthTokenProvider {
            override suspend fun bearerToken(): String? = token
        })

    fun pendingMeta() = SyncMetadata(syncState = SyncState.PENDING, retryCount = 0, createdAt = now)

    fun ackBody(id: UUID, remote: String) =
        """{"id":"$id","remoteId":"$remote","status":"accepted"}"""

    "well test push posts to /v1/well-tests with id, auth header, and is acknowledged" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val transport = RecordingTransport(HttpResponse(201, ackBody(id, "remote-xyz")))
        val client = RestSyncPushClient(
            config = config(),
            transport = transport,
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        val result = client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))

        result.shouldBeInstanceOf<SyncPushResult.Acknowledged>()
        result.remoteId shouldBe "remote-xyz"

        val sent = transport.lastRequest!!
        sent.method shouldBe HttpMethod.POST
        sent.url shouldBe "$baseUrl/v1/well-tests"
        sent.headers["Authorization"] shouldBe "Bearer tok-123"
        val json = JSONObject((sent.body as HttpBody.Json).text)
        json.getString("id") shouldBe id.toString()
        json.getString("wellId") shouldBe "WELL-1"
    }

    "wellhead log push targets /v1/wellhead-logs and sends only production fields" {
        val id = UUID.randomUUID()
        val log = WellheadLog(
            id = id,
            wellId = "WELL-2",
            logType = LogType.PRODUCTION,
            capturedAt = now,
            emulsionFlowRate = 12.5,
            casingGasPressure = 300.0,
            espMotorTemperature = 95.0,
            waterCutPercent = 40.0,
            sync = pendingMeta(),
        )
        val transport = RecordingTransport(HttpResponse(200, ackBody(id, "r1")))
        val client = RestSyncPushClient(
            config = config(),
            transport = transport,
            payloadSource = InMemoryPayloadSource(wellheadLogs = mapOf(id to log)),
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELLHEAD_LOG, pendingMeta()))

        val json = JSONObject((transport.lastRequest!!.body as HttpBody.Json).text)
        transport.lastRequest!!.url shouldBe "$baseUrl/v1/wellhead-logs"
        json.getString("logType") shouldBe "production"
        json.has("emulsionFlowRate") shouldBe true
        // Injection-only fields must be absent on a production log.
        json.has("steamInjectionPressure") shouldBe false
    }

    "subcool reading push targets /v1/subcool-readings" {
        val id = UUID.randomUUID()
        val reading = SubcoolReading(
            id = id,
            wellId = "WELL-3",
            casingPressure = 2000.0,
            pumpTemperature = 180.0,
            saturationTemperature = 212.0,
            subcoolValue = 32.0,
            isFlashing = false,
            capturedAt = now,
            sync = pendingMeta(),
        )
        val transport = RecordingTransport(HttpResponse(200, ackBody(id, "r2")))
        val client = RestSyncPushClient(
            config = config(),
            transport = transport,
            payloadSource = InMemoryPayloadSource(subcoolReadings = mapOf(id to reading)),
        )

        val result = client.push(
            SyncableRecord(id, SyncableRecordType.SUBCOOL_READING, pendingMeta()),
        )

        result.shouldBeInstanceOf<SyncPushResult.Acknowledged>()
        transport.lastRequest!!.url shouldBe "$baseUrl/v1/subcool-readings"
    }

    "409 conflict is a non-retryable failure" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val client = RestSyncPushClient(
            config = config(),
            transport = RecordingTransport(HttpResponse(409, """{"error":"conflict"}""")),
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        val result = client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))

        result.shouldBeInstanceOf<SyncPushResult.NonRetryableFailure>()
    }

    "422 validation error is a non-retryable failure" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val client = RestSyncPushClient(
            config = config(),
            transport = RecordingTransport(HttpResponse(422, """{"error":"invalid"}""")),
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))
            .shouldBeInstanceOf<SyncPushResult.NonRetryableFailure>()
    }

    "500 server error is a retryable failure" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val client = RestSyncPushClient(
            config = config(),
            transport = RecordingTransport(HttpResponse(503, "unavailable")),
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))
            .shouldBeInstanceOf<SyncPushResult.RetryableFailure>()
    }

    "a transport failure (timeout) is a retryable failure" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val client = RestSyncPushClient(
            config = config(),
            transport = ThrowingTransport(HttpTransportException("timed out")),
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))
            .shouldBeInstanceOf<SyncPushResult.RetryableFailure>()
    }

    "a 2xx without a parseable acknowledgment is treated as retryable, not acknowledged" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val client = RestSyncPushClient(
            config = config(),
            transport = RecordingTransport(HttpResponse(200, "not json")),
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))
            .shouldBeInstanceOf<SyncPushResult.RetryableFailure>()
    }

    "a missing payload for a queued id is retryable (never acknowledged)" {
        val id = UUID.randomUUID()
        val client = RestSyncPushClient(
            config = config(),
            transport = RecordingTransport(HttpResponse(200, ackBody(id, "r"))),
            payloadSource = InMemoryPayloadSource(), // empty store
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))
            .shouldBeInstanceOf<SyncPushResult.RetryableFailure>()
    }

    "escalation upload posts multipart with metadata JSON and the exact photo bytes" {
        val id = UUID.randomUUID()
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
        val photo = byteArrayOf(1, 2, 3, 4, 5)
        val transport = RecordingTransport(HttpResponse(201, ackBody(id, "remote-esc")))
        val client = RestSyncPushClient(
            config = config(),
            transport = transport,
            payloadSource = InMemoryPayloadSource(),
            blobBytesReader = { path -> if (path == "/blobs/$id.jpg") photo else null },
        )

        val result = client.upload(escalation, "/blobs/$id.jpg")

        result.shouldBeInstanceOf<SyncPushResult.Acknowledged>()
        result.remoteId shouldBe "remote-esc"

        val sent = transport.lastRequest!!
        sent.url shouldBe "$baseUrl/v1/escalations"
        sent.headers["Authorization"] shouldBe "Bearer tok-123"
        val parts = (sent.body as HttpBody.Multipart).parts
        val names = parts.map { it.name }
        names shouldContain "metadata"
        names shouldContain "photo"

        val metadata = parts.first { it.name == "metadata" }
        metadata.contentType shouldContain "json"
        val metaJson = JSONObject(String(metadata.bytes, Charsets.UTF_8))
        metaJson.getString("priority") shouldBe "high"
        metaJson.getString("id") shouldBe id.toString()
        // photoRef is a local reference and must not leak into the payload.
        metaJson.has("photoRef") shouldBe false

        // The photo part carries the blob bytes verbatim (Req 7.6).
        val photoPart = parts.first { it.name == "photo" }
        photoPart.bytes.toList() shouldBe photo.toList()
    }

    "escalation upload with an unreadable blob is retryable (not acknowledged)" {
        val id = UUID.randomUUID()
        val escalation = Escalation(
            id = id,
            wellId = "WELL-9",
            priority = EscalationPriority.LOW,
            photoRef = "blob-ref",
            capturedAt = now,
            sync = pendingMeta(),
        )
        val client = RestSyncPushClient(
            config = config(),
            transport = RecordingTransport(HttpResponse(201, ackBody(id, "x"))),
            payloadSource = InMemoryPayloadSource(),
            blobBytesReader = { null },
        )

        client.upload(escalation, "/missing.jpg")
            .shouldBeInstanceOf<SyncPushResult.RetryableFailure>()
    }

    "no auth header is sent when no token is available" {
        val id = UUID.randomUUID()
        val test = WellTest(id, "WELL-1", 0.5, 0.3, 0.2, now, pendingMeta())
        val transport = RecordingTransport(HttpResponse(200, ackBody(id, "r")))
        val client = RestSyncPushClient(
            config = config(token = null),
            transport = transport,
            payloadSource = InMemoryPayloadSource(wellTests = mapOf(id to test)),
        )

        client.push(SyncableRecord(id, SyncableRecordType.WELL_TEST, pendingMeta()))

        transport.lastRequest!!.headers.containsKey("Authorization") shouldBe false
    }
})

// --- test doubles ---

/** Captures the last request and returns a fixed response. */
private class RecordingTransport(private val response: HttpResponse) : HttpTransport {
    var lastRequest: HttpRequest? = null
    override suspend fun send(request: HttpRequest): HttpResponse {
        lastRequest = request
        return response
    }
}

/** Always throws, simulating a transport-level failure. */
private class ThrowingTransport(private val error: HttpTransportException) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = throw error
}

/** In-memory payload source backed by maps. */
private class InMemoryPayloadSource(
    private val wellTests: Map<UUID, WellTest> = emptyMap(),
    private val wellheadLogs: Map<UUID, WellheadLog> = emptyMap(),
    private val subcoolReadings: Map<UUID, SubcoolReading> = emptyMap(),
) : PushPayloadSource {
    override suspend fun wellTest(id: UUID): WellTest? = wellTests[id]
    override suspend fun wellheadLog(id: UUID): WellheadLog? = wellheadLogs[id]
    override suspend fun subcoolReading(id: UUID): SubcoolReading? = subcoolReadings[id]
}
