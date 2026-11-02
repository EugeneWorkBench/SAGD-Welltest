/*
 * Property 5: Acknowledgment yields synchronized state (Android) — task 16.3.
 *
 * Validates: Requirements 2.3
 *
 * Requirement 2.3 says that when the Cloud_Backend acknowledges a pushed record
 * (a 2xx response carrying a server-assigned remote id), the App must mark that
 * record's sync state SYNCHRONIZED and persist the returned remote id against
 * it.
 *
 * Design "Property 5: Acknowledgment yields synchronized state":
 *
 *     For any set of pending records, running a sync cycle in which every push
 *     is acknowledged leaves each of those records in the SYNCHRONIZED state
 *     with the acknowledgment's remoteId stored.
 *
 * This property drives the real engine end to end against the real repository:
 *
 *   * [SyncCycleRunner] is the actual production runner — the platform-agnostic
 *     heart of the Android sync engine. Nothing about the acknowledgment path
 *     is stubbed on the runner side.
 *   * [SyncRepositoryImpl] is the real repository (its nextPendingBatch
 *     oldest-first ordering and its markSynchronized state transition run for
 *     real), wired over map-backed fake DAOs. An in-memory Room database cannot
 *     be opened in a pure-JVM unit test (Room's in-memory builder needs an
 *     Android Context and the native SQLite library, neither available without
 *     instrumentation / Robolectric, and there is no JDK/Gradle here). So the
 *     persistence substrate is faked at the DAO seam — the DAOs are interfaces —
 *     while every repository behaviour the property depends on runs unchanged.
 *   * The push client is a stub that returns Acknowledged(remoteId) for every
 *     record, with the remoteId derived from the pushed record's id so the test
 *     asserts the *specific* id each record received was the one persisted — not
 *     merely that *some* id was stored.
 *   * A controllable clock ([FixedSyncClock]) supplies a deterministic "now";
 *     seeded records have lastAttemptAt == null / retryCount == 0, so every
 *     record is immediately backoff-eligible and pushed in the one cycle.
 *
 * Only well-test, wellhead-log, and subcool-reading records are seeded: those
 * are the kinds the runner routes through [SyncPushClient] (Req 2.3's push
 * path). Escalations take the separate multipart blob-upload path, which is a
 * different property; the escalation seams here are inert and never exercised.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 5: Acknowledgment yields
 * synchronized state`, mirroring the iOS
 * AcknowledgmentYieldsSynchronizedStatePropertyTests.
 */
package com.sagd.welltest.sync

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
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AcknowledgmentYieldsSynchronizedStatePropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 5: Acknowledgment yields synchronized state" {
        checkAll(pbtConfig(), seededCycleArb) { seed ->
            // Each generated case gets its own isolated map-backed store.
            val store = FakeSyncStore()
            seedStore(seed, store)

            val repository = SyncRepositoryImpl(
                wellTestDao = store.wellTestDao,
                wellheadLogDao = store.wellheadLogDao,
                subcoolReadingDao = store.subcoolReadingDao,
                escalationDao = store.escalationDao,
                // Deterministic wall clock — unused on the acknowledgment path
                // (only recordFailure stamps lastAttemptAt), but pinned anyway.
                clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
            )

            // Acknowledge every push, echoing a remote id derived from the
            // record id so we can verify per-record association, not just
            // presence.
            val pushClient = AcknowledgingPushClient(::remoteIdFor)

            val runner = SyncCycleRunner(
                syncRepository = repository,
                pushClient = pushClient,
                blobUploadClient = UnusedBlobUploadClient,
                blobSource = UnusedEscalationBlobSource,
                backoffPolicy = ExponentialBackoffPolicy(),
                clock = FixedSyncClock(FIXED_NOW),
                // A batch size comfortably above the seeded count so every
                // record is pushed in the single cycle.
                batchSize = maxOf(seed.size, 1),
            )

            val outcome = runner.runSyncCycle()

            // Every seeded record was acknowledged this cycle.
            withClue("outcome=$outcome") {
                outcome.acknowledged shouldBe seed.size
                outcome.failed shouldBe 0
                outcome.skipped shouldBe 0
            }

            // Every seeded record must now be SYNCHRONIZED with its remote id.
            for (record in seed) {
                val meta = store.syncMetadataOf(record.id, record.kind)
                withClue("record ${record.id} (${record.kind}) meta=$meta") {
                    // The record must still exist (not vanished) after the cycle.
                    (meta != null) shouldBe true
                    // ...be SYNCHRONIZED (Req 2.3)...
                    meta?.syncState shouldBe SyncState.SYNCHRONIZED
                    // ...and carry the exact remoteId the stub returned for it.
                    meta?.remoteId shouldBe remoteIdFor(record.id)
                }
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Deterministic time / remote-id mapping
// ---------------------------------------------------------------------------

/** A fixed instant used by both clocks so timestamps are deterministic. */
private val FIXED_NOW: Instant = Instant.ofEpochSecond(1_700_000_000L)

/**
 * The remote id the stub returns for a given record id. Deterministic and
 * unique per record so the test can assert the exact id round-trips.
 */
private fun remoteIdFor(id: UUID): String = "remote-$id"

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
// Map-backed fake DAOs (the persistence substrate standing in for Room)
// ---------------------------------------------------------------------------
//
// Only the methods SyncRepositoryImpl calls on the acknowledgment path are
// meaningful: nextPending (oldest-first PENDING rows), getById, and update.
// Everything else satisfies the interface and is unused by this property. The
// escalation DAO is empty — escalations are never seeded here.

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
    throw UnsupportedOperationException("not used by the acknowledgment property")

// ---------------------------------------------------------------------------
// Test doubles: push client, blob seams, clock
// ---------------------------------------------------------------------------

/** Acknowledges every record with a remote id derived from its id. */
private class AcknowledgingPushClient(
    private val remoteIdFor: (UUID) -> String,
) : SyncPushClient {
    override suspend fun push(record: SyncableRecord): SyncPushResult =
        SyncPushResult.Acknowledged(remoteIdFor(record.recordId))
}

/**
 * Escalation seams that must never be reached: this property seeds only records
 * routed through [SyncPushClient]. Reaching either would mean the runner
 * misrouted a non-escalation record.
 */
private object UnusedBlobUploadClient : BlobUploadClient {
    override suspend fun upload(
        escalation: com.sagd.welltest.domain.Escalation,
        blobPath: String,
    ): SyncPushResult =
        throw AssertionError("blob upload client reached for a non-escalation property")
}

private object UnusedEscalationBlobSource : EscalationBlobSource {
    override suspend fun escalationWithBlob(id: UUID): EscalationBlob? =
        throw AssertionError("escalation blob source reached for a non-escalation property")
}

/** A controllable clock pinned at a fixed instant; sleep returns at once. */
private class FixedSyncClock(private val instant: Instant) : SyncClock {
    override fun now(): Instant = instant
    override suspend fun sleep(seconds: Double) = Unit
}

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
// attempt, so all are backoff-eligible in the single cycle. One flat Arb draws
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
