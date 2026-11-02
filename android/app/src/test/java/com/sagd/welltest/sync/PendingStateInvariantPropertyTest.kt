/*
 * Property 8: Pending-state invariant (Android) — task 16.6.
 *
 * Validates: Requirements 2.6
 *
 * Requirement 2.6: WHILE a record has been created locally and is not yet
 * acknowledged and has not reached the failed state, THE App SHALL display that
 * record's synchronization state as pending.
 *
 * Design "Property 8: Pending-state invariant":
 *
 *     For any captured record, from the moment it is saved until it is either
 *     acknowledged or reaches the failed state, its displayed sync state is
 *     PENDING.
 *
 * "Displayed sync state" is the `syncState` column the UI observes via
 * SyncRepository.observeSyncState; this test reads the same column straight from
 * the store so the assertion is about the authoritative persisted value.
 *
 * For any generated set of captured records, the property asserts:
 *
 *   1. Immediately after a record is saved through its REAL repository
 *      (LogRepositoryImpl.saveWellTest / saveWellheadLog,
 *      EscalationRepositoryImpl.saveEscalation), that record's persisted
 *      `syncState` is exactly PENDING — just created locally, neither
 *      acknowledged nor failed.
 *
 *   2. After each of k failing push cycles (with 1 <= k <= 4, so the
 *      consecutive-failure count stays strictly below the 5-failure limit
 *      [SYNC_MAX_RETRY_COUNT] at which a record flips to FAILED), every record
 *      is STILL PENDING. A failing push is "not acknowledged" and, below the
 *      retry limit, "not failed" — so Req 2.6 requires PENDING to hold.
 *
 * --- How the failing push is driven (and why it is faithful) ---
 *
 * The REAL [SyncCycleRunner] is constructed over the REAL [SyncRepositoryImpl]
 * and a REAL [RoomEscalationBlobSource] (reading the SAME DAOs + blob store the
 * records were saved into), with its push client and blob-upload client replaced
 * by stubs that always return [SyncPushResult.RetryableFailure]. That is exactly
 * the engine's genuine failure branch: the runner routes a failure to
 * [SyncRepositoryImpl.recordFailure], which increments retryCount, stamps
 * lastAttemptAt, and keeps the record PENDING until the 5th failure. Nothing
 * about the state machine is mocked — only the network boundary (which the
 * design abstracts behind [SyncPushClient] / [BlobUploadClient]) fails.
 *
 * Two seams are driven deterministically so each cycle actually pushes every
 * record rather than skipping it on backoff:
 *   * A single advancing [MutableClock] backs BOTH the runner's [SyncClock]
 *     (backoff eligibility) and the repository's [java.time.Clock] (which stamps
 *     lastAttemptAt on failure), so the two live on one timeline. Advancing it
 *     far past the largest possible backoff window before every cycle makes
 *     every pending record eligible and pushed again each cycle.
 *   * A batch size above the seeded count so all records are pulled in one
 *     cycle.
 *
 * --- Why the real repositories over map-backed fake DAOs ---
 *
 * The task directs saving via the real repositories (LogRepositoryImpl /
 * EscalationRepositoryImpl) over map-backed DAOs + a fake [TransactionRunner] +
 * a real [BlobStore] temp dir. An in-memory Room database cannot be opened in a
 * pure-JVM unit test (Room's in-memory builder needs an Android Context and the
 * native SQLite runtime, available only under instrumentation / Robolectric, and
 * there is no JDK/Gradle here). So the persistence substrate is faked at the two
 * real seams the repositories expose: the [TransactionRunner] (a commit-on-
 * success / rollback-on-throw runner over the in-memory store) and the DAO
 * interfaces (a transaction-aware in-memory store reproducing the exact query
 * shapes Daos.kt pins down — `nextPending` returns PENDING rows oldest-first by
 * createdAt, `getById`/`update`/`upsert` key on the primary id). The [BlobStore]
 * is plain java.io, so the test uses the REAL BlobStore over a temp directory.
 *
 * Every behaviour the property depends on runs unchanged: the repositories'
 * normalize-to-PENDING-on-save, the blob-first escalation ordering, the real
 * SyncRepositoryImpl state machine and oldest-first batch, the real
 * SyncCycleRunner cycle, and the real ExponentialBackoffPolicy.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 8: Pending-state invariant`,
 * mirroring the iOS PendingStateInvariantPropertyTests.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.localstore.BlobStore
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
import com.sagd.welltest.repository.EscalationRepositoryImpl
import com.sagd.welltest.repository.LogRepositoryImpl
import com.sagd.welltest.repository.RoomEscalationBlobSource
import com.sagd.welltest.repository.StorageGuard
import com.sagd.welltest.repository.SyncRepositoryImpl
import com.sagd.welltest.repository.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class PendingStateInvariantPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 8: Pending-state invariant" {
        checkAll(pbtConfig(), scenarioArb) { scenario ->
            // Each generated scenario gets its own isolated store + temp blob dir.
            val store = FakeStore()
            val blobDir = Files.createTempDirectory("sagd-prop8-blobs").toFile()
            try {
                pendingStateInvariantHolds(scenario, store, blobDir)
            } finally {
                blobDir.deleteRecursively()
            }
        }
    }
})

// ---------------------------------------------------------------------------
// The property check for one generated scenario
// ---------------------------------------------------------------------------

/**
 * Saves the generated records through the real repositories, asserts each is
 * immediately PENDING, then runs [Scenario.failingCycles] failing push cycles
 * and asserts every record remains PENDING after each cycle.
 */
private suspend fun pendingStateInvariantHolds(
    scenario: Scenario,
    store: FakeStore,
    blobDir: File,
) {
    // A single advancing clock shared by the runner (backoff eligibility) and
    // the repository (stamps lastAttemptAt on failure) so both live on one
    // timeline — advancing it past the backoff window makes every record
    // eligible each cycle.
    val clock = MutableClock(start = Instant.ofEpochSecond(1_700_000_000L))

    // A real BlobStore over the temp dir; used by the escalation save path and
    // the sync engine's escalation blob source alike.
    val blobStore = BlobStore(blobDir)
    val runner = FakeTransactionRunner(store)

    // --- Real repositories over the fake DAOs + fake transaction runner. ---
    val logRepo = LogRepositoryImpl(
        transactionRunner = runner,
        wellheadLogDao = store.wellheadLogDao,
        wellTestDao = store.wellTestDao,
        storageGuard = AmpleStorageGuard,
        clock = clock.javaClock(),
    )
    val escalationRepo = EscalationRepositoryImpl(
        transactionRunner = runner,
        escalationDao = store.escalationDao,
        blobStore = blobStore,
        storageGuard = AmpleStorageGuard,
        clock = clock.javaClock(),
    )

    // --- Save each record through its repository (Req 2.6 "created locally"). ---
    val savedTest = logRepo.saveWellTest(scenario.wellTest)
    val savedLog = logRepo.saveWellheadLog(scenario.wellheadLog)
    val savedEsc = escalationRepo.saveEscalation(scenario.escalation, scenario.photo)

    withClue("a record did not save successfully: test=$savedTest, log=$savedLog, esc=$savedEsc") {
        savedTest shouldBe SaveResult.Success
        savedLog shouldBe SaveResult.Success
        savedEsc shouldBe SaveResult.Success
    }

    val ids = listOf(scenario.wellTest.id, scenario.wellheadLog.id, scenario.escalation.id)

    // Invariant at creation time: every freshly saved record is PENDING.
    withClue("a record was not PENDING immediately after save: ${store.statesByKind(scenario)}") {
        for (id in ids) {
            store.syncState(id) shouldBe SyncState.PENDING
        }
    }

    // --- Real sync engine over the SAME store, with a failing network boundary. ---
    val syncRepository = SyncRepositoryImpl(
        wellTestDao = store.wellTestDao,
        wellheadLogDao = store.wellheadLogDao,
        subcoolReadingDao = store.subcoolReadingDao,
        escalationDao = store.escalationDao,
        clock = clock.javaClock(),
    )
    val cycleRunner = SyncCycleRunner(
        syncRepository = syncRepository,
        pushClient = AlwaysRetryableFailurePushClient,
        blobUploadClient = AlwaysRetryableFailureBlobUploadClient,
        blobSource = RoomEscalationBlobSource(store.escalationDao, blobStore),
        backoffPolicy = ExponentialBackoffPolicy(),
        clock = clock,
        // A batch size above the seeded count so every record is pushed each cycle.
        batchSize = ids.size + 1,
    )

    // --- Run k failing push cycles; k < 5 so no record reaches FAILED. ---
    // Each cycle: advance the clock past any backoff window, run one cycle whose
    // every push fails (retryable), then assert every record is still PENDING.
    for (cycle in 1..scenario.failingCycles) {
        clock.advancePastBackoff()
        cycleRunner.runSyncCycle()

        withClue(
            "Property 8 violated after failing cycle $cycle of ${scenario.failingCycles}: " +
                "expected all PENDING, got ${store.statesByKind(scenario)}",
        ) {
            for (id in ids) {
                store.syncState(id) shouldBe SyncState.PENDING
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Generated scenario model
// ---------------------------------------------------------------------------

/**
 * One generated scenario: one record of each syncable save path (well test,
 * wellhead log, escalation) for a single well, the photo bytes for the
 * escalation, and the number of failing cycles to run (1..4, strictly below the
 * 5-failure FAILED boundary).
 */
private class Scenario(
    val wellId: String,
    val capturedAt: Instant,
    val oilRatio: Double,
    val waterRatio: Double,
    val gasRatio: Double,
    val logIsProduction: Boolean,
    val logDoubles: List<Double>,
    val priority: EscalationPriority,
    val latitude: Double,
    val longitude: Double,
    val locationUnavailable: Boolean,
    val photo: ByteArray,
    val failingCycles: Int,
) {
    private fun pendingSync() = SyncMetadata(
        syncState = SyncState.PENDING,
        retryCount = 0,
        createdAt = capturedAt,
    )

    val wellTest = com.sagd.welltest.domain.WellTest(
        id = UUID.randomUUID(),
        wellId = wellId,
        oilRatio = oilRatio,
        waterRatio = waterRatio,
        gasRatio = gasRatio,
        capturedAt = capturedAt,
        sync = pendingSync(),
    )

    val wellheadLog = if (logIsProduction) {
        com.sagd.welltest.domain.WellheadLog(
            id = UUID.randomUUID(),
            wellId = wellId,
            logType = LogType.PRODUCTION,
            capturedAt = capturedAt,
            emulsionFlowRate = scaled(logDoubles[0], 0.0, 999_999.99),
            casingGasPressure = scaled(logDoubles[1], 0.0, 999_999.99),
            espMotorTemperature = scaled(logDoubles[2], 0.0, 999_999.99),
            waterCutPercent = scaled(logDoubles[3], 0.0, 100.0),
            sync = pendingSync(),
        )
    } else {
        com.sagd.welltest.domain.WellheadLog(
            id = UUID.randomUUID(),
            wellId = wellId,
            logType = LogType.INJECTION,
            capturedAt = capturedAt,
            steamInjectionPressure = scaled(logDoubles[0], 0.0, 25_000.0),
            steamTemperature = scaled(logDoubles[1], 0.0, 400.0),
            steamFlowRate = scaled(logDoubles[2], 0.0, 2_000.0),
            sync = pendingSync(),
        )
    }

    val escalation = Escalation(
        id = UUID.randomUUID(),
        wellId = wellId,
        priority = priority,
        photoRef = "placeholder",
        latitude = latitude,
        longitude = longitude,
        locationUnavailable = locationUnavailable,
        capturedAt = capturedAt,
        sync = pendingSync(),
    )
}

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------
//
// One flat generator draws every primitive a scenario needs and assembles the
// three records directly, so there is no nested Arb sampling. failingCycles is
// drawn from 1..(SYNC_MAX_RETRY_COUNT - 1) = 1..4 so no record ever reaches the
// FAILED boundary.

private val timestampArb: Arb<Instant> =
    Arb.long(1_600_000_000L, 1_800_000_000L).map { Instant.ofEpochSecond(it) }

private val photoArb: Arb<ByteArray> =
    Arb.list(Arb.byte(), 1..256).map { it.toByteArray() }

/** Normalizes any finite double into [0, 1] so it can be scaled into a field range. */
private fun unit(d: Double): Double {
    val x = if (d.isFinite()) d else 0.0
    return kotlin.math.abs(x).coerceIn(0.0, 1.0)
}

private fun scaled(d: Double, min: Double, max: Double): Double = min + unit(d) * (max - min)

/** Raw primitives for a scenario, bundled to stay within Arb.bind's arity. */
private class ScenarioPrimitives(
    val wellId: String,
    val capturedAt: Instant,
    val ratios: List<Double>,
    val logIsProduction: Boolean,
    val logDoubles: List<Double>,
    val priority: EscalationPriority,
    val geo: List<Double>,
    val locationUnavailable: Boolean,
    val photo: ByteArray,
    val failingCycles: Int,
)

private val scenarioArb: Arb<Scenario> = Arb.bind(
    Arb.int(1..1_000_000).map { "WELL-$it" },
    timestampArb,
    Arb.list(Arb.double(0.0, 1.0), 3..3),
    Arb.boolean(),
    Arb.list(Arb.double(0.0, 1.0), 4..4),
    Arb.element(EscalationPriority.entries.toList()),
    Arb.list(Arb.double(0.0, 1.0), 2..2),
    Arb.boolean(),
    photoArb,
    // 1..4 failing cycles: strictly below the 5-failure limit, so no record
    // ever transitions to FAILED.
    Arb.int(1..(SYNC_MAX_RETRY_COUNT - 1)),
) { wellId, capturedAt, ratios, logIsProduction, logDoubles, priority, geo, locationUnavailable, photo, cycles ->
    ScenarioPrimitives(
        wellId = wellId,
        capturedAt = capturedAt,
        ratios = ratios,
        logIsProduction = logIsProduction,
        logDoubles = logDoubles,
        priority = priority,
        geo = geo,
        locationUnavailable = locationUnavailable,
        photo = photo,
        failingCycles = cycles,
    )
}.map { p ->
    Scenario(
        wellId = p.wellId,
        capturedAt = p.capturedAt,
        oilRatio = unit(p.ratios[0]),
        waterRatio = unit(p.ratios[1]),
        gasRatio = unit(p.ratios[2]),
        logIsProduction = p.logIsProduction,
        logDoubles = p.logDoubles,
        priority = p.priority,
        latitude = scaled(p.geo[0], -90.0, 90.0),
        longitude = scaled(p.geo[1], -180.0, 180.0),
        locationUnavailable = p.locationUnavailable,
        photo = p.photo,
        failingCycles = p.failingCycles,
    )
}

// ---------------------------------------------------------------------------
// Test doubles: failing network boundary + controllable clock
// ---------------------------------------------------------------------------

/**
 * A push client whose every attempt is a transient (retryable) failure, driving
 * the engine's genuine failure branch: the record stays PENDING and retryCount
 * increments.
 */
private object AlwaysRetryableFailurePushClient : SyncPushClient {
    override suspend fun push(record: com.sagd.welltest.domain.SyncableRecord): SyncPushResult =
        SyncPushResult.RetryableFailure("forced failure for pending-state invariant test")
}

/** A blob-upload client that always fails the same way, so escalation pushes fail too. */
private object AlwaysRetryableFailureBlobUploadClient : BlobUploadClient {
    override suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult =
        SyncPushResult.RetryableFailure("forced failure for pending-state invariant test")
}

/**
 * A controllable clock whose instant only moves when the test advances it, used
 * BOTH as the engine's [SyncClock] (backoff "now") and, via [javaClock], as the
 * repository's [java.time.Clock] (stamps lastAttemptAt on failure). Sharing one
 * timeline is what lets [advancePastBackoff] reliably re-enable every pending
 * record before each cycle.
 */
private class MutableClock(start: Instant) : SyncClock {
    @Volatile
    private var current: Instant = start

    override fun now(): Instant = current

    override suspend fun sleep(seconds: Double) {
        // No real waiting in tests; backoff eligibility is driven by now().
    }

    /** Jump far past the largest possible backoff window so every record is eligible. */
    fun advancePastBackoff() {
        current = current.plusSeconds(10_000)
    }

    /** A java.time.Clock reading this same advancing instant (fixed ZoneOffset.UTC). */
    fun javaClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
    }
}

/** Always reports ample space so the pre-write check never short-circuits to StorageFull. */
private object AmpleStorageGuard : StorageGuard {
    override fun hasFreeSpace(requiredBytes: Long): Boolean = true
}

// ---------------------------------------------------------------------------
// In-memory, transaction-aware fake store + DAOs (the substrate standing in for Room)
// ---------------------------------------------------------------------------

/**
 * The persistence substrate standing in for Room. Holds one map per syncable
 * table and a transaction buffer so [FakeTransactionRunner] can begin / commit /
 * rollback with Room's semantics (writes inside a transaction are staged and
 * merged only on commit; a rollback drops them). The DAOs reproduce the exact
 * query shapes the repositories and the sync layer use:
 *   * `nextPending`: WHERE syncState = PENDING ORDER BY createdAt ASC LIMIT n.
 *   * `getById` / `update` / `upsert`: keyed on the primary id.
 */
private class FakeStore {
    // Committed state.
    private val wellTests = linkedMapOf<UUID, WellTestEntity>()
    private val wellheadLogs = linkedMapOf<UUID, WellheadLogEntity>()
    private val escalations = linkedMapOf<UUID, EscalationEntity>()

    // Active transaction staging (null when no transaction is open).
    private var txWellTests: MutableMap<UUID, WellTestEntity>? = null
    private var txWellheadLogs: MutableMap<UUID, WellheadLogEntity>? = null
    private var txEscalations: MutableMap<UUID, EscalationEntity>? = null

    val wellTestDao = FakeWellTestDao(this)
    val wellheadLogDao = FakeWellheadLogDao(this)
    val escalationDao = FakeEscalationDao(this)

    // No subcool readings are seeded by this property; the empty DAO satisfies
    // the SyncRepositoryImpl constructor and returns nothing.
    val subcoolReadingDao: SubcoolReadingDao = EmptySubcoolReadingDao

    fun begin() {
        txWellTests = linkedMapOf()
        txWellheadLogs = linkedMapOf()
        txEscalations = linkedMapOf()
    }

    fun commit() {
        txWellTests?.let { wellTests.putAll(it) }
        txWellheadLogs?.let { wellheadLogs.putAll(it) }
        txEscalations?.let { escalations.putAll(it) }
        clearTx()
    }

    fun rollback() = clearTx()

    private fun clearTx() {
        txWellTests = null
        txWellheadLogs = null
        txEscalations = null
    }

    // Writes go to the transaction buffer when one is open, else straight to
    // committed state (the sync state-machine updates run outside a transaction,
    // exactly like the production SyncRepositoryImpl which updates rows directly).
    fun putWellTest(e: WellTestEntity) { (txWellTests ?: wellTests)[e.id] = e }
    fun putWellheadLog(e: WellheadLogEntity) { (txWellheadLogs ?: wellheadLogs)[e.id] = e }
    fun putEscalation(e: EscalationEntity) { (txEscalations ?: escalations)[e.id] = e }

    fun wellTest(id: UUID): WellTestEntity? = (txWellTests?.get(id)) ?: wellTests[id]
    fun wellheadLog(id: UUID): WellheadLogEntity? = (txWellheadLogs?.get(id)) ?: wellheadLogs[id]
    fun escalation(id: UUID): EscalationEntity? = (txEscalations?.get(id)) ?: escalations[id]

    fun pendingWellTests(limit: Int): List<WellTestEntity> =
        wellTests.values
            .filter { it.sync.syncState == SyncState.PENDING }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    fun pendingWellheadLogs(limit: Int): List<WellheadLogEntity> =
        wellheadLogs.values
            .filter { it.sync.syncState == SyncState.PENDING }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    fun pendingEscalations(limit: Int): List<EscalationEntity> =
        escalations.values
            .filter { it.sync.syncState == SyncState.PENDING }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    /** The persisted `syncState` for [id] from whichever syncable table holds it. */
    fun syncState(id: UUID): SyncState? =
        wellTest(id)?.sync?.syncState
            ?: wellheadLog(id)?.sync?.syncState
            ?: escalation(id)?.sync?.syncState

    /** A labeled snapshot of each record's state, for readable failures. */
    fun statesByKind(scenario: Scenario): Map<String, SyncState?> = mapOf(
        "wellTest" to syncState(scenario.wellTest.id),
        "wellheadLog" to syncState(scenario.wellheadLog.id),
        "escalation" to syncState(scenario.escalation.id),
    )
}

/**
 * Transaction runner over a [FakeStore] honouring Room's contract: begin, run
 * the body, COMMIT on normal return, ROLLBACK (discarding writes) while
 * propagating the throwable on failure.
 */
private class FakeTransactionRunner(private val store: FakeStore) : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R {
        store.begin()
        return try {
            val result = block()
            store.commit()
            result
        } catch (t: Throwable) {
            store.rollback()
            throw t
        }
    }
}

private class FakeWellTestDao(private val store: FakeStore) : WellTestDao {
    override suspend fun upsert(test: WellTestEntity) = store.putWellTest(test)
    override suspend fun getById(id: UUID): WellTestEntity? = store.wellTest(id)
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        store.pendingWellTests(limit)
    override suspend fun update(test: WellTestEntity) = store.putWellTest(test)
    override suspend fun getForWell(wellId: String): List<WellTestEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(store.wellTest(id)?.sync?.syncState)
}

private class FakeWellheadLogDao(private val store: FakeStore) : WellheadLogDao {
    override suspend fun upsert(log: WellheadLogEntity) = store.putWellheadLog(log)
    override suspend fun getById(id: UUID): WellheadLogEntity? = store.wellheadLog(id)
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        store.pendingWellheadLogs(limit)
    override suspend fun update(log: WellheadLogEntity) = store.putWellheadLog(log)
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(store.wellheadLog(id)?.sync?.syncState)
}

private class FakeEscalationDao(private val store: FakeStore) : EscalationDao {
    override suspend fun upsert(escalation: EscalationEntity) = store.putEscalation(escalation)
    override suspend fun getById(id: UUID): EscalationEntity? = store.escalation(id)
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        store.pendingEscalations(limit)
    override suspend fun update(escalation: EscalationEntity) = store.putEscalation(escalation)
    override suspend fun delete(escalation: EscalationEntity) = unsupported()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(store.escalation(id)?.sync?.syncState)
    override suspend fun allPhotoRefs(): List<String> = emptyList()
}

/** Empty subcool-reading DAO: no readings are seeded by this property. */
private object EmptySubcoolReadingDao : SubcoolReadingDao {
    override suspend fun upsert(reading: SubcoolReadingEntity) = Unit
    override suspend fun getById(id: UUID): SubcoolReadingEntity? = null
    override suspend fun getForWell(wellId: String): List<SubcoolReadingEntity> = emptyList()
    override suspend fun nextPending(limit: Int, pending: SyncState): List<SubcoolReadingEntity> =
        emptyList()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
    override suspend fun update(reading: SubcoolReadingEntity) = Unit
}

private fun unsupported(): Nothing =
    throw UnsupportedOperationException("not used by the pending-state-invariant property")
