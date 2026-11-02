/*
 * SyncWorker — the CoroutineWorker shell around the sync cycle.
 *
 * WorkManager instantiates this worker (via its default WorkerFactory) when the
 * CONNECTED constraint is satisfied for either the connectivity-triggered
 * unique work or the periodic fallback (see [SyncWorkScheduler]). The worker
 * holds no sync logic itself: it resolves the process-wide [SyncCycleRunner]
 * from [SyncWorkerDependencies] and delegates to [SyncCycleRunner.runSyncCycle].
 * This keeps the state machine in the testable plain class while WorkManager
 * provides guaranteed, process-death-surviving background execution (Req 7.6).
 *
 * Result mapping:
 *   - dependencies not yet wired  -> Result.retry() (the graph composes shortly
 *     after launch; retry lets WorkManager run again once it is ready).
 *   - cycle completes             -> Result.success(). Per-record retry/backoff
 *     is handled inside the cycle via the SyncRepository, so a cycle that left
 *     records pending still "succeeded" as a worker run; the next connectivity
 *     event or the periodic schedule re-drives it.
 *   - cycle throws                -> Result.retry() so WorkManager reruns with
 *     its own exponential backoff.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = SyncWorkerDependencies.cycleRunner
            ?: return Result.retry() // graph not composed yet; try again later

        return try {
            runner.runSyncCycle()
            Result.success()
        } catch (t: Throwable) {
            // Let WorkManager reschedule with its own backoff; per-record state
            // was already advanced inside the cycle for records it reached.
            Result.retry()
        }
    }
}

/**
 * Process-wide handle to the [SyncCycleRunner] the [SyncWorker] delegates to.
 *
 * WorkManager constructs [SyncWorker] with only a Context and params, so it
 * cannot receive constructor-injected dependencies via the default factory.
 * The app's graph-composition step (task 23.2) sets [cycleRunner] once at
 * startup; the worker reads it here. Kept as a simple `@Volatile` holder rather
 * than a DI framework to match the project's hand-wired graph style.
 *
 * Tests exercise [SyncCycleRunner] directly and do not go through this holder.
 */
object SyncWorkerDependencies {
    @Volatile
    var cycleRunner: SyncCycleRunner? = null
}
