/*
 * Property 7: Five-retry boundary and manual-retry reset (Android) — task 16.5.
 *
 * Validates: Requirements 2.5, 7.7
 *
 * Requirement 2.5: if a record's synchronization has failed on 5 consecutive
 * attempts, the Sync_Service sets that record's state to failed and stops
 * automatic retries for that record until the Operator triggers a manual retry.
 * Requirement 7.7: the same 5-consecutive-failure boundary and manual-retry
 * re-enablement for Escalations.
 *
 * Design "Property 7: Five-retry boundary and manual-retry reset":
 *
 *     For any locally created record whose every synchronization attempt fails,
 *     the record stays PENDING through its first four failures and transitions
 *     to FAILED on the fifth; once FAILED it is excluded from nextPendingBatch
 *     (so automatic retries stop); and a SyncRepository.manualRetry resets it to
 *     PENDING, clearing the retry budget, so the engine pushes it again.
 *
 * This mirrors the iOS FiveRetryBoundaryAndManualRetryResetPropertyTests (task
 * 15.5): it drives the REAL engine end to end over the REAL repository,
 * stubbing only the push outcome (a switchable stub) and the persistence
 * substrate.
 *
 *   * [SyncCycleRunner] is the actual production runner — the platform-agnostic
 *     heart of the Android sync engine. None of its failure/acknowledge path is
 *     stubbed.
 *   * [SyncRepositoryImpl] is the actual production repository, which owns the
 *     per-record state machine: recordFailure increments retryCount, stamps
 *     lastAttemptAt, and flips to FAILED only at the 5th failure
 *     ([SYNC_MAX_RETRY_COUNT]); nextPendingBatch excludes FAILED rows;
 *     manualRetry resets to PENDING with retryCount 0.
 *   * The push client is [SwitchablePushClient]: it returns
 *     [SyncPushResult.RetryableFailure] while driving the record to FAILED, then
 *     is switched to [SyncPushResult.Acknowledged] to prove the manual retry
 *     truly re-enables automatic sync. It counts pushes so the property can
 *     assert that NO push happens while the record is FAILED.
 *
 * -- A single controllable clock shared by repository and runner --
 *
 * A single advancing [MutableClock] backs BOTH the runner's [SyncClock]
 * (backoff eligibility) and, via [javaClock], the repository's
 * [java.time.Clock] (which stamps lastAttemptAt on each failure), so the two
 * live on one timeline. Before every cycle the clock is advanced well past the
 * largest possible backoff window, so the seeded record is eligible every cycle
 * and each failing cycle lands exactly one failure — making the five-failure
 * boundary deterministic.
 *
 * -- Why map-backed fake DAOs rather than a live Room DB --
 *
 * On iOS the mirror opens a real in-memory GRDB database. Room cannot do the
 * equivalent in a pure-JVM unit test: opening a Room database (even in-memory)
 * needs an Android Context and the native SQLite runtime, available only under
 * Robolectric or on-device instrumentation, neither present here (no JDK/Gradle;
 * the unit-test source set is plain JVM). As the other Android sync properties
 * do (e.g. NoDataLossOnFailedSyncPropertyTest, PendingStateInvariantPropertyTest),
 * this test uses a map-backed fake [WellTestDao] that implements the production
 * DAO interface and reproduces the exact query shapes the real Room queries pin
 * down (Daos.kt):
 *
 *   * nextPending: WHERE syncState = PENDING ORDER BY createdAt ASC LIMIT :limit.
 *   * getById / update / upsert: by primary key.
 *
 * Only a well-test record is seeded: it routes through [SyncPushClient] (the
 * push path Reqs 2.5/7.7 concern for the non-blob case). The other three
 * syncable DAOs are empty fakes; escalations take the separate multipart
 * blob-upload path, so the escalation seams here are inert and fail loudly if
 * ever reached.
 *
 * Assertions per generated case:
 *   1. After each of the first four failing cycles the record is still PENDING
 *      and appears in nextPendingBatch (automatic retries continue).
 *   2. After the fifth failing cycle the record is FAILED and is absent from
 *      nextPendingBatch (automatic retries stopped — Req 2.5 exclusion).
 *   3. A further cycle while FAILED triggers no push and no state change.
 *   4. manualRetry resets it to PENDING with retryCount 0 and re-adds it to the
 *      batch (Req 2.5, 7.7).
 *   5. With the client switched to acknowledge, the next cycle pushes it again
 *      and it becomes SYNCHRONIZED — confirming the reset genuinely re-enables
 *      automatic sync.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 7: Five-retry boundary and manual-retry reset`.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.SubcoolReadingDao
import com.sagd.welltest.localstore.SubcoolReadingEntity
import com.sagd.welltest.localstore.SyncMetadataEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.network.BlobUploadClient
import com.sagd.welltest.network.SyncPushClient
import com.sagd.welltest.network.SyncPushResult
import com.sagd.welltest.pbtConfig
import com.sagd.welltest.repository.SyncRepositoryImpl
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class FiveRetryBoundaryAndManualRetryResetPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 7: Five-retry boundary and manual-retry reset" {
        checkAll(pbtConfig(), retryCaseArb) { testCase ->
            boundaryHolds(testCase)
        }
    }
})

// ---------------------------------------------------------------------------
// The property check for one generated case
// ---------------------------------------------------------------------------

/**
 * Seeds one PENDING well test, drives the real engine over the real repository
 * through four failing cycles (still PENDING), a fifth (FAILED + excluded), an
 * idle cycle while FAILED (no push, no change), a manualRetry (reset to PENDING
 * / retryCount 0 / back in batch), then one acknowledging cycle (SYNCHRONIZED).
 */
private suspend fun boundaryHolds(testCase: RetryCase) {
    // A single advancing clock shared by the repository (stamps lastAttemptAt on
    // failure) and the runner (backoff eligibility), so advancing it past the
    // backoff window keeps the record eligible every cycle.
    val clock = MutableClock(start = testCase.startInstant)

    val wellTestDao = FakeWellTestDao()
    wellTestDao.insert(testCase.test.toSeedEntity())

    val repository = SyncRepositoryImpl(
        wellTestDao = wellTestDao,
        wellheadLogDao = EmptyWellheadLogDao,
        subcoolReadingDao = EmptySubcoolReadingDao,
        escalationDao = EmptyEscalationDao,
        clock = clock.javaClock(),
    )

    // Fails retryably while driving to FAILED; switched to acknowledge later.
    val pushClient = SwitchablePushClient(
        SyncPushResult.RetryableFailure("stubbed offline failure"),
    )

    val backoff = ExponentialBackoffPolicy()
    val runner = SyncCycleRunner(
        syncRepository = repository,
        pushClient = pushClient,
        blobUploadClient = UnusedBlobUploadClient,
        blobSource = UnusedEscalationBlobSource,
        backoffPolicy = backoff,
        clock = clock,
        // A generous batch so the single record is always pulled when eligible.
        batchSize = 100,
    )

    val recordId = testCase.test.id

    withClue("record $recordId") {
        // --- Phase 1: the first four failing cycles stay PENDING & batched. ---
        for (cycle in 1 until SYNC_MAX_RETRY_COUNT) {
            val outcome = runner.runSyncCycle()
            withClue("failing cycle $cycle should push and fail exactly the one record") {
                outcome.acknowledged shouldBe 0
                outcome.failed shouldBe 1
                outcome.skipped shouldBe 0
            }
            withClue("after failing cycle $cycle the record must still be PENDING") {
                wellTestDao.syncStateOf(recordId) shouldBe SyncState.PENDING
            }
            withClue("after failing cycle $cycle the record must still be in nextPendingBatch") {
                repository.isBatched(recordId) shouldBe true
            }
            // Jump past this attempt's backoff window so the next cycle finds it eligible.
            clock.advancePastBackoff(backoff)
        }

        // --- Phase 2: the fifth failing cycle flips to FAILED & excludes it. ---
        val fifth = runner.runSyncCycle()
        withClue("the fifth failing cycle should push and fail the one record") {
            fifth.acknowledged shouldBe 0
            fifth.failed shouldBe 1
        }
        withClue("after the fifth consecutive failure the record must be FAILED (Req 2.5)") {
            wellTestDao.syncStateOf(recordId) shouldBe SyncState.FAILED
        }
        withClue("a FAILED record must be excluded from nextPendingBatch (Req 2.5)") {
            repository.isBatched(recordId) shouldBe false
        }

        // --- Phase 3: a further cycle while FAILED must not push or change it. ---
        clock.advancePastBackoff(backoff)
        val pushesBeforeIdle = pushClient.pushCount
        val idle = runner.runSyncCycle()
        withClue("a cycle while FAILED must process no records") {
            idle.processed shouldBe 0
            idle.acknowledged shouldBe 0
            idle.failed shouldBe 0
        }
        withClue("no push may occur while the record is FAILED (automatic retries stopped)") {
            pushClient.pushCount shouldBe pushesBeforeIdle
        }
        withClue("the record must remain FAILED after the idle cycle") {
            wellTestDao.syncStateOf(recordId) shouldBe SyncState.FAILED
        }

        // --- Phase 4: manualRetry resets to PENDING / retryCount 0 / re-batched. ---
        repository.manualRetry(recordId)
        withClue("manualRetry must reset the record to PENDING (Req 2.5, 7.7)") {
            wellTestDao.syncStateOf(recordId) shouldBe SyncState.PENDING
        }
        withClue("manualRetry must clear the retry budget (retryCount 0)") {
            wellTestDao.retryCountOf(recordId) shouldBe 0
        }
        withClue("a manually retried record must be back in nextPendingBatch") {
            repository.isBatched(recordId) shouldBe true
        }

        // --- Phase 5: with the client acknowledging, the next cycle syncs it. ---
        pushClient.result = SyncPushResult.Acknowledged(testCase.remoteId)
        clock.advancePastBackoff(backoff)
        val finalOutcome = runner.runSyncCycle()
        withClue("the reset record must be pushed again and acknowledged") {
            finalOutcome.acknowledged shouldBe 1
            finalOutcome.failed shouldBe 0
        }
        withClue("after acknowledgment the manually-retried record must be SYNCHRONIZED") {
            wellTestDao.syncStateOf(recordId) shouldBe SyncState.SYNCHRONIZED
        }
    }
}

/** Whether [recordId] currently appears in the pending batch (generous limit). */
private suspend fun SyncRepositoryImpl.isBatched(recordId: UUID): Boolean =
    nextPendingBatch(100).any { it.recordId == recordId }

private suspend fun FakeWellTestDao.syncStateOf(id: UUID): SyncState? =
    getById(id)?.sync?.syncState

private suspend fun FakeWellTestDao.retryCountOf(id: UUID): Int? =
    getById(id)?.sync?.retryCount

// ---------------------------------------------------------------------------
// Generated case model
// ---------------------------------------------------------------------------

/**
 * A single pending well test under test, a start instant for the virtual clock,
 * and the remote id the acknowledging push echoes back.
 */
private data class RetryCase(
    val test: WellTest,
    val startInstant: Instant,
    val remoteId: String,
)

/**
 * A freshly created, never-attempted PENDING record (retryCount 0, no
 * lastAttemptAt) so it is eligible for the first cycle.
 */
private fun WellTest.toSeedEntity(): WellTestEntity =
    WellTestEntity(
        id = id,
        wellId = wellId,
        oilRatio = oilRatio,
        waterRatio = waterRatio,
        gasRatio = gasRatio,
        capturedAt = capturedAt,
        sync = SyncMetadataEntity(
            syncState = SyncState.PENDING,
            retryCount = 0,
            createdAt = sync.createdAt,
            lastAttemptAt = null,
            remoteId = null,
        ),
    )

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------

private val timestampArb: Arb<Instant> =
    Arb.long(1_600_000_000L, 1_800_000_000L).map { Instant.ofEpochSecond(it) }

private val retryCaseArb: Arb<RetryCase> = Arb.bind(
    Arb.int(1..1_000_000),
    Arb.double(0.0, 1.0),
    Arb.double(0.0, 1.0),
    Arb.double(0.0, 1.0),
    timestampArb,
    timestampArb,
    Arb.int(1..1_000_000),
) { wellNum, oil, water, gas, capturedAt, start, remoteNum ->
    // The remaining primitive is drawn separately below to stay within arity.
    RetryCasePrimitives(
        wellNum = wellNum,
        oil = oil,
        water = water,
        gas = gas,
        capturedAt = capturedAt,
        start = start,
        remoteNum = remoteNum,
    )
}.map { p ->
    RetryCase(
        test = WellTest(
            id = UUID.randomUUID(),
            wellId = "WELL-${p.wellNum}",
            oilRatio = p.oil,
            waterRatio = p.water,
            gasRatio = p.gas,
            capturedAt = p.capturedAt,
            sync = SyncMetadata(
                syncState = SyncState.PENDING,
                retryCount = 0,
                createdAt = p.capturedAt,
                lastAttemptAt = null,
                remoteId = null,
            ),
        ),
        startInstant = p.start,
        remoteId = "REMOTE-${p.remoteNum}",
    )
}

/** Raw primitives for a retry case, bundled to stay within Arb.bind's arity. */
private class RetryCasePrimitives(
    val wellNum: Int,
    val oil: Double,
    val water: Double,
    val gas: Double,
    val capturedAt: Instant,
    val start: Instant,
    val remoteNum: Int,
)

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

/**
 * A push client whose single result can be switched between calls. Counts
 * pushes so the test can assert that no push happens while a record is FAILED,
 * mirroring the iOS SwitchablePushClient.
 */
private class SwitchablePushClient(initial: SyncPushResult) : SyncPushClient {
    var result: SyncPushResult = initial

    var pushCount: Int = 0
        private set

    override suspend fun push(record: SyncableRecord): SyncPushResult {
        pushCount++
        return result
    }
}

/**
 * A controllable clock whose instant only moves when the test advances it, used
 * BOTH as the engine's [SyncClock] (backoff "now") and, via [javaClock], as the
 * repository's [java.time.Clock] (stamps lastAttemptAt on failure). Sharing one
 * timeline is what lets [advancePastBackoff] reliably re-enable the pending
 * record before each cycle.
 */
private class MutableClock(start: Instant) : SyncClock {
    @Volatile
    private var current: Instant = start

    override fun now(): Instant = current

    override suspend fun sleep(seconds: Double) {
        // No real waiting in tests; backoff eligibility is driven by now().
    }

    /** Jump just past the largest possible backoff window so the record is eligible. */
    fun advancePastBackoff(policy: ExponentialBackoffPolicy) {
        current = current.plusMillis((policy.delaySeconds(SYNC_MAX_RETRY_COUNT) * 1000).toLong() + 1_000)
    }

    /** A java.time.Clock reading this same advancing instant (fixed ZoneOffset.UTC). */
    fun javaClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
    }
}

/**
 * Escalation seams that must never be reached: this property seeds only a
 * well-test record routed through [SyncPushClient]. Reaching either would mean
 * the engine misrouted a non-escalation record.
 */
private object UnusedBlobUploadClient : BlobUploadClient {
    override suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult =
        error("blob upload client reached for a non-escalation property")
}

private object UnusedEscalationBlobSource : EscalationBlobSource {
    override suspend fun escalationWithBlob(id: UUID): EscalationBlob? =
        error("escalation blob source reached for a non-escalation property")
}

// ---------------------------------------------------------------------------
// Map-backed fake DAOs
// ---------------------------------------------------------------------------

/**
 * In-memory [WellTestDao] reproducing the production query shapes the sync path
 * uses: `nextPending` returns PENDING rows oldest-first by createdAt, limited;
 * `getById`/`update`/`upsert` key on the primary id. Backs the real
 * [SyncRepositoryImpl] so the state machine runs unchanged over a map rather
 * than SQLite.
 */
private class FakeWellTestDao : WellTestDao {
    private val rows = linkedMapOf<UUID, WellTestEntity>()

    fun insert(entity: WellTestEntity) {
        rows[entity.id] = entity
    }

    override suspend fun upsert(test: WellTestEntity) {
        rows[test.id] = test
    }

    override suspend fun getById(id: UUID): WellTestEntity? = rows[id]

    override suspend fun getForWell(wellId: String): List<WellTestEntity> =
        rows.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }

    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        rows.values
            .filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    override fun observeSyncState(id: UUID): Flow<SyncState?> =
        flowOf(rows[id]?.sync?.syncState)

    override suspend fun update(test: WellTestEntity) {
        rows[test.id] = test
    }
}

/** Empty wellhead-log DAO: no logs are seeded by this property. */
private object EmptyWellheadLogDao : WellheadLogDao {
    override suspend fun upsert(log: WellheadLogEntity) = Unit
    override suspend fun getById(id: UUID): WellheadLogEntity? = null
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> = emptyList()
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        emptyList()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
    override suspend fun update(log: WellheadLogEntity) = Unit
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

/** Empty escalation DAO: no escalations are seeded by this property. */
private object EmptyEscalationDao : EscalationDao {
    override suspend fun upsert(escalation: EscalationEntity) = Unit
    override suspend fun getById(id: UUID): EscalationEntity? = null
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        emptyList()
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(null)
    override suspend fun allPhotoRefs(): List<String> = emptyList()
    override suspend fun update(escalation: EscalationEntity) = Unit
    override suspend fun delete(escalation: EscalationEntity) = Unit
}
