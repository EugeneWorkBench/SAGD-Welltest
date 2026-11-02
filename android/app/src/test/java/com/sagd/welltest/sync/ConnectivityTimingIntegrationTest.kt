/*
 * Integration test — connectivity timing and background execution (Android) — task 16.8.
 *
 * Validates: Requirements 2.2, 2.8, 7.6
 *
 * This is the Android mirror of the iOS ConnectivityTimingIntegrationTests
 * (task 15.8). It exercises the connectivity-driven behavior of the real
 * production [WorkManagerSyncEngine] end to end through its seams, with NO real
 * ConnectivityManager.NetworkCallback, NO real network, NO real WorkManager, and
 * NO real delays. Three guarantees are asserted against a controllable
 * [SyncClock]:
 *
 *   * Req 2.2 — WHEN connectivity is restored after a period without it, the
 *     Sync_Service SHALL begin synchronizing unsynchronized records within 60s.
 *     Here: the engine's connectivity monitor is started, connectivity is driven
 *     from UNSATISFIED to SATISFIED (a genuine "returns after a period without
 *     it" transition), and we assert (a) the engine enqueues sync work — the
 *     real engine's only reaction to a restore is workScheduler.enqueueImmediateSync(),
 *     observed by a recording [SyncWorkScheduler] that stamps the virtual clock
 *     at enqueue — within 60s of the restore, and (b) that the enqueued work,
 *     once WorkManager would grant it execution, drives a real
 *     [SyncCycleRunner] cycle whose recording push client observes the seeded
 *     pending records being pushed. (A real WorkManager run is replaced by
 *     invoking the engine's runSyncCycle() exactly as the SyncWorker shell does;
 *     WorkManager's own scheduling latency is well under 60s when a network is
 *     present and is not simulated here.)
 *
 *   * Req 2.8 — WHILE the device has no network connectivity, the App SHALL
 *     display an offline indicator within 5s of connectivity loss. Here:
 *     connectivity is driven from SATISFIED to UNSATISFIED and we assert the
 *     [OfflineIndicator] the UI observes flips to offline within 5s of the loss
 *     (measured on the same virtual clock).
 *
 *   * Req 7.6 (WorkManager job resumes across process death) — asserted at the
 *     seam level, as the task directs. WorkManager itself guarantees that an
 *     enqueued unique work survives process death and is re-run; what the app
 *     owns — and what this test pins down — is that the DURABLE QUEUE behaves
 *     correctly across that restart: a record left PENDING by a half-finished
 *     cycle (its push failed and the "process" died before it could be retried)
 *     is still returned by [SyncRepository.nextPendingBatch] after the runner
 *     and repository are RECONSTRUCTED over the SAME store (simulating a fresh
 *     process). A new cycle built on the reconstructed graph then resumes and
 *     completes that record. We do not drive a real WorkManager across a real
 *     process death (impossible in a pure-JVM unit test); we assert the
 *     invariant that makes WorkManager's guarantee meaningful — no pending work
 *     is lost or skipped when the runner is rebuilt over a persistent store.
 *
 * -- Why a map-backed fake store rather than a live Room DB --
 *
 * On iOS the mirror opens a real in-memory GRDB AppDatabase because GRDB runs
 * directly on the host. Room cannot: opening a Room database (even in-memory)
 * needs an Android Context and the native SQLite runtime, available only under
 * Robolectric or on-device instrumentation, neither present in this pure-JVM
 * unit test. This test therefore backs [SyncRepository] with a map-backed store
 * ([InMemorySyncStore]) whose nextPendingBatch reproduces the exact query shape
 * the real Room-backed SyncRepositoryImpl pins down (PENDING rows, ORDER BY
 * createdAt ASC, excluding FAILED — Req 2.1). Crucially the store is a SEPARATE,
 * persistent object: a repository is a thin view over it, so "reconstructing the
 * runner/repository over the same store" (the process-death seam) is modelled by
 * building a fresh repository + runner over the SAME [InMemorySyncStore]. The
 * REAL [WorkManagerSyncEngine], [SyncCycleRunner], [OfflineIndicator], and
 * [ExponentialBackoffPolicy] run unchanged; only the persistence substrate is
 * substituted, which is irrelevant to the connectivity/queue behavior under test.
 *
 * The connectivity seam is [DrivableConnectivityProvider] — a fake standing in
 * for ConnectivityManager.NetworkCallback whose statusStream() can be driven to
 * emit SATISFIED / UNSATISFIED on command, with the first emission being the
 * current status (matching the real provider's contract). The clock is a
 * [ControllableSyncClock] reporting a fixed virtual instant so timing assertions
 * are deterministic and never actually wait.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.SyncableRecordType
import com.sagd.welltest.network.BlobUploadClient
import com.sagd.welltest.network.SyncPushClient
import com.sagd.welltest.network.SyncPushResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ConnectivityTimingIntegrationTest : StringSpec({

    /**
     * Req 2.2 — a connectivity restore begins a sync cycle (records push) within
     * 60s of the restore transition.
     */
    "connectivity restore enqueues and begins a sync cycle within 60s (Req 2.2)" {
        runTest {
            val base = Instant.ofEpochSecond(1_700_000_000L)
            val clock = ControllableSyncClock(base)

            // A persistent store seeded with pending records across record kinds
            // so the triggered cycle has real work to push.
            val store = InMemorySyncStore(seedPendingRecords())
            val repository: SyncRepository = InMemorySyncRepository(store)

            val recorder = PushRecorder(clock)
            // Start OFFLINE so the restore is a genuine "connectivity returns
            // after a period without it" transition (Req 2.2).
            val connectivity = DrivableConnectivityProvider(ConnectivityStatus.UNSATISFIED)
            val offlineIndicator = OfflineIndicator(initiallyOffline = true)

            // The recording scheduler stamps the virtual clock at each enqueue —
            // this is the engine's sole reaction to a restore (Req 2.2).
            val scheduler = RecordingSyncWorkScheduler(clock)

            val cycleRunner = SyncCycleRunner(
                syncRepository = repository,
                pushClient = RecordingSyncPushClient(recorder),
                blobUploadClient = RecordingBlobUploadClient(recorder),
                blobSource = StubEscalationBlobSource(seedEscalationsById()),
                backoffPolicy = ExponentialBackoffPolicy(),
                clock = clock,
                batchSize = 50,
            )

            val engine = WorkManagerSyncEngine(
                cycleRunner = cycleRunner,
                syncRepository = repository,
                blobUploadClient = RecordingBlobUploadClient(recorder),
                blobSource = StubEscalationBlobSource(seedEscalationsById()),
                connectivityProvider = connectivity,
                offlineIndicator = offlineIndicator,
                workScheduler = scheduler,
            )

            // Observe connectivity on a background dispatcher so the collector
            // runs concurrently while we drive transitions.
            val monitor = engine.startMonitoring(backgroundScope)
            // Let the monitor subscribe and consume the baseline (UNSATISFIED).
            testScheduler.runCurrent()

            // The instant at which connectivity is restored.
            val restoreInstant = clock.now()
            connectivity.drive(ConnectivityStatus.SATISFIED)
            // Let the engine react to the restore (setOffline(false) +
            // onConnectivityAvailable -> scheduler.enqueueImmediateSync()).
            testScheduler.runCurrent()

            // (a) The engine enqueued the sync work within 60s of the restore.
            val enqueueInstant = withClue("engine must enqueue sync work on restore (Req 2.2)") {
                scheduler.firstEnqueueInstant() ?: error("no sync work enqueued on connectivity restore")
            }
            val enqueueElapsed = Duration.between(restoreInstant, enqueueInstant)
            enqueueElapsed shouldBeGreaterThanOrEqualTo Duration.ZERO
            withClue("enqueue must occur within 60s of restore (Req 2.2); elapsed ${enqueueElapsed.toMillis()}ms") {
                enqueueElapsed shouldBeLessThanOrEqualTo CONNECTIVITY_TRIGGER_DEADLINE
            }

            // (b) When WorkManager would grant the enqueued work execution, the
            // engine's runSyncCycle() (what the SyncWorker shell invokes) drives
            // the real runner so the seeded pending records are pushed — the
            // cycle has genuinely begun.
            engine.runSyncCycle()

            val firstPushInstant = withClue("a sync cycle must begin and push pending records (Req 2.2)") {
                recorder.firstPushInstant() ?: error("no record pushed after the triggered sync cycle")
            }
            val pushElapsed = Duration.between(restoreInstant, firstPushInstant)
            pushElapsed shouldBeGreaterThanOrEqualTo Duration.ZERO
            withClue("sync must begin within 60s of restore (Req 2.2); elapsed ${pushElapsed.toMillis()}ms") {
                pushElapsed shouldBeLessThanOrEqualTo CONNECTIVITY_TRIGGER_DEADLINE
            }
            // Every seeded record was pushed exactly once this cycle.
            recorder.pushedIds().toSet() shouldBe seedPendingRecords().map { it.recordId }.toSet()

            monitor.cancel()
        }
    }

    /**
     * Req 2.8 — a connectivity loss flips the offline indicator within 5s of the
     * loss transition.
     */
    "connectivity loss flips the offline indicator within 5s (Req 2.8)" {
        runTest {
            val base = Instant.ofEpochSecond(1_700_000_000L)
            val clock = ControllableSyncClock(base)

            val store = InMemorySyncStore(seedPendingRecords())
            val repository: SyncRepository = InMemorySyncRepository(store)
            val recorder = PushRecorder(clock)

            // Start ONLINE so the loss is a genuine SATISFIED -> UNSATISFIED drop.
            val connectivity = DrivableConnectivityProvider(ConnectivityStatus.SATISFIED)
            val offlineIndicator = OfflineIndicator(initiallyOffline = false)
            val scheduler = RecordingSyncWorkScheduler(clock)

            val cycleRunner = SyncCycleRunner(
                syncRepository = repository,
                pushClient = RecordingSyncPushClient(recorder),
                blobUploadClient = RecordingBlobUploadClient(recorder),
                blobSource = StubEscalationBlobSource(seedEscalationsById()),
                clock = clock,
                batchSize = 50,
            )

            val engine = WorkManagerSyncEngine(
                cycleRunner = cycleRunner,
                syncRepository = repository,
                blobUploadClient = RecordingBlobUploadClient(recorder),
                blobSource = StubEscalationBlobSource(seedEscalationsById()),
                connectivityProvider = connectivity,
                offlineIndicator = offlineIndicator,
                workScheduler = scheduler,
            )

            // The value the UI binds to, captured whenever it changes.
            val offlineFlipInstant = AtomicReference<Instant?>(null)
            val observer = backgroundScope.launch {
                offlineIndicator.observe().collect { isOffline ->
                    if (isOffline && offlineFlipInstant.get() == null) {
                        offlineFlipInstant.set(clock.now())
                    }
                }
            }

            val monitor = engine.startMonitoring(backgroundScope)
            // Let the monitor subscribe and consume the baseline (SATISFIED):
            // this also sets offline=false, so the pre-loss baseline is clean.
            testScheduler.runCurrent()
            // The indicator must still be online before the loss.
            offlineIndicator.offline shouldBe false

            // The instant at which connectivity is lost.
            val lossInstant = clock.now()
            connectivity.drive(ConnectivityStatus.UNSATISFIED)
            // Let the engine react: setOffline(true).
            testScheduler.runCurrent()

            val flipInstant = withClue("offline indicator must flip on connectivity loss (Req 2.8)") {
                offlineFlipInstant.get() ?: error("offline indicator did not flip on connectivity loss")
            }
            offlineIndicator.offline shouldBe true

            val elapsed = Duration.between(lossInstant, flipInstant)
            elapsed shouldBeGreaterThanOrEqualTo Duration.ZERO
            withClue("offline indicator must flip within 5s of loss (Req 2.8); elapsed ${elapsed.toMillis()}ms") {
                elapsed shouldBeLessThanOrEqualTo OFFLINE_INDICATOR_DEADLINE
            }

            observer.cancel()
            monitor.cancel()
        }
    }

    /**
     * Req 7.6 — a WorkManager job resumes across process death. Asserted at the
     * seam level (see file header): a record left PENDING by a half-finished
     * cycle survives in the durable queue and is resumed by a cycle built on a
     * runner/repository RECONSTRUCTED over the SAME store (a fresh "process").
     */
    "a record left PENDING survives a simulated process death and is resumed by a new cycle (Req 7.6)" {
        runTest {
            val clock = ControllableSyncClock(Instant.ofEpochSecond(1_700_000_000L))

            // One pending record representing enqueued, not-yet-synced work.
            val pending = SyncableRecord(
                recordId = UUID.randomUUID(),
                type = SyncableRecordType.WELL_TEST,
                sync = SyncMetadata(
                    syncState = SyncState.PENDING,
                    retryCount = 0,
                    createdAt = Instant.ofEpochSecond(1_600_000_000L),
                    lastAttemptAt = null,
                    remoteId = null,
                ),
            )
            // The DURABLE store — the thing WorkManager's guarantee hangs on. It
            // outlives the "process": both the pre-death and post-death runners
            // are views over this one object.
            val store = InMemorySyncStore(listOf(pending))

            // --- "Process 1": a cycle begins but the push FAILS (connectivity
            // flapped) and the "process is killed" before any retry. The record
            // is left PENDING in the store (Req 2.4 — no data loss). ---
            run {
                val repo1: SyncRepository = InMemorySyncRepository(store)
                val recorder1 = PushRecorder(clock)
                val runner1 = SyncCycleRunner(
                    syncRepository = repo1,
                    pushClient = FailingSyncPushClient(recorder1),
                    blobUploadClient = RecordingBlobUploadClient(recorder1),
                    blobSource = StubEscalationBlobSource(emptyMap()),
                    clock = clock,
                    batchSize = 50,
                )
                runner1.runSyncCycle()

                // The push was attempted but failed; the record stays PENDING
                // (retryCount advanced, below the 5-failure FAILED threshold).
                recorder1.pushedIds() shouldContainExactly listOf(pending.recordId)
                store.get(pending.recordId)!!.sync.syncState shouldBe SyncState.PENDING
            }

            // --- Simulate process death: discard runner1/repo1 entirely. The
            // enqueued WorkManager job would be re-run in a fresh process; we
            // model that by RECONSTRUCTING the repository and runner over the
            // SAME durable store. ---
            // Advance the virtual clock past the record's backoff window so the
            // reconstructed cycle is eligible to retry it immediately.
            clock.advance(Duration.ofSeconds(600))

            // --- "Process 2": a fresh cycle over the same store. The still-
            // PENDING record is returned by nextPendingBatch and resumed. ---
            run {
                val repo2: SyncRepository = InMemorySyncRepository(store)

                // The durable-queue invariant: the pending work is still visible
                // to a freshly-built repository (this is what makes WorkManager's
                // cross-process-death re-run meaningful — the work is not lost).
                val batch = repo2.nextPendingBatch(50)
                withClue("the PENDING record must survive the simulated process death in the durable queue (Req 7.6)") {
                    batch.map { it.recordId } shouldContainExactly listOf(pending.recordId)
                }

                val recorder2 = PushRecorder(clock)
                val runner2 = SyncCycleRunner(
                    syncRepository = repo2,
                    pushClient = RecordingSyncPushClient(recorder2),
                    blobUploadClient = RecordingBlobUploadClient(recorder2),
                    blobSource = StubEscalationBlobSource(emptyMap()),
                    clock = clock,
                    batchSize = 50,
                )
                runner2.runSyncCycle()

                // The resumed cycle pushed and completed the record.
                recorder2.pushedIds() shouldContainExactly listOf(pending.recordId)
                withClue("the resumed cycle must complete the record (Req 7.6)") {
                    store.get(pending.recordId)!!.sync.syncState shouldBe SyncState.SYNCHRONIZED
                }
            }
        }
    }
}) {
    companion object {
        /** Req 2.2 — sync must begin within 60s of connectivity restore. */
        val CONNECTIVITY_TRIGGER_DEADLINE: Duration = Duration.ofSeconds(60)

        /** Req 2.8 — the offline indicator must flip within 5s of loss. */
        val OFFLINE_INDICATOR_DEADLINE: Duration = Duration.ofSeconds(5)

        /** A fixed set of pending records across record kinds for the trigger cases. */
        private fun seedPendingRecords(): List<SyncableRecord> {
            val createdBase = Instant.ofEpochSecond(1_600_000_000L)
            return listOf(
                SyncableRecord(
                    recordId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    type = SyncableRecordType.WELL_TEST,
                    sync = SyncMetadata(
                        syncState = SyncState.PENDING,
                        retryCount = 0,
                        createdAt = createdBase,
                    ),
                ),
                SyncableRecord(
                    recordId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    type = SyncableRecordType.WELLHEAD_LOG,
                    sync = SyncMetadata(
                        syncState = SyncState.PENDING,
                        retryCount = 0,
                        createdAt = createdBase.plusSeconds(1),
                    ),
                ),
            )
        }

        /** No escalations in the seed set, so the blob source is never queried. */
        private fun seedEscalationsById(): Map<UUID, Escalation> = emptyMap()
    }
}

// ---------------------------------------------------------------------------
// Controllable clock (fixed virtual time, advanceable on command)
// ---------------------------------------------------------------------------

/**
 * A [SyncClock] reporting a fixed virtual instant, advanceable only via
 * [advance]. [sleep] is a no-op so the engine never actually waits; timing
 * assertions read [now] instead. Thread-safe so it can be read from the engine's
 * monitor coroutine and the test concurrently.
 */
private class ControllableSyncClock(start: Instant) : SyncClock {
    private val current = AtomicReference(start)

    override fun now(): Instant = current.get()

    /** Advance virtual time by [duration]. */
    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }

    override suspend fun sleep(seconds: Double) {
        // No real waiting: timing is asserted on the virtual clock.
    }
}

// ---------------------------------------------------------------------------
// Drivable connectivity provider (fake ConnectivityManager.NetworkCallback)
// ---------------------------------------------------------------------------

/**
 * A fake [ConnectivityProvider] standing in for ConnectivityManager.NetworkCallback.
 * Its [statusStream] is backed by a replay-1 shared flow seeded with the current
 * status, so a new collector first sees the current status (matching the real
 * provider's contract) and then every value driven via [drive]. [currentStatus]
 * reflects the latest driven value so a cycle that reads it behaves correctly.
 */
private class DrivableConnectivityProvider(initial: ConnectivityStatus) : ConnectivityProvider {
    private val state = AtomicReference(initial)
    private val flow = MutableSharedFlow<ConnectivityStatus>(
        replay = 1,
        extraBufferCapacity = 8,
    ).apply { tryEmit(initial) }

    override val currentStatus: ConnectivityStatus get() = state.get()

    override fun statusStream(): Flow<ConnectivityStatus> = flow

    /** Drive a connectivity transition exactly as a real NetworkCallback would. */
    fun drive(status: ConnectivityStatus) {
        state.set(status)
        check(flow.tryEmit(status)) { "connectivity transition buffer overflow" }
    }
}

// ---------------------------------------------------------------------------
// Recording SyncWorkScheduler (stamps the virtual clock at enqueue)
// ---------------------------------------------------------------------------

/**
 * A [SyncWorkScheduler] that records the virtual-clock instant of each enqueue.
 * The engine's sole reaction to a connectivity restore is
 * [enqueueImmediateSync], so observing it is how we assert a sync was triggered
 * within 60s (Req 2.2) without a real WorkManager.
 */
private class RecordingSyncWorkScheduler(private val clock: SyncClock) : SyncWorkScheduler {
    private val firstEnqueue = AtomicReference<Instant?>(null)
    private val enqueueCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun enqueueImmediateSync() {
        enqueueCount.incrementAndGet()
        firstEnqueue.compareAndSet(null, clock.now())
    }

    override fun ensurePeriodicSync() {
        // Not exercised by these cases; the fallback schedule is irrelevant to
        // the connectivity-trigger timing under test.
    }

    fun firstEnqueueInstant(): Instant? = firstEnqueue.get()
}

// ---------------------------------------------------------------------------
// Map-backed durable store + repository view
// ---------------------------------------------------------------------------

/**
 * A persistent, map-backed store of syncable records. It stands in for the
 * Room-backed local store and — critically for the process-death seam (Req 7.6)
 * — OUTLIVES any single repository/runner: reconstructing a repository over the
 * same store models a fresh process re-attaching to the durable queue.
 */
private class InMemorySyncStore(seed: List<SyncableRecord>) {
    private val records: MutableMap<UUID, SyncableRecord> =
        seed.associateByTo(linkedMapOf()) { it.recordId }

    @Synchronized
    fun pendingOldestFirst(limit: Int): List<SyncableRecord> =
        records.values
            .filter { it.sync.syncState == SyncState.PENDING }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    @Synchronized
    fun get(id: UUID): SyncableRecord? = records[id]

    @Synchronized
    fun markSynchronized(id: UUID, remoteId: String) {
        val current = records[id] ?: return
        records[id] = current.copy(
            sync = current.sync.copy(syncState = SyncState.SYNCHRONIZED, remoteId = remoteId),
        )
    }

    @Synchronized
    fun recordFailure(id: UUID) {
        val current = records[id] ?: return
        val nextRetry = current.sync.retryCount + 1
        val nextState = if (nextRetry >= 5) SyncState.FAILED else SyncState.PENDING
        records[id] = current.copy(
            sync = current.sync.copy(retryCount = nextRetry, syncState = nextState),
        )
    }

    @Synchronized
    fun manualRetry(id: UUID) {
        val current = records[id] ?: return
        records[id] = current.copy(
            sync = current.sync.copy(syncState = SyncState.PENDING, retryCount = 0),
        )
    }

    @Synchronized
    fun syncState(id: UUID): SyncState = records[id]?.sync?.syncState ?: SyncState.PENDING
}

/**
 * A [SyncRepository] that is a thin VIEW over an [InMemorySyncStore]. Its
 * nextPendingBatch reproduces the real Room-backed query shape (PENDING, ORDER
 * BY createdAt ASC, excluding FAILED — Req 2.1). Because it holds no state of its
 * own, discarding it and building a new one over the same store models a process
 * restart over a durable queue.
 */
private class InMemorySyncRepository(private val store: InMemorySyncStore) : SyncRepository {
    override suspend fun nextPendingBatch(limit: Int): List<SyncableRecord> =
        store.pendingOldestFirst(limit)

    override suspend fun markSynchronized(recordId: UUID, remoteId: String) =
        store.markSynchronized(recordId, remoteId)

    override suspend fun recordFailure(recordId: UUID) = store.recordFailure(recordId)

    override suspend fun manualRetry(recordId: UUID) = store.manualRetry(recordId)

    override fun observeSyncState(recordId: UUID): Flow<SyncState> =
        kotlinx.coroutines.flow.flowOf(store.syncState(recordId))
}

// ---------------------------------------------------------------------------
// Push recorder + recording/failing clients
// ---------------------------------------------------------------------------

/** Records, in push order, the ids pushed, stamping the first push's virtual instant. */
private class PushRecorder(private val clock: SyncClock) {
    private val ids = mutableListOf<UUID>()
    private var first: Instant? = null

    @Synchronized
    fun record(id: UUID) {
        ids.add(id)
        if (first == null) first = clock.now()
    }

    @Synchronized
    fun pushedIds(): List<UUID> = ids.toList()

    @Synchronized
    fun firstPushInstant(): Instant? = first
}

/** A push client that records each push and acknowledges it. */
private class RecordingSyncPushClient(private val recorder: PushRecorder) : SyncPushClient {
    override suspend fun push(record: SyncableRecord): SyncPushResult {
        recorder.record(record.recordId)
        return SyncPushResult.Acknowledged(remoteId = "remote-${record.recordId}")
    }
}

/** A blob-upload client that records each escalation push and acknowledges it. */
private class RecordingBlobUploadClient(private val recorder: PushRecorder) : BlobUploadClient {
    override suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult {
        recorder.record(escalation.id)
        return SyncPushResult.Acknowledged(remoteId = "remote-${escalation.id}")
    }
}

/**
 * A push client that records the attempt but reports a retryable failure, so the
 * record stays PENDING (Req 2.4). Used by the process-death case to model a
 * cycle that began but could not finish before the "process" died.
 */
private class FailingSyncPushClient(private val recorder: PushRecorder) : SyncPushClient {
    override suspend fun push(record: SyncableRecord): SyncPushResult {
        recorder.record(record.recordId)
        return SyncPushResult.RetryableFailure("simulated connectivity drop")
    }
}

/** Resolves escalations by id for the runner's escalation path (unused by these seeds). */
private class StubEscalationBlobSource(
    private val escalationsById: Map<UUID, Escalation>,
) : EscalationBlobSource {
    override suspend fun escalationWithBlob(id: UUID): EscalationBlob? {
        val escalation = escalationsById[id] ?: return null
        return EscalationBlob(escalation = escalation, blobPath = "/dev/null/$id.jpg")
    }
}
