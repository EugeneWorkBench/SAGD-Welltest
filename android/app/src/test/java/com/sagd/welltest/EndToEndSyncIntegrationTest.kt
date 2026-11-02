/*
 * Integration test — end-to-end sync against a stub backend (Android) — task 23.4.
 *
 * Validates: Requirements 2.1, 2.3, 7.6
 *
 * This is the Android mirror of the iOS end-to-end sync integration test (task
 * 22.4). Where the per-record property tests (tasks 16.2-16.7) each isolate one
 * invariant with a stubbed push client, this test wires the WHOLE real push
 * path together and exercises it against a single programmable stub backend:
 *
 *     repository (SyncRepositoryImpl + RoomPushPayloadSource +
 *                 RoomEscalationBlobSource)
 *         -> SyncCycleRunner           (the real cycle + state machine driver)
 *             -> RestSyncPushClient     (the real REST client: endpoints, auth,
 *                                        JSON/multipart encoding, response
 *                                        classification)
 *                 -> HttpTransport      (the ONLY stubbed seam: a programmable
 *                                        StubBackend that scripts responses and
 *                                        records every request it received)
 *
 * Nothing between the repository and the socket is faked: the runner routes
 * records, the REST client builds the exact endpoint/body/headers and parses the
 * acknowledgment contract, and the repository runs its real oldest-first queue
 * query and its real per-record state machine. Only the transport is a stub —
 * exactly the seam the design calls out as injectable ("keep the transport
 * injectable so tests can stub responses").
 *
 * -- Why map-backed fake DAOs + a real temp-dir BlobStore, not a live Room DB --
 *
 * On iOS the mirror opens a real in-memory GRDB database because GRDB runs on
 * the host as a plain library. Room cannot do the equivalent in a pure-JVM unit
 * test: opening a Room database (even in-memory) needs an Android Context and
 * the native SQLite runtime, available only under Robolectric or on-device
 * instrumentation, neither present here. So, following the established pattern
 * across the Android sync/repository tests (e.g.
 * sync/NoDataLossOnFailedSyncPropertyTest,
 * sync/FiveRetryBoundaryAndManualRetryResetPropertyTest), the persistence
 * substrate is faked at the DAO seam — the DAOs are interfaces whose map-backed
 * fakes reproduce the exact query shapes the real Room queries pin down
 * (Daos.kt): nextPending = WHERE syncState = PENDING ORDER BY createdAt ASC
 * LIMIT, getById/update/upsert by primary key. The REAL SyncRepositoryImpl,
 * RoomPushPayloadSource, and RoomEscalationBlobSource run unchanged over those
 * fakes. The photo blob store, by contrast, is the REAL BlobStore rooted at a
 * JVM temp directory: BlobStore is pure java.io.File work with no Android
 * dependency, so the escalation photo is a genuine on-disk file the real client
 * streams — giving a true end-to-end multipart photo path (Req 7.6).
 *
 * Scenarios covered:
 *
 *   1. Happy path, mixed queue: a well test, a production wellhead log, and a
 *      subcool reading are enqueued PENDING with distinct createdAt. One cycle
 *      pushes them oldest-first (Req 2.1) to the correct endpoints with the
 *      bearer header; the stub acknowledges each; all three become SYNCHRONIZED
 *      carrying the stub's remoteId (Req 2.3).
 *   2. Escalation multipart with photo: an escalation whose photo is a real blob
 *      file is pushed; the stub receives a multipart body carrying the metadata
 *      JSON and the exact photo bytes, acknowledges, and the record becomes
 *      SYNCHRONIZED (Req 7.6).
 *   3. 5xx-then-success: the stub returns 503 on the first push of a record and
 *      a 2xx acknowledgment on the second. After the first cycle the record is
 *      still PENDING (retryCount 1, no data loss — Req 2.1 queue retained); after
 *      a later cycle it is SYNCHRONIZED (Req 2.3).
 *
 * kotest StringSpec + kotlinx-coroutines-test, matching the existing
 * conventions. Example-based (not a property test).
 */
package com.sagd.welltest

import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.OptionalSyncMetadataEntity
import com.sagd.welltest.localstore.SubcoolReadingDao
import com.sagd.welltest.localstore.SubcoolReadingEntity
import com.sagd.welltest.localstore.SyncMetadataEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.network.BackendConfig
import com.sagd.welltest.network.HttpBody
import com.sagd.welltest.network.HttpMethod
import com.sagd.welltest.network.HttpRequest
import com.sagd.welltest.network.HttpResponse
import com.sagd.welltest.network.HttpTransport
import com.sagd.welltest.network.RestSyncPushClient
import com.sagd.welltest.network.StaticAuthTokenProvider
import com.sagd.welltest.repository.RoomEscalationBlobSource
import com.sagd.welltest.repository.RoomPushPayloadSource
import com.sagd.welltest.repository.SyncRepositoryImpl
import com.sagd.welltest.sync.ExponentialBackoffPolicy
import com.sagd.welltest.sync.SyncClock
import com.sagd.welltest.sync.SyncCycleRunner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EndToEndSyncIntegrationTest : StringSpec({

    val baseUrl = "https://api.sagd.example.test"
    val token = "tok-e2e-123"
    val epoch = Instant.ofEpochSecond(1_700_000_000L)

    // -----------------------------------------------------------------------
    // Scenario 1: happy path, mixed queue pushed oldest-first and acknowledged.
    // -----------------------------------------------------------------------
    "mixed pending queue is pushed oldest-first to the right endpoints and all become SYNCHRONIZED" {
        runTest {
            val store = FakeStore()

            // Three records with DISTINCT createdAt so oldest-first is observable.
            // Seeded out of creation order to prove the queue ordering (not the
            // insertion order) drives the push sequence.
            val subcoolId = UUID.randomUUID()   // newest
            val wellTestId = UUID.randomUUID()  // oldest
            val logId = UUID.randomUUID()       // middle

            store.wellTests[wellTestId] = wellTestEntity(
                id = wellTestId, wellId = "WELL-1", createdAt = epoch.plusSeconds(10),
            )
            store.wellheadLogs[logId] = productionLogEntity(
                id = logId, wellId = "WELL-2", createdAt = epoch.plusSeconds(20),
            )
            store.subcoolReadings[subcoolId] = subcoolEntity(
                id = subcoolId, wellId = "WELL-3", createdAt = epoch.plusSeconds(30),
            )

            // The stub acknowledges every push, echoing a remoteId derived from
            // the client id so each record's specific remoteId can be verified.
            val backend = StubBackend { request ->
                HttpResponse(201, acknowledge(request))
            }

            val runner = wireRunner(store, backend, baseUrl, token, FixedSyncClock(epoch))

            val outcome = runner.runSyncCycle()

            withClue("outcome=$outcome") {
                outcome.processed shouldBe 3
                outcome.acknowledged shouldBe 3
                outcome.failed shouldBe 0
                outcome.skipped shouldBe 0
            }

            // The stub saw exactly three POSTs, oldest-first by createdAt.
            backend.requests.map { it.method } shouldContainExactly listOf(
                HttpMethod.POST, HttpMethod.POST, HttpMethod.POST,
            )
            backend.requests.map { it.url } shouldContainExactly listOf(
                "$baseUrl/v1/well-tests",
                "$baseUrl/v1/wellhead-logs",
                "$baseUrl/v1/subcool-readings",
            )

            // Every request carried the bearer auth header.
            backend.requests.forEach { req ->
                withClue("every request must carry the bearer token") {
                    req.headers["Authorization"] shouldBe "Bearer $token"
                }
            }

            // The payload ids match, in order, confirming the oldest-first push.
            backend.requests.map { idOf(it) } shouldContainExactly listOf(
                wellTestId.toString(), logId.toString(), subcoolId.toString(),
            )

            // All three records transitioned to SYNCHRONIZED with the stub's id.
            assertSynchronized(store.wellTests[wellTestId]!!.sync.syncState, store.wellTests[wellTestId]!!.sync.remoteId, wellTestId)
            assertSynchronized(store.wellheadLogs[logId]!!.sync.syncState, store.wellheadLogs[logId]!!.sync.remoteId, logId)
            val subcoolMeta = store.subcoolReadings[subcoolId]!!.sync
            withClue("subcool reading sync metadata after cycle") {
                subcoolMeta?.syncState shouldBe SyncState.SYNCHRONIZED
                subcoolMeta?.remoteId shouldBe remoteIdFor(subcoolId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scenario 2: escalation multipart carries the real photo bytes (Req 7.6).
    // -----------------------------------------------------------------------
    "escalation push sends multipart with the exact photo bytes and becomes SYNCHRONIZED" {
        runTest {
            val store = FakeStore()
            val blobDir = Files.createTempDirectory("sagd-e2e-blobs").toFile()
            try {
                val blobStore = BlobStore(blobDir)

                // A real photo blob written to a real temp file, exactly as the
                // escalation capture path would, so the client streams it off
                // disk through the real RoomEscalationBlobSource.
                val photoBytes = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 1, 2, 3, 4, 5, 6, 7, 8)
                val photoRef = blobStore.write(photoBytes)

                val escalationId = UUID.randomUUID()
                store.escalations[escalationId] = escalationEntity(
                    id = escalationId,
                    wellId = "WELL-9",
                    priority = EscalationPriority.HIGH,
                    photoRef = photoRef,
                    createdAt = epoch.plusSeconds(5),
                )

                val backend = StubBackend { request ->
                    HttpResponse(201, acknowledge(request))
                }

                val runner = wireRunner(
                    store, backend, baseUrl, token, FixedSyncClock(epoch), blobStore,
                )

                val outcome = runner.runSyncCycle()

                withClue("outcome=$outcome") {
                    outcome.processed shouldBe 1
                    outcome.acknowledged shouldBe 1
                    outcome.failed shouldBe 0
                }

                val request = backend.requests.single()
                request.method shouldBe HttpMethod.POST
                request.url shouldBe "$baseUrl/v1/escalations"
                request.headers["Authorization"] shouldBe "Bearer $token"

                val body = request.body
                withClue("escalation must be pushed as a multipart body") {
                    (body is HttpBody.Multipart) shouldBe true
                }
                val parts = (body as HttpBody.Multipart).parts
                parts.map { it.name } shouldContainExactlyInAnyOrder listOf("metadata", "photo")

                // The metadata part carries the escalation's JSON, and the
                // local-only photoRef must not leak into the payload.
                val metadata = parts.first { it.name == "metadata" }
                val metaJson = JSONObject(String(metadata.bytes, Charsets.UTF_8))
                metaJson.getString("id") shouldBe escalationId.toString()
                metaJson.getString("priority") shouldBe "high"
                metaJson.getString("wellId") shouldBe "WELL-9"
                metaJson.has("photoRef") shouldBe false

                // The photo part carries the blob bytes verbatim (Req 7.6).
                val photo = parts.first { it.name == "photo" }
                withClue("multipart photo bytes must be byte-identical to the stored blob") {
                    photo.bytes.toList() shouldBe photoBytes.toList()
                }

                // The escalation is now SYNCHRONIZED with the stub's remoteId.
                val meta = store.escalations[escalationId]!!.sync
                withClue("escalation sync metadata after cycle") {
                    meta.syncState shouldBe SyncState.SYNCHRONIZED
                    meta.remoteId shouldBe remoteIdFor(escalationId)
                }
            } finally {
                blobDir.deleteRecursively()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scenario 3: a 5xx then a success — pending then synchronized (Req 2.1, 2.3).
    // -----------------------------------------------------------------------
    "a 5xx push leaves the record PENDING, and a later successful push makes it SYNCHRONIZED" {
        runTest {
            val store = FakeStore()
            val wellTestId = UUID.randomUUID()
            store.wellTests[wellTestId] = wellTestEntity(
                id = wellTestId, wellId = "WELL-7", createdAt = epoch.plusSeconds(1),
            )

            // First push -> 503 (retryable); every later push -> 201 ack.
            var seen = 0
            val backend = StubBackend { request ->
                seen++
                if (seen == 1) {
                    HttpResponse(503, "service unavailable")
                } else {
                    HttpResponse(201, acknowledge(request))
                }
            }

            // A single advancing clock shared by the runner (backoff eligibility)
            // and the repository (stamps lastAttemptAt on failure), so advancing
            // it past the backoff window makes the record eligible next cycle.
            val clock = MutableClock(epoch)
            val backoff = ExponentialBackoffPolicy()
            val runner = wireRunner(store, backend, baseUrl, token, clock, backoff = backoff)

            // --- Cycle 1: the 503 is a retryable failure -> still PENDING. ---
            val first = runner.runSyncCycle()
            withClue("first cycle outcome=$first") {
                first.processed shouldBe 1
                first.failed shouldBe 1
                first.acknowledged shouldBe 0
            }
            val afterFail = store.wellTests[wellTestId]!!
            withClue("after a 5xx the record must stay PENDING and be retained (Req 2.1)") {
                afterFail.sync.syncState shouldBe SyncState.PENDING
                afterFail.sync.retryCount shouldBe 1
                // Captured payload is untouched — no data loss.
                afterFail.wellId shouldBe "WELL-7"
            }

            // Advance past the backoff window so the record is eligible again.
            clock.advancePastBackoff(backoff)

            // --- Cycle 2: the retry is acknowledged -> SYNCHRONIZED. ---
            val second = runner.runSyncCycle()
            withClue("second cycle outcome=$second") {
                second.processed shouldBe 1
                second.acknowledged shouldBe 1
                second.failed shouldBe 0
            }
            val afterAck = store.wellTests[wellTestId]!!
            withClue("after the successful retry the record must be SYNCHRONIZED (Req 2.3)") {
                afterAck.sync.syncState shouldBe SyncState.SYNCHRONIZED
                afterAck.sync.remoteId shouldBe remoteIdFor(wellTestId)
            }

            // The stub saw two pushes of the same client id (idempotent retry).
            backend.requests.size shouldBe 2
            backend.requests.map { idOf(it) } shouldContainExactly listOf(
                wellTestId.toString(), wellTestId.toString(),
            )
        }
    }
})

// ===========================================================================
// Wiring: assemble the REAL push stack over the fakes + stub transport.
// ===========================================================================

/**
 * Build a [SyncCycleRunner] wired to the production repository, payload source,
 * escalation blob source, and REST push client, with only the HTTP transport
 * stubbed by [backend]. A real [BlobStore] (temp-dir backed) may be supplied for
 * scenarios that push an escalation; otherwise a throwaway temp-dir store is
 * created (unused when no escalation is seeded).
 */
private fun wireRunner(
    store: FakeStore,
    backend: StubBackend,
    baseUrl: String,
    token: String,
    clock: SyncClock,
    blobStore: BlobStore = BlobStore(Files.createTempDirectory("sagd-e2e-unused").toFile()),
    backoff: ExponentialBackoffPolicy = ExponentialBackoffPolicy(),
): SyncCycleRunner {
    val config = BackendConfig(baseUrl, StaticAuthTokenProvider(token))

    // The REAL REST client, used both as the JSON push client and as the
    // multipart blob-upload client. Its default filesystem blobBytesReader
    // streams the real temp-dir blob, so the photo path is genuinely end to end.
    val restClient = RestSyncPushClient(
        config = config,
        transport = backend,
        payloadSource = RoomPushPayloadSource(
            wellTestDao = store.wellTestDao,
            wellheadLogDao = store.wellheadLogDao,
            subcoolReadingDao = store.subcoolReadingDao,
        ),
    )

    // The repository uses a java.time.Clock on the same timeline as the runner's
    // SyncClock so lastAttemptAt and backoff eligibility stay consistent.
    val repository = SyncRepositoryImpl(
        wellTestDao = store.wellTestDao,
        wellheadLogDao = store.wellheadLogDao,
        subcoolReadingDao = store.subcoolReadingDao,
        escalationDao = store.escalationDao,
        clock = clock.asJavaClock(),
    )

    return SyncCycleRunner(
        syncRepository = repository,
        pushClient = restClient,
        blobUploadClient = restClient,
        blobSource = RoomEscalationBlobSource(store.escalationDao, blobStore),
        backoffPolicy = backoff,
        clock = clock,
        batchSize = 50,
    )
}

// ===========================================================================
// The programmable stub backend (the one and only stubbed seam).
// ===========================================================================

/**
 * A programmable [HttpTransport] standing in for the Cloud_Backend. It records
 * every request it receives (in send order) and returns whatever the supplied
 * [responder] scripts for that request, so a test can assert what the real REST
 * client sent and drive success/failure sequences deterministically.
 */
private class StubBackend(
    private val responder: (HttpRequest) -> HttpResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        return responder(request)
    }
}

/** The remote id the stub mints for a given client record id. */
private fun remoteIdFor(id: UUID): String = "remote-$id"

/** Extract the client `id` from a request body (JSON push or multipart metadata). */
private fun idOf(request: HttpRequest): String =
    when (val body = request.body) {
        is HttpBody.Json -> JSONObject(body.text).getString("id")
        is HttpBody.Multipart -> {
            val metadata = body.parts.first { it.name == "metadata" }
            JSONObject(String(metadata.bytes, Charsets.UTF_8)).getString("id")
        }
        HttpBody.None -> error("request carried no body")
    }

/**
 * Build the acknowledgment contract body the backend returns on success:
 * `{ "id": "<clientId>", "remoteId": "remote-<clientId>", "status": "accepted" }`,
 * echoing the id the client sent so the test can assert per-record association.
 */
private fun acknowledge(request: HttpRequest): String {
    val clientId = idOf(request)
    return JSONObject()
        .put("id", clientId)
        .put("remoteId", "remote-$clientId")
        .put("status", "accepted")
        .toString()
}

/** Shared assertion: a record is SYNCHRONIZED carrying the stub's remoteId. */
private fun assertSynchronized(state: SyncState, remoteId: String?, id: UUID) {
    withClue("record $id must be SYNCHRONIZED with remoteId=${remoteIdFor(id)}") {
        state shouldBe SyncState.SYNCHRONIZED
        remoteId shouldBe remoteIdFor(id)
    }
}

// ===========================================================================
// Clocks
// ===========================================================================

/** Converts a [SyncClock] to a [java.time.Clock] reading the same instant. */
private fun SyncClock.asJavaClock(): Clock = object : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = this@asJavaClock.now()
}

/** A clock pinned at a fixed instant; fresh records are always eligible. */
private class FixedSyncClock(private val fixed: Instant) : SyncClock {
    override fun now(): Instant = fixed
    override suspend fun sleep(seconds: Double) = Unit
}

/**
 * A clock whose instant only moves when the test advances it, used for the
 * 5xx-then-success scenario so the retried record becomes eligible after its
 * backoff window elapses.
 */
private class MutableClock(start: Instant) : SyncClock {
    @Volatile
    private var current: Instant = start

    override fun now(): Instant = current
    override suspend fun sleep(seconds: Double) = Unit

    /** Jump just past the largest possible backoff window. */
    fun advancePastBackoff(policy: ExponentialBackoffPolicy) {
        current = current.plusMillis((policy.delaySeconds(5) * 1000).toLong() + 1_000)
    }
}

// ===========================================================================
// Map-backed fake store + DAOs (the persistence substrate standing in for Room).
// ===========================================================================
//
// The DAO fakes reproduce exactly the query shapes the real Room DAOs pin down
// (Daos.kt): nextPending returns PENDING rows oldest-first by createdAt, limited;
// getById/update/upsert key on the primary id. The REAL SyncRepositoryImpl,
// RoomPushPayloadSource, and RoomEscalationBlobSource run unchanged over them.

private class FakeStore {
    val wellTests = linkedMapOf<UUID, WellTestEntity>()
    val wellheadLogs = linkedMapOf<UUID, WellheadLogEntity>()
    val subcoolReadings = linkedMapOf<UUID, SubcoolReadingEntity>()
    val escalations = linkedMapOf<UUID, EscalationEntity>()

    val wellTestDao = FakeWellTestDao(this)
    val wellheadLogDao = FakeWellheadLogDao(this)
    val subcoolReadingDao = FakeSubcoolReadingDao(this)
    val escalationDao = FakeEscalationDao(this)
}

private class FakeWellTestDao(private val store: FakeStore) : WellTestDao {
    override suspend fun upsert(test: WellTestEntity) { store.wellTests[test.id] = test }
    override suspend fun getById(id: UUID): WellTestEntity? = store.wellTests[id]
    override suspend fun getForWell(wellId: String): List<WellTestEntity> =
        store.wellTests.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        store.wellTests.values
            .filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }
            .take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> =
        flowOf(store.wellTests[id]?.sync?.syncState)
    override suspend fun update(test: WellTestEntity) { store.wellTests[test.id] = test }
}

private class FakeWellheadLogDao(private val store: FakeStore) : WellheadLogDao {
    override suspend fun upsert(log: WellheadLogEntity) { store.wellheadLogs[log.id] = log }
    override suspend fun getById(id: UUID): WellheadLogEntity? = store.wellheadLogs[id]
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> =
        store.wellheadLogs.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        store.wellheadLogs.values
            .filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }
            .take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> =
        flowOf(store.wellheadLogs[id]?.sync?.syncState)
    override suspend fun update(log: WellheadLogEntity) { store.wellheadLogs[log.id] = log }
}

private class FakeSubcoolReadingDao(private val store: FakeStore) : SubcoolReadingDao {
    override suspend fun upsert(reading: SubcoolReadingEntity) {
        store.subcoolReadings[reading.id] = reading
    }
    override suspend fun getById(id: UUID): SubcoolReadingEntity? = store.subcoolReadings[id]
    override suspend fun getForWell(wellId: String): List<SubcoolReadingEntity> =
        store.subcoolReadings.values.filter { it.wellId == wellId }
            .sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<SubcoolReadingEntity> =
        store.subcoolReadings.values
            .filter { it.sync?.syncState == pending }
            .sortedBy { it.sync?.createdAt }
            .take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> =
        flowOf(store.subcoolReadings[id]?.sync?.syncState)
    override suspend fun update(reading: SubcoolReadingEntity) {
        store.subcoolReadings[reading.id] = reading
    }
}

private class FakeEscalationDao(private val store: FakeStore) : EscalationDao {
    override suspend fun upsert(escalation: EscalationEntity) {
        store.escalations[escalation.id] = escalation
    }
    override suspend fun getById(id: UUID): EscalationEntity? = store.escalations[id]
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        store.escalations.values
            .filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }
            .take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> =
        flowOf(store.escalations[id]?.sync?.syncState)
    override suspend fun allPhotoRefs(): List<String> =
        store.escalations.values.map { it.photoRef }
    override suspend fun update(escalation: EscalationEntity) {
        store.escalations[escalation.id] = escalation
    }
    override suspend fun delete(escalation: EscalationEntity) {
        store.escalations.remove(escalation.id)
    }
}

// ===========================================================================
// Entity builders (PENDING, retryCount 0, no prior attempt -> eligible).
// ===========================================================================

private fun pendingMeta(createdAt: Instant) =
    SyncMetadataEntity(
        syncState = SyncState.PENDING,
        retryCount = 0,
        createdAt = createdAt,
        lastAttemptAt = null,
        remoteId = null,
    )

private fun pendingOptionalMeta(createdAt: Instant) =
    OptionalSyncMetadataEntity(
        syncState = SyncState.PENDING,
        retryCount = 0,
        createdAt = createdAt,
        lastAttemptAt = null,
        remoteId = null,
    )

private fun wellTestEntity(id: UUID, wellId: String, createdAt: Instant): WellTestEntity =
    WellTestEntity(
        id = id,
        wellId = wellId,
        oilRatio = 0.5,
        waterRatio = 0.3,
        gasRatio = 0.2,
        capturedAt = createdAt,
        sync = pendingMeta(createdAt),
    )

private fun productionLogEntity(id: UUID, wellId: String, createdAt: Instant): WellheadLogEntity =
    WellheadLogEntity(
        id = id,
        wellId = wellId,
        logType = LogType.PRODUCTION,
        capturedAt = createdAt,
        emulsionFlowRate = 12.5,
        casingGasPressure = 300.0,
        espMotorTemperature = 95.0,
        waterCutPercent = 40.0,
        sync = pendingMeta(createdAt),
    )

private fun subcoolEntity(id: UUID, wellId: String, createdAt: Instant): SubcoolReadingEntity =
    SubcoolReadingEntity(
        id = id,
        wellId = wellId,
        casingPressure = 2000.0,
        pumpTemperature = 180.0,
        saturationTemperature = 212.0,
        subcoolValue = 32.0,
        isFlashing = false,
        capturedAt = createdAt,
        sync = pendingOptionalMeta(createdAt),
    )

private fun escalationEntity(
    id: UUID,
    wellId: String,
    priority: EscalationPriority,
    photoRef: String,
    createdAt: Instant,
): EscalationEntity =
    EscalationEntity(
        id = id,
        wellId = wellId,
        priority = priority,
        photoRef = photoRef,
        latitude = 55.1,
        longitude = -118.8,
        locationUnavailable = false,
        capturedAt = createdAt,
        sync = pendingMeta(createdAt),
    )
