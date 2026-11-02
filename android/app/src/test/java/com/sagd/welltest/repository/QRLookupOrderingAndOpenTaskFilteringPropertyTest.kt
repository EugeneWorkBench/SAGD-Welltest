/*
 * Property 19: QR lookup ordering and open-task filtering (Android) — task 6.5.
 *
 * Validates: Requirements 6.2
 *
 * Design "Property 19: QR lookup ordering and open-task filtering":
 *
 *     For any Well in the Local_Store with an arbitrary set of well tests and
 *     maintenance tasks, a successful lookup returns the well's tests ordered
 *     from most recent to oldest and returns exactly those maintenance tasks
 *     whose status is open.
 *
 * This property exercises the real repository read path end to end, mirroring
 * the iOS counterpart (QRLookupOrderingAndOpenTaskFilteringPropertyTests.swift,
 * task 5.5):
 *
 *   * A subject Well is seeded first because wellTest and maintenanceTask carry
 *     a non-null wellId foreign key (Entities.kt).
 *   * Well tests are seeded through the store's write path
 *     (LogRepositoryImpl.saveWellTest) — the same transactional save the UI
 *     uses. Maintenance tasks have no repository (they are pulled reference
 *     data), so they are seeded directly through the DAO, still a write to the
 *     Local_Store.
 *   * A second, unrelated "other" well plus its own tests and an open task are
 *     also seeded so the query's wellId filter is exercised: tests and tasks
 *     belonging to another well must never leak into the subject well's
 *     results.
 *   * The assertions read back exclusively through WellRepositoryImpl
 *     (getWellTests / getOpenMaintenanceTasks) — the subject of Req 6.2.
 *
 * -- Why map-backed fake DAOs rather than a live Room DB --
 *
 * On iOS the mirror test opens a real in-memory GRDB AppDatabase and reads back
 * through the real GRDBWellRepository, because GRDB runs directly on the host
 * as a plain library. Room cannot do the equivalent in a pure-JVM unit test:
 * opening a Room database (even in-memory) requires an Android Context and the
 * native SQLite runtime, available only under Robolectric or on-device
 * instrumentation, neither of which is present here. As task 6.5 directs, this
 * test therefore uses map-backed fake DAOs (like the 6.3
 * CaptureRequiresNoNetworkPropertyTest fakes) that implement the production DAO
 * interfaces and reproduce the exact query shapes the real Room queries pin
 * down (Daos.kt):
 *
 *   * WellTestDao.getForWell: ORDER BY capturedAt DESC (most-recent-first).
 *   * MaintenanceTaskDao.getOpenForWell: WHERE wellId = :wellId AND status = OPEN.
 *
 * The real WellRepositoryImpl and LogRepositoryImpl run unchanged over these
 * fakes — the only substitution is the persistence substrate (a map rather than
 * SQLite), which is irrelevant to the ordering/filtering Req 6.2 is about.
 *
 * -- Ordering determinism --
 *
 * getForWell orders by capturedAt descending. The generator draws DISTINCT
 * capturedAt values for the subject well's tests so the most-recent-first order
 * is total and unambiguous; the expected result is then simply those tests
 * sorted by capturedAt descending.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 19: QR lookup ordering and open-task filtering`.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.Well
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

class QRLookupOrderingAndOpenTaskFilteringPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 19: QR lookup ordering and open-task filtering" {
        checkAll(pbtConfig(), lookupCaseArb) { case ->
            val store = LookupStore()

            // Seed the subject well (parent FK row for its tests and tasks).
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

            // Seed an unrelated well with its own tests and an open task so the
            // wellId filter is genuinely exercised (its records must not appear).
            store.wellDao.upsert(case.otherWell.toEntity())
            case.otherTests.forEach { store.logs.saveWellTest(it) }
            case.otherTasks.forEach { store.maintenanceTaskDao.upsert(it.toEntity()) }

            // --- Read back through the WellRepository (the subject of Req 6.2). ---
            val returnedTests = store.wells.getWellTests(case.well.id)
            val returnedTasks = store.wells.getOpenMaintenanceTasks(case.well.id)

            // 1. Ordering + membership for well tests.
            val expectedTestIdsOrdered =
                case.tests.sortedByDescending { it.capturedAt }.map { it.id }
            val returnedTestIds = returnedTests.map { it.id }

            // a) Exactly the subject well's tests (no leakage from the other well).
            withClue("returned tests must be exactly the subject well's tests: $returnedTestIds") {
                returnedTestIds.toSet() shouldBe case.tests.map { it.id }.toSet()
            }
            // b) Returned in most-recent-first order.
            withClue("returned tests must be most-recent-first: $returnedTestIds vs $expectedTestIdsOrdered") {
                returnedTestIds shouldBe expectedTestIdsOrdered
            }
            // c) Independently confirm the sequence is non-increasing in capturedAt.
            val capturedAts = returnedTests.map { it.capturedAt }
            withClue("returned capturedAt sequence must be non-increasing: $capturedAts") {
                capturedAts.zipWithNext().all { (a, b) -> !a.isBefore(b) }.shouldBeTrue()
            }

            // 2. Open-task filtering.
            val expectedOpenTaskIds =
                case.tasks.filter { it.status == MaintenanceStatus.OPEN }.map { it.id }.toSet()
            val returnedTaskIds = returnedTasks.map { it.id }.toSet()
            // a) Exactly the subject well's open tasks.
            withClue("returned tasks must be exactly the subject well's open tasks") {
                returnedTaskIds shouldBe expectedOpenTaskIds
            }
            // b) Every returned task is actually open (no closed task slips through,
            //    no other well's task leaks in).
            withClue("every returned task must be OPEN and belong to the subject well") {
                returnedTasks.all {
                    it.status == MaintenanceStatus.OPEN && it.wellId == case.well.id
                }.shouldBeTrue()
            }
        }
    }
})

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
private class FakeTransactionRunner : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
}

/**
 * One isolated in-memory store with the REAL WellRepositoryImpl (the subject of
 * Req 6.2) and LogRepositoryImpl (the seeding write path) over map-backed fake
 * DAOs whose query shapes reproduce the production Room queries exactly.
 */
private class LookupStore {
    val wellDao = FakeWellDao()
    val wellTestDao = FakeWellTestDao()
    val wellheadLogDao = FakeWellheadLogDao()
    val escalationDao = FakeEscalationDao()
    val maintenanceTaskDao = FakeMaintenanceTaskDao()

    val wells: WellRepository =
        WellRepositoryImpl(wellDao, wellTestDao, maintenanceTaskDao)
    val logs: LogRepository =
        LogRepositoryImpl(
            FakeTransactionRunner(),
            wellheadLogDao,
            wellTestDao,
            AlwaysFreeStorageGuard,
            FIXED_CLOCK,
        )
}

// ---------------------------------------------------------------------------
// Map-backed fake DAOs (no network, pure in-memory local store)
// ---------------------------------------------------------------------------

private class FakeWellDao : WellDao {
    private val rows = linkedMapOf<String, WellEntity>()
    override suspend fun upsert(well: WellEntity) { rows[well.id] = well }
    override suspend fun upsertAll(wells: List<WellEntity>) = wells.forEach { rows[it.id] = it }
    override suspend fun getWell(id: String): WellEntity? = rows[id]
    override fun observeWell(id: String): Flow<WellEntity?> = flowOf(rows[id])
    override fun observeAll(): Flow<List<WellEntity>> =
        flowOf(rows.values.sortedBy { it.name })
    override suspend fun deleteAll() = rows.clear()
}

private class FakeWellTestDao : WellTestDao {
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

private class FakeWellheadLogDao : WellheadLogDao {
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

private class FakeEscalationDao : EscalationDao {
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

private class FakeMaintenanceTaskDao : MaintenanceTaskDao {
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
 * A subject well with an arbitrary set of tests (DISTINCT capturedAt) and a mix
 * of open/closed tasks, plus an unrelated "other" well whose records must be
 * excluded by the wellId filter.
 */
private data class LookupCase(
    val well: Well,
    val tests: List<WellTest>,
    val tasks: List<MaintenanceTask>,
    val otherWell: Well,
    val otherTests: List<WellTest>,
    val otherTasks: List<MaintenanceTask>,
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

private fun makeWellTest(wellId: String, capturedSecond: Long, o: Double, w: Double, g: Double): WellTest {
    val at = Instant.ofEpochSecond(capturedSecond)
    return WellTest(
        id = UUID.randomUUID(),
        wellId = wellId,
        oilRatio = o,
        waterRatio = w,
        gasRatio = g,
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
 * open/closed, plus an unrelated well with 1..4 tests and at least one OPEN task
 * — the strongest check that both the test ordering query and the open-task
 * query are scoped by wellId.
 */
private val lookupCaseArb: Arb<LookupCase> = Arb.bind(
    Arb.int(1, 500_000).map { "WELL-$it" },
    Arb.int(500_001, 1_000_000).map { "OTHER-$it" },
    Arb.enum<WellType>(),
    // Up to 8 candidate capture seconds for the subject well's tests; deduped
    // below so capturedAt is distinct and the ordering is unambiguous.
    Arb.list(secondsArb, 0..8),
    // Open/closed flags for 0..8 subject tasks.
    Arb.list(Arb.boolean(), 0..8),
    // 1..4 candidate capture seconds for the other well's tests.
    Arb.list(secondsArb, 1..4),
) { subjectId, otherId, wellType, testSeconds, taskFlags, otherTestSeconds ->
    val distinctSubjectSeconds = testSeconds.distinct()
    val tests = distinctSubjectSeconds.map { second ->
        makeWellTest(subjectId, second, 0.6, 0.3, 0.1)
    }
    val tasks = taskFlags.map { isOpen ->
        makeTask(
            subjectId,
            if (isOpen) MaintenanceStatus.OPEN else MaintenanceStatus.CLOSED,
            1_000_000L,
        )
    }

    val otherTests = otherTestSeconds.distinct().map { second ->
        makeWellTest(otherId, second, 0.4, 0.4, 0.2)
    }
    // Force at least one OPEN task on the other well — must be excluded.
    val otherTasks = listOf(makeTask(otherId, MaintenanceStatus.OPEN, 1_000_000L))

    LookupCase(
        well = makeWell(subjectId, wellType, 1_000_000L),
        tests = tests,
        tasks = tasks,
        otherWell = makeWell(otherId, wellType, 1_000_000L),
        otherTests = otherTests,
        otherTasks = otherTasks,
    )
}
