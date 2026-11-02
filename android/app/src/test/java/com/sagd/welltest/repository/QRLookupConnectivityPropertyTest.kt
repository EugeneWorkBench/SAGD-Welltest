/*
 * Property 20: QR lookup independent of connectivity (Android) — task 13.2.
 *
 * Validates: Requirements 6.5
 *
 * Design "Property 20: QR lookup independent of connectivity":
 *
 *     For any Well present in the Local_Store, the lookup returns the same
 *     Found result (same well, same ordered tests, same open tasks) regardless
 *     of whether network connectivity is available.
 *
 * This mirrors the iOS counterpart
 * (QRLookupIndependentOfConnectivityPropertyTests.swift, task 10.2): it
 * exercises the real LocalWellLookupUseCase over the real WellRepositoryImpl
 * read path, driving two use-case instances that differ only in the injected
 * StaticConnectivityProvider.isOnline flag.
 *
 *   * A subject Well is seeded first (its tests carry a non-null wellId foreign
 *     key, Entities.kt).
 *   * Its well tests are written through the store's transactional save path
 *     (LogRepositoryImpl.saveWellTest) — the same save the UI uses — and its
 *     maintenance tasks are written directly (pulled reference data with no
 *     repository), both into the one Local_Store.
 *   * Two LocalWellLookupUseCase instances are built over the SAME store, one
 *     with StaticConnectivityProvider(isOnline = true) and one with
 *     StaticConnectivityProvider(isOnline = false). Because the well exists
 *     locally, lookup must return the identical Found result from both — same
 *     well, same ordered tests, same open tasks (Req 6.5). The use case never
 *     consults connectivity on a hit (WellLookup.kt).
 *
 * The miss branch is asserted in the same property so the connectivity split is
 * covered: for a well id that is NOT in the store, the online lookup must return
 * NotFound and the offline lookup must return UnavailableOffline.
 *
 * -- Why map-backed fake DAOs rather than a live Room DB --
 *
 * On iOS the mirror opens a real in-memory GRDB AppDatabase, because GRDB runs
 * directly on the host as a plain library. Room cannot do the equivalent in a
 * pure-JVM unit test: opening a Room database (even in-memory) needs an Android
 * Context and the native SQLite runtime, available only under Robolectric or
 * on-device instrumentation, neither present here. As task 13.2 directs (like
 * the 6.5 ordering test, QRLookupOrderingAndOpenTaskFilteringPropertyTest), this
 * test uses map-backed fake DAOs that implement the production DAO interfaces
 * and reproduce the exact query shapes the real Room queries pin down (Daos.kt):
 *
 *   * WellTestDao.getForWell: ORDER BY capturedAt DESC (most-recent-first).
 *   * MaintenanceTaskDao.getOpenForWell: WHERE wellId = :wellId AND status = OPEN.
 *
 * The real LocalWellLookupUseCase, WellRepositoryImpl and LogRepositoryImpl run
 * unchanged over these fakes — the only substitution is the persistence
 * substrate (a map rather than SQLite), which is irrelevant to the
 * connectivity-independence Req 6.5 is about.
 *
 * -- Ordering determinism --
 *
 * getWellTests orders by capturedAt descending. The generator draws DISTINCT
 * capturedAt values for the subject well's tests so the most-recent-first order
 * is total and unambiguous; because equality here compares two reads of the
 * SAME store, the order would match even with ties, but distinct timestamps make
 * the invariant self-evident.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 20: QR lookup independent of connectivity`.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.ConnectivityProviding
import com.sagd.welltest.domain.LocalWellLookupUseCase
import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.StaticConnectivityProvider
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellLookupResult
import com.sagd.welltest.domain.WellLookupUseCase
import com.sagd.welltest.domain.WellRepository
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.MaintenanceTaskDao
import com.sagd.welltest.localstore.MaintenanceTaskEntity
import com.sagd.welltest.localstore.WellDao
import com.sagd.welltest.localstore.WellEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
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

class QRLookupConnectivityPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 20: QR lookup independent of connectivity" {
        checkAll(pbtConfig(), connectivityCaseArb) { case ->
            val store = ConnectivityLookupStore()

            // Seed the subject well (parent FK row for its tests).
            store.wellDao.upsert(case.well.toEntity())

            // Seed the subject well's tests through the store's write path —
            // the same transactional save the UI uses.
            case.tests.forEach { test ->
                withClue("seeding a subject well test must succeed") {
                    (store.logs.saveWellTest(test) == SaveResult.Success).shouldBeTrue()
                }
            }

            // Seed the subject well's maintenance tasks directly (no repository;
            // tasks are pulled reference data).
            case.tasks.forEach { store.maintenanceTaskDao.upsert(it.toEntity()) }

            // Two use cases over the SAME store, differing ONLY in connectivity.
            val onlineLookup: WellLookupUseCase =
                LocalWellLookupUseCase(store.wells, online(isOnline = true))
            val offlineLookup: WellLookupUseCase =
                LocalWellLookupUseCase(store.wells, online(isOnline = false))

            // --- 1. Present well: identical Found result regardless of connectivity. ---
            val onlineResult = onlineLookup.lookup(case.well.id)
            val offlineResult = offlineLookup.lookup(case.well.id)

            val onlineFound = onlineResult.shouldBeInstanceOf<WellLookupResult.Found>()
            val offlineFound = offlineResult.shouldBeInstanceOf<WellLookupResult.Found>()

            // a) Byte-for-byte equal: same well, same ordered tests, same open
            //    tasks. WellLookupResult.Found is a data class, so this compares
            //    every field structurally.
            withClue("present well must yield the same Found result online vs offline") {
                onlineFound shouldBe offlineFound
            }

            // b) Sanity: the Found payload reflects the seeded data — tests
            //    most-recent-first and exactly the open tasks (independently of
            //    the connectivity flag that produced it).
            val expectedOrderedTestIds =
                case.tests.sortedByDescending { it.capturedAt }.map { it.id }
            val expectedOpenTaskIds =
                case.tasks.filter { it.status == MaintenanceStatus.OPEN }.map { it.id }.toSet()

            withClue("Found well id must be the subject well") {
                onlineFound.well.id shouldBe case.well.id
            }
            withClue("Found tests must be most-recent-first") {
                onlineFound.wellTests.map { it.id } shouldBe expectedOrderedTestIds
            }
            withClue("Found open tasks must be exactly the subject well's open tasks") {
                onlineFound.openMaintenanceTasks.map { it.id }.toSet() shouldBe expectedOpenTaskIds
            }
            withClue("every Found task must be OPEN") {
                onlineFound.openMaintenanceTasks
                    .all { it.status == MaintenanceStatus.OPEN }.shouldBeTrue()
            }

            // --- 2. Absent well: online -> NotFound, offline -> UnavailableOffline. ---
            val onlineMiss = onlineLookup.lookup(case.missingWellId)
            val offlineMiss = offlineLookup.lookup(case.missingWellId)

            withClue("online lookup of an absent well must be NotFound: $onlineMiss") {
                onlineMiss shouldBe WellLookupResult.NotFound
            }
            withClue("offline lookup of an absent well must be UnavailableOffline: $offlineMiss") {
                offlineMiss shouldBe WellLookupResult.UnavailableOffline
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Connectivity provider
// ---------------------------------------------------------------------------

/** Builds the production [StaticConnectivityProvider] for a fixed online state. */
private fun online(isOnline: Boolean): ConnectivityProviding =
    StaticConnectivityProvider(isOnline = isOnline)

// ---------------------------------------------------------------------------
// Per-case store: the REAL repositories layered over map-backed fake DAOs
// ---------------------------------------------------------------------------

/**
 * Pin the save clock so seeding is deterministic. getWellTests orders by
 * capturedAt (an operator-captured field), never by the store-assigned
 * createdAt, so a fixed save clock does not affect the asserted order; it only
 * keeps seeding side-effect free.
 */
private val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC)

/** A StorageGuard that always reports room; this property is about reads, not storage. */
private object AlwaysFreeStorageGuard : StorageGuard {
    override fun hasFreeSpace(requiredBytes: Long): Boolean = true
}

/** A [TransactionRunner] that commits on normal return (every seed here succeeds). */
private class CommitOnReturnTransactionRunner : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
}

/**
 * One isolated in-memory store with the REAL WellRepositoryImpl (the read path
 * the lookup use case drives) and LogRepositoryImpl (the seeding write path)
 * over map-backed fake DAOs whose query shapes reproduce the production Room
 * queries exactly.
 */
private class ConnectivityLookupStore {
    val wellDao = MapWellDao()
    val wellTestDao = MapWellTestDao()
    val wellheadLogDao = MapWellheadLogDao()
    val escalationDao = MapEscalationDao()
    val maintenanceTaskDao = MapMaintenanceTaskDao()

    val wells: WellRepository =
        WellRepositoryImpl(wellDao, wellTestDao, maintenanceTaskDao)
    val logs: LogRepository =
        LogRepositoryImpl(
            CommitOnReturnTransactionRunner(),
            wellheadLogDao,
            wellTestDao,
            AlwaysFreeStorageGuard,
            FIXED_CLOCK,
        )
}

// ---------------------------------------------------------------------------
// Map-backed fake DAOs (no network, pure in-memory local store)
// ---------------------------------------------------------------------------

private class MapWellDao : WellDao {
    private val rows = linkedMapOf<String, WellEntity>()
    override suspend fun upsert(well: WellEntity) { rows[well.id] = well }
    override suspend fun upsertAll(wells: List<WellEntity>) = wells.forEach { rows[it.id] = it }
    override suspend fun getWell(id: String): WellEntity? = rows[id]
    override fun observeWell(id: String): Flow<WellEntity?> = flowOf(rows[id])
    override fun observeAll(): Flow<List<WellEntity>> = flowOf(rows.values.sortedBy { it.name })
    override suspend fun deleteAll() = rows.clear()
}

private class MapWellTestDao : WellTestDao {
    private val rows = linkedMapOf<UUID, WellTestEntity>()
    override suspend fun upsert(test: WellTestEntity) { rows[test.id] = test }
    override suspend fun getById(id: UUID): WellTestEntity? = rows[id]

    // Reproduces: ORDER BY capturedAt DESC (Req 6.2 most-recent-first).
    override suspend fun getForWell(wellId: String): List<WellTestEntity> =
        rows.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        rows.values.filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun update(test: WellTestEntity) { rows[test.id] = test }
}

private class MapWellheadLogDao : WellheadLogDao {
    private val rows = linkedMapOf<UUID, WellheadLogEntity>()
    override suspend fun upsert(log: WellheadLogEntity) { rows[log.id] = log }
    override suspend fun getById(id: UUID): WellheadLogEntity? = rows[id]
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> =
        rows.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        rows.values.filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun update(log: WellheadLogEntity) { rows[log.id] = log }
}

private class MapEscalationDao : EscalationDao {
    private val rows = linkedMapOf<UUID, EscalationEntity>()
    override suspend fun upsert(escalation: EscalationEntity) { rows[escalation.id] = escalation }
    override suspend fun getById(id: UUID): EscalationEntity? = rows[id]
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        rows.values.filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun allPhotoRefs(): List<String> = rows.values.map { it.photoRef }
    override suspend fun update(escalation: EscalationEntity) { rows[escalation.id] = escalation }
    override suspend fun delete(escalation: EscalationEntity) { rows.remove(escalation.id) }
}

private class MapMaintenanceTaskDao : MaintenanceTaskDao {
    private val rows = linkedMapOf<UUID, MaintenanceTaskEntity>()
    override suspend fun upsert(task: MaintenanceTaskEntity) { rows[task.id] = task }
    override suspend fun upsertAll(tasks: List<MaintenanceTaskEntity>) =
        tasks.forEach { rows[it.id] = it }

    // Reproduces: WHERE wellId = :wellId AND status = OPEN ORDER BY createdAt DESC (Req 6.2).
    override suspend fun getOpenForWell(
        wellId: String,
        open: MaintenanceStatus,
    ): List<MaintenanceTaskEntity> =
        rows.values.filter { it.wellId == wellId && it.status == open }
            .sortedByDescending { it.createdAt }
    override suspend fun deleteForWell(wellId: String) {
        rows.values.filter { it.wellId == wellId }.map { it.id }.forEach { rows.remove(it) }
    }
}

// ---------------------------------------------------------------------------
// Generated case
// ---------------------------------------------------------------------------

/**
 * A subject well present in the store (with an arbitrary set of tests at DISTINCT
 * capturedAt and a mix of open/closed tasks) plus a disjoint well id guaranteed
 * NOT to be in the store (the miss branch).
 */
private data class ConnectivityLookupCase(
    val well: Well,
    val tests: List<WellTest>,
    val tasks: List<MaintenanceTask>,
    /** A well id that is never seeded, exercising the miss branch. */
    val missingWellId: String,
)

// 1-second-precision capture timestamps, the captured-timestamp contract.
private val secondsArb: Arb<Long> = Arb.long(0L, 2_000_000_000L)

private fun syncMeta(createdAt: Instant) =
    SyncMetadata(syncState = SyncState.PENDING, retryCount = 0, createdAt = createdAt)

private fun makeWell(id: String, type: WellType, updatedAtSeconds: Long): Well =
    Well(
        id = id,
        name = "Well $id",
        type = type,
        padId = null,
        updatedAt = Instant.ofEpochSecond(updatedAtSeconds),
    )

private fun makeWellTest(wellId: String, capturedSecond: Long): WellTest {
    val at = Instant.ofEpochSecond(capturedSecond)
    return WellTest(
        id = UUID.randomUUID(),
        wellId = wellId,
        oilRatio = 0.6,
        waterRatio = 0.3,
        gasRatio = 0.1,
        capturedAt = at,
        sync = syncMeta(at),
    )
}

private fun makeTask(wellId: String, status: MaintenanceStatus, createdSecond: Long): MaintenanceTask =
    MaintenanceTask(
        id = UUID.randomUUID(),
        wellId = wellId,
        description = "Task ${UUID.randomUUID()}",
        status = status,
        createdAt = Instant.ofEpochSecond(createdSecond),
    )

/**
 * Draws a subject well with 0..8 tests at DISTINCT capturedAt seconds (so the
 * most-recent-first order is total) and 0..8 tasks each independently
 * open/closed, plus a disjoint "missing" id (different prefix and numeric range)
 * that can never collide with the seeded subject — so the online-NotFound vs
 * offline-UnavailableOffline miss branch is genuinely exercised.
 */
private val connectivityCaseArb: Arb<ConnectivityLookupCase> = Arb.bind(
    Arb.int(1, 500_000).map { "WELL-$it" },
    Arb.int(500_001, 1_000_000).map { "MISSING-$it" },
    Arb.enum<WellType>(),
    // Up to 8 candidate capture seconds for the subject well's tests; deduped
    // below so capturedAt is distinct and the ordering is unambiguous.
    Arb.list(secondsArb, 0..8),
    // Open/closed flags for 0..8 subject tasks.
    Arb.list(Arb.boolean(), 0..8),
) { subjectId, missingId, wellType, testSeconds, taskFlags ->
    val tests = testSeconds.distinct().map { second -> makeWellTest(subjectId, second) }
    val tasks = taskFlags.map { isOpen ->
        makeTask(
            subjectId,
            if (isOpen) MaintenanceStatus.OPEN else MaintenanceStatus.CLOSED,
            1_000_000L,
        )
    }

    ConnectivityLookupCase(
        well = makeWell(subjectId, wellType, 1_000_000L),
        tests = tests,
        tasks = tasks,
        missingWellId = missingId,
    )
}
