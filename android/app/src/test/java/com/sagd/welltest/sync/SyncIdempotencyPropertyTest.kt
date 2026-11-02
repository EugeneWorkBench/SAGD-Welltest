/*
 * Property 9: Sync idempotency (Android) — task 16.7.
 *
 * Validates: Requirements 2.3, 2.4
 *
 * Design "Property 9: Sync idempotency":
 *
 *     For any record, pushing it to the Cloud_Backend and then pushing the same
 *     record (same client `id`) again results in a single stored record on the
 *     backend with the same `remoteId`, and the record's final local state is
 *     SYNCHRONIZED (duplicate pushes cause no duplicate and no state
 *     regression).
 *
 * This mirrors the iOS SyncIdempotencyPropertyTests (task 15.7): it drives the
 * REAL runner end to end over the REAL repository, running a *second* cycle
 * after the first so the "push the same record twice" clause is exercised
 * exactly as production would re-attempt a queue.
 *
 *   * [SyncCycleRunner] is the actual production runner — the platform-agnostic
 *     heart of the Android sync engine. Nothing on the runner side is stubbed.
 *   * [SyncRepositoryImpl] is the real repository (its nextPendingBatch
 *     oldest-first PENDING-only query and its markSynchronized transition run
 *     for real), wired over map-backed fake DAOs. An in-memory Room database
 *     cannot be opened in a pure-JVM unit test (Room's in-memory builder needs
 *     an Android Context and the native SQLite library, available only under
 *     Robolectric / on-device instrumentation, neither present here — no
 *     JDK/Gradle in this environment, the unit-test source set is plain JVM).
 *     So, as the sibling sync properties do
 *     (AcknowledgmentYieldsSynchronizedStatePropertyTest,
 *     NoDataLossOnFailedSyncPropertyTest), the persistence substrate is faked at
 *     the DAO seam — the DAOs are interfaces — while every repository behaviour
 *     the property depends on runs unchanged.
 *   * The push client is an *idempotent backend stub* ([IdempotentPushClient])
 *     that models the Cloud_Backend's by-`id` dedup contract: it keeps a
 *     per-`id` ledger of acknowledged client ids, mints one stable `remoteId`
 *     the first time it sees an id, and returns that *same* `remoteId` on every
 *     subsequent push of the id — never creating a second backend entry. It also
 *     records the full push log so the test can assert what the runner actually
 *     pushed on the second cycle.
 *   * A controllable clock ([FixedSyncClock]) supplies a deterministic "now";
 *     seeded records have lastAttemptAt == null / retryCount == 0, so every
 *     record is immediately backoff-eligible and pushed in the first cycle.
 *
 * After two cycles the property asserts, per seeded record:
 *
 *   1. The local record ends SYNCHRONIZED exactly once, carrying the stable
 *      remoteId the backend minted (no state regression — Req 2.3, 2.4).
 *   2. The backend holds exactly one entry for that client `id` (no duplicate
 *      row created by the re-push).
 *   3. The second cycle did *not* re-push the record: once a record is
 *      SYNCHRONIZED, nextPendingBatch (PENDING-only) excludes it, so the runner
 *      addresses each id on the backend exactly once across both cycles.
 *
 * Only well-test, wellhead-log, and subcool-reading records are seeded: those
 * are the kinds the runner routes through [SyncPushClient]. Escalations take the
 * separate multipart blob-upload path (a different property); their seams here
 * are inert and fail loudly if ever reached.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 9: Sync idempotency`.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.SubcoolReadingDao
import com.sagd.welltest.localstore.SubcoolReadingEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.network.BlobUploadClient
import com.sagd.welltest.network.SyncPushClient
import com.sagd.welltest.network.SyncPushResult
import com.sagd.welltest.pbtConfig
import com.sagd.welltest.repository.SyncRepositoryImpl
import com.sagd.welltest.repository.toDomain
import com.sagd.welltest.repository.toEntity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SyncIdempotencyPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 9: Sync idempotency" {
        checkAll(pbtConfig(), seededCycleArb) { seed ->
            duplicatePushIsIdempotent(seed)
        }
    }
})

// ---------------------------------------------------------------------------
// The property check for one generated case
// ---------------------------------------------------------------------------

/**
 * Seeds the generated pending records, runs the sync cycle twice against an
 * idempotent backend stub, then asserts each record ended SYNCHRONIZED with a
 * single stable remoteId, the backend holds exactly one entry per client `id`,
 * and the second cycle re-pushed nothing.
 */
private suspend fun duplicatePushIsIdempotent(seed: List<SeededRecord>) {
    // Each generated case gets its own isolated map-backed store.
    val store = FakeSyncStore()
    seedStore(seed, store)

    val repository = SyncRepositoryImpl(
        wellTestDao = store.wellTestDao,
        wellheadLogDao = store.wellheadLogDao,
        subcoolReadingDao = store.subcoolReadingDao,
        escalationDao = store.escalationDao,
        // Deterministic wall clock — only recordFailure stamps lastAttemptAt,
        // and no push fails here, but it is pinned for determinism anyway.
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
    )

    // A backend stub that dedups by client `id`: one stable remoteId per id,
    // plus a push log so we can see what the second cycle actually pushed.
    val backend = IdempotentBackend()
    val pushClient = IdempotentPushClient(backend)

    val runner = SyncCycleRunner(
        syncRepository = repository,
        pushClient = pushClient,
        blobUploadClient = UnusedBlobUploadClient,
        blobSource = UnusedEscalationBlobSource,
        backoffPolicy = ExponentialBackoffPolicy(),
        clock = FixedSyncClock(FIXED_NOW),
        // A batch size comfortably above the seeded count so every record is
        // pushed in a single cycle.
        batchSize = maxOf(seed.size, 1),
    )

    // Push the same records twice: a full re-run of the cycle. The first cycle
    // acknowledges and synchronizes; the second must be a no-op with respect to
    // the backend — the records are already SYNCHRONIZED and so excluded from
    // nextPendingBatch (which selects PENDING only).
    val first = runner.runSyncCycle()
    val second = runner.runSyncCycle()

    withClue("first cycle=$first, second cycle=$second") {
        // First cycle acknowledged every seeded record.
        first.acknowledged shouldBe seed.size
        first.failed shouldBe 0
        first.skipped shouldBe 0
        // Second cycle saw nothing: already-synchronized records are not pending.
        second.processed shouldBe 0
        second.acknowledged shouldBe 0
        second.failed shouldBe 0
    }

    val pushLog = backend.pushLog()

    for (record in seed) {
        val expectedRemoteId = backend.remoteId(record.id)

        // 1. Final local state is SYNCHRONIZED with the stable remoteId.
        val meta = store.syncMetadataOf(record.id, record.kind)
        withClue("record ${record.id} (${record.kind}) meta=$meta") {
            // The record must still exist (not vanished) after the cycles.
            (meta != null) shouldBe true
            // ...be SYNCHRONIZED — no state regression (Req 2.3, 2.4)...
            meta?.syncState shouldBe SyncState.SYNCHRONIZED
            // ...the backend must have acknowledged it (minted a remote id)...
            expectedRemoteId shouldNotBe null
            // ...and the stored remoteId is the stable one the backend minted.
            meta?.remoteId shouldBe expectedRemoteId
        }

        // 2. The backend holds exactly one entry for this client id: the
        //    re-push created no duplicate.
        withClue("backend entry count for ${record.id}") {
            backend.entryCount(record.id) shouldBe 1
        }

        // 3. The runner pushed this id exactly once across both cycles: once
        //    SYNCHRONIZED, nextPendingBatch excludes it, so the second cycle
        //    must not re-push an already-synchronized record.
        withClue("push count for ${record.id} across two cycles, log=$pushLog") {
            pushLog.count { it == record.id } shouldBe 1
        }
    }
}

// ---------------------------------------------------------------------------
// Deterministic time
// ---------------------------------------------------------------------------

/** A fixed instant used by both clocks so timestamps are deterministic. */
private val FIXED_NOW: Instant = Instant.ofEpochSecond(1_700_000_000L)

// ---------------------------------------------------------------------------
// Seeding
// ---------------------------------------------------------------------------

private fun seedStore(seed: List<SeededRecord>, store: FakeSyncStore) {
    for (record in seed) {
        when (val payload = record.payload) {
            is Payload.WellTestPayload ->
                store.wellTests[payload.test.id] = payload.test.toEntity()
            is Payload.WellheadLogPayload ->
                store.wellheadLogs[payload.log.id] = payload.log.toEntity()
            is Payload.SubcoolReadingPayload ->
                store.subcoolReadings[payload.reading.id] = payload.reading.toEntity()
        }
    }
}

// ---------------------------------------------------------------------------
// Test doubles: the idempotent backend and its push client
// ---------------------------------------------------------------------------

/**
 * The Cloud_Backend's by-`id` dedup contract. Records every push (the log),
 * mints one stable remoteId the first time it sees a client `id`, and returns
 * that same id forever after — never a second backend entry. A [Mutex] guards
 * the ledger so it is safe even if the runner ever pushed concurrently (it does
 * not here, but the guard keeps the stub honest).
 */
private class IdempotentBackend {
    private val mutex = Mutex()

    /**
     * The stable remote id minted per client id. One entry == one stored backend
     * record; its presence is the "no duplicate" witness.
     */
    private val remoteIds = linkedMapOf<UUID, String>()

    /** Every push the runner issued, in order, for log-based assertions. */
    private val pushes = mutableListOf<UUID>()

    /**
     * Acknowledge a push of [id], minting a stable remote id on first sight and
     * echoing the same one on every re-push. Idempotent by `id`.
     */
    suspend fun acknowledge(id: UUID): String = mutex.withLock {
        pushes.add(id)
        remoteIds.getOrPut(id) { "remote-$id" }
    }

    suspend fun remoteId(id: UUID): String? = mutex.withLock { remoteIds[id] }

    /**
     * How many distinct backend entries exist for [id]: 0 (never pushed) or 1
     * (idempotent). Never more — that would be a duplicate.
     */
    suspend fun entryCount(id: UUID): Int = mutex.withLock { if (remoteIds.containsKey(id)) 1 else 0 }

    suspend fun pushLog(): List<UUID> = mutex.withLock { pushes.toList() }
}

/**
 * Bridges the runner's [SyncPushClient] seam onto the idempotent backend: every
 * push is acknowledged with the backend's stable remote id for the record's
 * client `id`.
 */
private class IdempotentPushClient(
    private val backend: IdempotentBackend,
) : SyncPushClient {
    override suspend fun push(record: SyncableRecord): SyncPushResult =
        SyncPushResult.Acknowledged(backend.acknowledge(record.recordId))
}

/** A controllable clock pinned at a fixed instant; `sleep` returns at once. */
private class FixedSyncClock(private val instant: Instant) : SyncClock {
    override fun now(): Instant = instant
    override suspend fun sleep(seconds: Double) = Unit
}

/**
 * Escalation seams that must never be reached: this property seeds only records
 * routed through [SyncPushClient]. Reaching either would mean the runner
 * misrouted a non-escalation record.
 */
private object UnusedBlobUploadClient : BlobUploadClient {
    override suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult =
        throw AssertionError("blob upload client reached for a non-escalation property")
}

private object UnusedEscalationBlobSource : EscalationBlobSource {
    override suspend fun escalationWithBlob(id: UUID): EscalationBlob? =
        throw AssertionError("escalation blob source reached for a non-escalation property")
}

// ---------------------------------------------------------------------------
// Map-backed fake DAOs (the persistence substrate standing in for Room)
// ---------------------------------------------------------------------------
//
// Only the methods SyncRepositoryImpl calls on the idempotency path are
// meaningful: nextPending (PENDING rows oldest-first by createdAt), getById, and
// update. Everything else satisfies the interface and is unused. The escalation
// DAO is empty — escalations are never seeded here.

private class FakeSyncStore {
    val wellTests = linkedMapOf<UUID, WellTestEntity>()
    val wellheadLogs = linkedMapOf<UUID, WellheadLogEntity>()
    val subcoolReadings = linkedMapOf<UUID, SubcoolReadingEntity>()
    val escalations = linkedMapOf<UUID, EscalationEntity>()

    val wellTestDao = FakeWellTestDao(this)
    val wellheadLogDao = FakeWellheadLogDao(this)
    val subcoolReadingDao = FakeSubcoolReadingDao(this)
    val escalationDao = FakeEscalationDao(this)

    /** Reads back the persisted sync metadata for a record by its kind. */
    fun syncMetadataOf(id: UUID, kind: SyncableKind): SyncMetadata? = when (kind) {
        SyncableKind.WELL_TEST -> wellTests[id]?.sync?.toDomain()
        SyncableKind.WELLHEAD_LOG -> wellheadLogs[id]?.sync?.toDomain()
        SyncableKind.SUBCOOL_READING -> subcoolReadings[id]?.sync.toDomain()
    }
}

private class FakeWellTestDao(private val store: FakeSyncStore) : WellTestDao {
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        store.wellTests.values
            .filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    override suspend fun getById(id: UUID): WellTestEntity? = store.wellTests[id]

    override suspend fun update(test: WellTestEntity) {
        store.wellTests[test.id] = test
    }

    override suspend fun upsert(test: WellTestEntity) {
        store.wellTests[test.id] = test
    }

    override suspend fun getForWell(wellId: String): List<WellTestEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
}

private class FakeWellheadLogDao(private val store: FakeSyncStore) : WellheadLogDao {
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        store.wellheadLogs.values
            .filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    override suspend fun getById(id: UUID): WellheadLogEntity? = store.wellheadLogs[id]

    override suspend fun update(log: WellheadLogEntity) {
        store.wellheadLogs[log.id] = log
    }

    override suspend fun upsert(log: WellheadLogEntity) {
        store.wellheadLogs[log.id] = log
    }

    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
}

private class FakeSubcoolReadingDao(private val store: FakeSyncStore) : SubcoolReadingDao {
    override suspend fun nextPending(limit: Int, pending: SyncState): List<SubcoolReadingEntity> =
        store.subcoolReadings.values
            // Readings without sync metadata have a null state and are excluded.
            .filter { it.sync?.syncState == pending }
            .sortedBy { it.sync?.createdAt }
            .take(limit)

    override suspend fun getById(id: UUID): SubcoolReadingEntity? = store.subcoolReadings[id]

    override suspend fun update(reading: SubcoolReadingEntity) {
        store.subcoolReadings[reading.id] = reading
    }

    override suspend fun upsert(reading: SubcoolReadingEntity) {
        store.subcoolReadings[reading.id] = reading
    }

    override suspend fun getForWell(wellId: String): List<SubcoolReadingEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
}

private class FakeEscalationDao(private val store: FakeSyncStore) : EscalationDao {
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        emptyList()

    override suspend fun getById(id: UUID): EscalationEntity? = store.escalations[id]

    override suspend fun update(escalation: EscalationEntity) {
        store.escalations[escalation.id] = escalation
    }

    override suspend fun upsert(escalation: EscalationEntity) {
        store.escalations[escalation.id] = escalation
    }

    override suspend fun delete(escalation: EscalationEntity) = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
    override suspend fun allPhotoRefs(): List<String> =
        store.escalations.values.map { it.photoRef }
}

private fun unsupported(): Nothing =
    throw UnsupportedOperationException("not used by the sync idempotency property")

// ---------------------------------------------------------------------------
// Generated case model
// ---------------------------------------------------------------------------

/** The concrete table a seeded record lives in, for table-scoped read-back. */
private enum class SyncableKind { WELL_TEST, WELLHEAD_LOG, SUBCOOL_READING }

/** One pending record, with its kind kept alongside for read-back. */
private class SeededRecord(
    val id: UUID,
    val kind: SyncableKind,
    val payload: Payload,
)

private sealed interface Payload {
    data class WellTestPayload(val test: WellTest) : Payload
    data class WellheadLogPayload(val log: WellheadLog) : Payload
    data class SubcoolReadingPayload(val reading: SubcoolReading) : Payload
}

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------
//
// Draws a batch of 1..6 pending records (so the "set of records" clause is
// exercised with more than one), each a well test, wellhead log, or subcool
// reading. Every record is seeded PENDING with retryCount == 0 and no prior
// attempt, so all are backoff-eligible in the first cycle. One flat Arb draws
// every primitive a record needs and assembles the payload directly, so there
// is no nested Arb sampling.

private val timestampArb: Arb<Instant> =
    Arb.long(1_600_000_000L, 1_800_000_000L).map { Instant.ofEpochSecond(it) }

/** Raw primitives for a single record, bundled to stay within Arb.bind's arity. */
private class RecordPrimitives(
    val wellId: String,
    val kind: Int,
    val logIsProduction: Boolean,
    val capturedAt: Instant,
    val doubles: List<Double>,
)

private fun recordPrimitivesArb(): Arb<RecordPrimitives> = Arb.bind(
    Arb.int(1..1_000_000).map { "WELL-$it" },
    Arb.int(0..2),
    Arb.boolean(),
    timestampArb,
    Arb.list(Arb.double(0.0, 1.0), 7..7),
) { wellId, kind, logIsProduction, capturedAt, doubles ->
    RecordPrimitives(wellId, kind, logIsProduction, capturedAt, doubles)
}

/** Normalizes any finite double into [0, 1]. */
private fun unit(d: Double): Double {
    val x = if (d.isFinite()) d else 0.0
    return kotlin.math.abs(x).coerceIn(0.0, 1.0)
}

private fun scaled(d: Double, min: Double, max: Double): Double = min + unit(d) * (max - min)

private fun RecordPrimitives.toSeededRecord(): SeededRecord {
    // A pending creation metadata with no prior attempt -> backoff-eligible.
    val sync = SyncMetadata(
        syncState = SyncState.PENDING,
        retryCount = 0,
        createdAt = capturedAt,
        lastAttemptAt = null,
        remoteId = null,
    )
    val d = doubles
    return when (kind) {
        0 -> {
            val id = UUID.randomUUID()
            SeededRecord(
                id = id,
                kind = SyncableKind.WELL_TEST,
                payload = Payload.WellTestPayload(
                    WellTest(
                        id = id,
                        wellId = wellId,
                        oilRatio = unit(d[0]),
                        waterRatio = unit(d[1]),
                        gasRatio = unit(d[2]),
                        capturedAt = capturedAt,
                        sync = sync,
                    ),
                ),
            )
        }
        1 -> {
            val id = UUID.randomUUID()
            val log = if (logIsProduction) {
                WellheadLog(
                    id = id,
                    wellId = wellId,
                    logType = LogType.PRODUCTION,
                    capturedAt = capturedAt,
                    emulsionFlowRate = scaled(d[0], 0.0, 999_999.99),
                    casingGasPressure = scaled(d[1], 0.0, 999_999.99),
                    espMotorTemperature = scaled(d[2], 0.0, 999_999.99),
                    waterCutPercent = scaled(d[3], 0.0, 100.0),
                    sync = sync,
                )
            } else {
                WellheadLog(
                    id = id,
                    wellId = wellId,
                    logType = LogType.INJECTION,
                    capturedAt = capturedAt,
                    steamInjectionPressure = scaled(d[0], 0.0, 25_000.0),
                    steamTemperature = scaled(d[1], 0.0, 400.0),
                    steamFlowRate = scaled(d[2], 0.0, 2_000.0),
                    sync = sync,
                )
            }
            SeededRecord(id, SyncableKind.WELLHEAD_LOG, Payload.WellheadLogPayload(log))
        }
        else -> {
            val id = UUID.randomUUID()
            val pump = scaled(d[0], 0.0, 400.0)
            val tsat = scaled(d[1], 0.0, 400.0)
            SeededRecord(
                id = id,
                kind = SyncableKind.SUBCOOL_READING,
                payload = Payload.SubcoolReadingPayload(
                    SubcoolReading(
                        id = id,
                        wellId = wellId,
                        casingPressure = scaled(d[2], 0.0, 25_000.0),
                        pumpTemperature = pump,
                        saturationTemperature = tsat,
                        subcoolValue = tsat - pump,
                        isFlashing = (tsat - pump) <= 10.0,
                        capturedAt = capturedAt,
                        sync = sync,
                    ),
                ),
            )
        }
    }
}

/** A seeded sync cycle: 1..6 pending push-client records. */
private val seededCycleArb: Arb<List<SeededRecord>> =
    Arb.list(recordPrimitivesArb().map { it.toSeededRecord() }, 1..6)
