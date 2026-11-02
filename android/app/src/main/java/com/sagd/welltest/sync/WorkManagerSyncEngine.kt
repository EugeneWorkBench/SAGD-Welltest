/*
 * WorkManager-backed SyncEngine.
 *
 * The concrete [com.sagd.welltest.domain.SyncEngine] for Android. It owns the
 * two connectivity-driven behaviors the design requires and delegates the
 * actual push loop to the platform-agnostic [SyncCycleRunner]:
 *
 *   - onConnectivityAvailable(): enqueue the unique WorkManager sync work
 *     constrained to NetworkType.CONNECTED so a cycle begins within 60s of
 *     connectivity restoration and survives process death (Req 2.2, 7.6). This
 *     mirrors the iOS engine scheduling an immediate cycle on a satisfied path.
 *   - runSyncCycle(): delegate to [SyncCycleRunner.runSyncCycle]. This is what
 *     the [SyncWorker] invokes once WorkManager grants execution.
 *   - uploadPhotoBlob(escalation): push a single escalation (JSON + photo) via
 *     the multipart [com.sagd.welltest.network.BlobUploadClient] and advance its
 *     state, mirroring the iOS background-session blob upload (Req 7.6).
 *
 * A background coroutine observes the connectivity stream to flip the global
 * offline indicator within 5s of loss and to kick a cycle on restore (Req 2.8).
 * Start it via [startMonitoring]; the app wires this in the graph-composition
 * task.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.SyncEngine
import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.network.BlobUploadClient
import com.sagd.welltest.network.SyncPushResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * @param cycleRunner the platform-agnostic push loop this engine delegates to.
 * @param syncRepository used by [uploadPhotoBlob] to advance an escalation's state.
 * @param blobUploadClient multipart uploader for a single-escalation push.
 * @param blobSource resolves an escalation and its photo blob path by id.
 * @param connectivityProvider source of connectivity transitions.
 * @param offlineIndicator the global offline indicator the UI observes.
 * @param workScheduler enqueues/cancels the WorkManager sync work.
 */
class WorkManagerSyncEngine(
    private val cycleRunner: SyncCycleRunner,
    private val syncRepository: SyncRepository,
    private val blobUploadClient: BlobUploadClient,
    private val blobSource: EscalationBlobSource,
    private val connectivityProvider: ConnectivityProvider,
    private val offlineIndicator: OfflineIndicatorPublishing,
    private val workScheduler: SyncWorkScheduler,
) : SyncEngine {

    override suspend fun onConnectivityAvailable() {
        // Enqueue the unique sync work constrained to CONNECTED. WorkManager
        // runs it within its scheduling window (well under 60s when a network
        // is present) and guarantees it across process death (Req 2.2, 7.6).
        workScheduler.enqueueImmediateSync()
    }

    override suspend fun runSyncCycle() {
        cycleRunner.runSyncCycle()
    }

    override suspend fun uploadPhotoBlob(escalation: Escalation) {
        val blob = blobSource.escalationWithBlob(escalation.id)
            ?: run {
                // Blob unresolvable: treat as a failed attempt so the escalation
                // is not acknowledged and advances toward FAILED after retries.
                syncRepository.recordFailure(escalation.id)
                return
            }
        when (val result = blobUploadClient.upload(blob.escalation, blob.blobPath)) {
            is SyncPushResult.Acknowledged ->
                syncRepository.markSynchronized(escalation.id, result.remoteId)

            is SyncPushResult.RetryableFailure,
            is SyncPushResult.NonRetryableFailure,
            -> syncRepository.recordFailure(escalation.id)
        }
    }

    /**
     * Observe connectivity transitions on [scope]: flip the offline indicator
     * on loss (within 5s — Req 2.8) and enqueue a sync on restore (Req 2.2).
     * Returns the launched [Job] so the caller can cancel monitoring.
     */
    fun startMonitoring(scope: CoroutineScope): Job =
        scope.launch {
            connectivityProvider.statusStream().collect { status ->
                when (status) {
                    ConnectivityStatus.SATISFIED -> {
                        offlineIndicator.setOffline(false)
                        onConnectivityAvailable()
                    }

                    ConnectivityStatus.UNSATISFIED ->
                        offlineIndicator.setOffline(true)
                }
            }
        }
}
