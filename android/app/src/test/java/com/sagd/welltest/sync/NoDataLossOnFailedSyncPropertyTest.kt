/*
 * Property 6: No data loss on failed sync (Android) — task 16.4.
 *
 * Validates: Requirements 2.4
 *
 * Requirement 2.4: if a synchronization attempt for a record fails, the
 * Sync_Service retains the record in the Local_Store without data loss and
 * retries it on the next connectivity event.
 *
 * Design "Property 6: No data loss on failed sync":
 *
 *     For any pending record and any sync attempt that fails, the record
 *     remains in the Local_Store with its captured payload unchanged and
 *     remains eligible for a later retry.
 *
 * This mirrors the iOS NoDataLossOnFailedSyncPropertyTests (task 15.4): it
 * drives the REAL engine end to end over the REAL repository, stubbing only the
 * push outcome and the persistence substrate.
 *
 *   * [SyncCycleRunner] is the actual production runner — the platform-agnostic
 *     heart of the Android sync engine. Nothing about its failure path is
 *     stubbed.
 *   * [SyncRepositoryImpl] is the actual production repository, which owns the
 *     per-record state machine (recordFailure → retryCount++, lastAttemptAt
 *     stamped, FAILED only at the 5th failure). Its [Clock] is pinned to a
 *     fixed instant so `lastAttemptAt` is deterministic.
 *   * The push client is [AlwaysRetryableFailurePushClient], whose every attempt
 *     is a transient [SyncPushResult.RetryableFailure]. The engine therefore
 *     records a failed attempt for each record without ever acknowledging.
 *   * The clock passed to the runner is a fixed [FixedSyncClock]; seeded records
 *     have `lastAttemptAt == null` / `retryCount == 0`, so every record is
 *     immediately backoff-eligible and pushed in the one cycle.
 *
 * -- Why map-backed fake DAOs rather than a live Room DB --
 *
 * On iOS the mirror opens a real in-memory GRDB database, because GRDB runs
 * directly on the host as a plain library. Room cannot do the equivalent in a
 * pure-JVM unit test: opening a Room database (even in-memory) needs an Android
 * Context and the native SQLite runtime, available only under Robolectric or
 * on-device instrumentation, neither present here (no JDK/Gradle in this
 * environment, the unit-test source set is plain JVM). As the other Android
 * sync/repository properties do (e.g. AtomicSavePreservesPriorStatePropertyTest,
 * QRLookupConnectivityPropertyTest), this test uses map-backed fake DAOs that
 * implement the production DAO interfaces and reproduce the exact query shapes
 * the real Room queries pin down (Daos.kt):
 *
 *   * WellTestDao.nextPending: WHERE syncState = PENDING ORDER BY createdAt ASC
 *     LIMIT :limit.
 *   * getById / update: by primary key.
 *
 * Only well-test records are seeded: they route through [SyncPushClient] (the
 * push path Req 2.4 concerns). The other three syncable DAOs are empty fakes;
 * escalations take the separate multipart blob-upload path covered by other
 * properties, so the escalation seams here are inert and fail loudly if ever
 * reached.
 *
 * After one failing cycle the property asserts, for every seeded record:
 *   1. the row still exists in the store (no record dropped — Req 2.4);
 *   2. its captured payload (id, well association, ratios, timestamp) is
 *      unchanged (no data mutation — Req 2.4);
 *   3. its retryCount incremented to exactly 1 (the failure was recorded);
 *   4. its sync state is still PENDING — below the 5-failure boundary — and it
 *      is still returned by nextPendingBatch, so it remains eligible for a later
 *      retry (Req 2.4).
 *
 * One failed cycle (not five) is used deliberately: Property 6 is about the
 * no-loss / still-eligible guarantee of a *failed* attempt, keeping every record
 * strictly below the FAILED boundary that Property 7 covers.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 6: No data loss on failed sync`.
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
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NoDataLossOnFailedSyncPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 6: No data loss on failed sync" {
        checkAll(pbtConfig(), seededCycleArb) { seed ->
            failedSyncLosesNothing(seed)
        }
    }
})

// ---------------------------------------------------------------------------
// The property check for one generated case
// ---------------------------------------------------------------------------

/**
 * Seeds the generated pending well tests, runs a single sync cycle in which
 * every push fails retryably, then asserts each record survived intact,
 * recorded exactly one failed attempt, and is still a pending, retry-eligible
 * record.
 */
private suspend fun failedSyncLosesNothing(seed: SeededCycle) {
    // A fixed instant shared by the repository clock (stamps lastAttemptAt on
    // failure) and the runner clock (backoff eligibility), so the outcome is
    // deterministic.
    val fixedNow = Instant.ofEpochSecond(1_700_000_000)

    val wellTestDao = FakeWellTestDao()
    for (test in seed.tests) {
        wellTestDao.insert(test.toSeedEntity())
    }

    val repository = SyncRepositoryImpl(
        wellTestDao = wellTestDao,
        wellheadLogDao = EmptyWellheadLogDao,
        subcoolReadingDao = EmptySubcoolReadingDao,
        escalationDao = EmptyEscalationDao,
        clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
    )

    val pushClient = AlwaysRetryableFailurePushClient()

    val runner = SyncCycleRunner(
        syncRepository = repository,
        pushClient = pushClient,
        blobUploadClient = UnusedBlobUploadClient,
        blobSource = UnusedEscalationBlobSource,
        backoffPolicy = ExponentialBackoffPolicy(),
        clock = FixedSyncClock(fixedNow),
        // A batch size comfortably above the seeded count so every record is
        // pushed in the single cycle.
        batchSize = seed.tests.size + 1,
    )

    val outcome = runner.runSyncCycle()

    withClue("seed of ${seed.tests.size} pending well tests") {
        // Every seeded record must have been attempted and failed (none skipped
        // by backoff, none acknowledged).
        outcome.processed shouldBe seed.tests.size
        outcome.failed shouldBe seed.tests.size
        outcome.acknowledged shouldBe 0
        outcome.skipped shouldBe 0
        pushClient.pushCount shouldBe seed.tests.size

        for (saved in seed.tests) {
            val reloaded = wellTestDao.getById(saved.id)

            // 1. The record still exists — nothing was dropped.
            withClue("well test ${saved.id} was lost from the store after a failed sync") {
                (reloaded != null) shouldBe true
            }
            reloaded!!

            // 2. Captured payload unchanged (no data loss / mutation).
            withClue("captured fields changed after failed sync for ${saved.id}") {
                reloaded.id shouldBe saved.id
                reloaded.wellId shouldBe saved.wellId
                reloaded.oilRatio shouldBe saved.oilRatio
                reloaded.waterRatio shouldBe saved.waterRatio
                reloaded.gasRatio shouldBe saved.gasRatio
                reloaded.capturedAt shouldBe saved.capturedAt
                reloaded.sync.createdAt shouldBe saved.sync.createdAt
            }

            // 3. The failed attempt was recorded: retryCount incremented to 1.
            withClue("retryCount expected 1 after one failed attempt for ${saved.id}") {
                reloaded.sync.retryCount shouldBe 1
            }

            // 4. Below the 5-failure boundary -> still pending.
            withClue("state expected PENDING after one failed attempt for ${saved.id}") {
                reloaded.sync.syncState shouldBe SyncState.PENDING
            }
        }

        // 4 (cont.). Each record is still offered by the next pending batch, so
        // it remains eligible for a later retry — it was not excluded.
        val stillPending: List<SyncableRecord> =
            repository.nextPendingBatch(seed.tests.size + 1)
        val pendingIds = stillPending.map { it.recordId }
        val expectedIds = seed.tests.map { it.id }
        withClue("records not eligible for retry after a failed sync: pending=$pendingIds, expected=$expectedIds") {
            pendingIds shouldContainAll expectedIds
        }
    }
}

// ---------------------------------------------------------------------------
// Generated case model
// ---------------------------------------------------------------------------

/** A seeded sync cycle: the pending well tests to push. */
private data class SeededCycle(val tests: List<WellTest>)

/**
 * A seeded well test is written PENDING with retryCount == 0 and no prior
 * attempt (so it is backoff-eligible in the single cycle). The sync metadata
 * here is the *seed* state; the property asserts the captured fields survive and
 * the engine moved retryCount 0 -> 1 after the failed push.
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
//
// Draws 1..6 pending well tests (so the "set of records" clause is exercised
// with more than one), each with its own random id, well association, and
// captured fields. Every record is seeded PENDING / retryCount 0 / no prior
// attempt, so all are backoff-eligible in the single cycle.

private val timestampArb: Arb<Instant> =
    Arb.long(1_600_000_000L, 1_800_000_000L).map { Instant.ofEpochSecond(it) }

private val wellTestArb: Arb<WellTest> = Arb.bind(
    Arb.int(1..1_000_000),
    Arb.double(0.0, 1.0),
    Arb.double(0.0, 1.0),
    Arb.double(0.0, 1.0),
    timestampArb,
) { wellNum, oil, water, gas, capturedAt ->
    WellTest(
        id = UUID.randomUUID(),
        wellId = "WELL-$wellNum",
        oilRatio = oil,
        waterRatio = water,
        gasRatio = gas,
        capturedAt = capturedAt,
        sync = SyncMetadata(
            syncState = SyncState.PENDING,
            retryCount = 0,
            createdAt = capturedAt,
            lastAttemptAt = null,
            remoteId = null,
        ),
    )
}

private val seededCycleArb: Arb<SeededCycle> =
    Arb.list(wellTestArb, 1..6).map { SeededCycle(it) }

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

/**
 * A push client whose every attempt is a transient (retryable) failure, so the
 * engine records a failure for each record without acknowledging. Counts
 * attempts so the property can assert every seeded record was pushed.
 */
private class AlwaysRetryableFailurePushClient : SyncPushClient {
    var pushCount: Int = 0
        private set

    override suspend fun push(record: SyncableRecord): SyncPushResult {
        pushCount++
        return SyncPushResult.RetryableFailure("simulated transient failure")
    }
}

/** A controllable clock pinned at a fixed instant; `sleep` returns at once. */
private class FixedSyncClock(private val instant: Instant) : SyncClock {
    override fun now(): Instant = instant
    override suspend fun sleep(seconds: Double) {}
}

/**
 * Escalation seams that must never be reached: this property seeds only records
 * routed through [SyncPushClient]. Reaching either would mean the engine
 * misrouted a non-escalation record.
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
 * `getById`/`update` key on the primary id. Backs the real
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
