/*
 * Sync work scheduler.
 *
 * Owns how sync cycles are scheduled on WorkManager, kept behind an interface so
 * the engine ([WorkManagerSyncEngine]) can be unit-tested with a recording fake
 * and so the real WorkManager wiring lives in one place.
 *
 * Two schedules, matching the design ("Connectivity and background execution"):
 *   - enqueueImmediateSync(): a unique, one-shot [OneTimeWorkRequest] constrained
 *     to NetworkType.CONNECTED, enqueued when connectivity becomes available so a
 *     cycle starts within 60s (Req 2.2). KEEP policy coalesces rapid connectivity
 *     flaps into a single pending run. WorkManager's own exponential backoff
 *     complements the per-record backoff for transient worker failures.
 *   - ensurePeriodicSync(): a unique [PeriodicWorkRequest] (also CONNECTED-
 *     constrained) as a fallback so records still drain even if no connectivity
 *     callback fires (e.g. the app was launched already-online). KEEP preserves
 *     an existing schedule across launches.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import androidx.work.BackoffPolicy as WorkBackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Schedules the engine's sync work. Backed by WorkManager in production. */
interface SyncWorkScheduler {
    /** Enqueue a unique one-shot sync, constrained to a connected network (Req 2.2). */
    fun enqueueImmediateSync()

    /** Ensure the periodic fallback sync is scheduled (keeps draining the queue). */
    fun ensurePeriodicSync()
}

/**
 * WorkManager-backed [SyncWorkScheduler].
 *
 * @param workManager the process WorkManager (`WorkManager.getInstance(context)`).
 * @param periodicInterval how often the fallback periodic sync runs. WorkManager
 *   clamps this to a 15-minute minimum.
 */
class WorkManagerSyncWorkScheduler(
    private val workManager: WorkManager,
    private val periodicInterval: Duration = Duration.ofMinutes(15),
) : SyncWorkScheduler {

    private val connectedConstraints: Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    override fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(
                WorkBackoffPolicy.EXPONENTIAL,
                DEFAULT_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        // KEEP coalesces rapid connectivity flaps into one pending run.
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun ensurePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            periodicInterval.toMinutes(),
            TimeUnit.MINUTES,
        )
            .setConstraints(connectedConstraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        /** Unique name for the connectivity-triggered one-shot sync. */
        const val IMMEDIATE_WORK_NAME = "com.sagd.welltest.sync.immediate"

        /** Unique name for the periodic fallback sync. */
        const val PERIODIC_WORK_NAME = "com.sagd.welltest.sync.periodic"

        /** WorkManager retry backoff floor for a failed worker run. */
        const val DEFAULT_BACKOFF_SECONDS = 30L
    }
}
