/*
 * Sync cycle runner — the core per-record sync-state machine.
 *
 * This is the platform-agnostic heart of the Android sync engine: a plain class
 * with no Android, Room, or WorkManager dependency so it is unit- and
 * property-testable (tasks 16.2-16.7) exactly like the iOS engine. The
 * [SyncWorker] CoroutineWorker is a thin shell that delegates to [runSyncCycle].
 *
 * It implements the design's "Sync Engine" flow (design.md "Trigger and
 * ordering", "Push ordering and acknowledgment", "Retry and backoff") and
 * mirrors the iOS engine semantics:
 *
 *   1. Pull the pending batch oldest-first from [SyncRepository.nextPendingBatch]
 *      (ORDER BY createdAt ASC — Req 2.1). FAILED/SYNCHRONIZED rows are already
 *      excluded by the repository.
 *   2. For each record, if it is still inside its exponential-backoff window
 *      (keyed on lastAttemptAt + retryCount via [BackoffPolicy]) skip it this
 *      cycle; it is revisited on the next connectivity event (Req 2.4).
 *   3. Push the record via [SyncPushClient] (escalations via [BlobUploadClient]
 *      with their photo blob — Req 7.6). The push is idempotent on the record
 *      id, so a retried push of an already-acknowledged record is safe.
 *   4. Advance the state machine through the SyncRepository, which owns the
 *      transitions:
 *        - Acknowledged         -> markSynchronized(remoteId) (Req 2.3).
 *        - Retryable failure     -> recordFailure (retryCount++, FAILED at 5) (Req 2.4, 2.5).
 *        - Non-retryable failure -> recordFailure as well: a 409/422 cannot
 *          succeed on retry, so counting it toward the limit parks the poison
 *          record as FAILED rather than looping on it (design "Conflict
 *          handling"; iOS treats it identically).
 *
 * The runner never computes the next retryCount or FAILED transition itself:
 * that lives in [SyncRepository.recordFailure]/manualRetry so the state machine
 * has a single owner (matching the iOS engine, which also delegates to the
 * repository).
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.SyncableRecordType
import com.sagd.welltest.network.BlobUploadClient
import com.sagd.welltest.network.SyncPushClient
import com.sagd.welltest.network.SyncPushResult

/**
 * Drives one sync cycle over the pending push queue.
 *
 * @param syncRepository the push queue and per-record state-machine mutators.
 * @param pushClient pushes non-escalation records (test/log/subcool) to the
 *   Cloud_Backend; abstract so the concrete HTTP client (task 23) and the test
 *   recording stub are interchangeable.
 * @param blobUploadClient multipart uploader for escalations (JSON + photo).
 * @param blobSource resolves an escalation and its photo blob path by id.
 * @param backoffPolicy decides whether a previously-failed record's backoff
 *   window has elapsed.
 * @param clock source of "now" for backoff eligibility; injectable for tests.
 * @param batchSize max records pulled per cycle.
 */
class SyncCycleRunner(
    private val syncRepository: SyncRepository,
    private val pushClient: SyncPushClient,
    private val blobUploadClient: BlobUploadClient,
    private val blobSource: EscalationBlobSource,
    private val backoffPolicy: BackoffPolicy = ExponentialBackoffPolicy(),
    private val clock: SyncClock = SystemSyncClock(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {

    /**
     * Run one sync cycle: pull the batch oldest-first, push each eligible
     * record, and advance its state. Returns the number of records whose push
     * was acknowledged this cycle (useful to callers/tests; the state is
     * already persisted via the repository).
     *
     * A single cycle processes the records returned by one
     * [SyncRepository.nextPendingBatch] call. Records inside their backoff
     * window are skipped and picked up on a subsequent cycle.
     */
    suspend fun runSyncCycle(): SyncCycleOutcome {
        val batch = syncRepository.nextPendingBatch(batchSize)
        var acknowledged = 0
        var skipped = 0
        var failed = 0

        // The batch is already oldest-first (Req 2.1); preserve that order.
        for (record in batch) {
            // Respect the exponential backoff window keyed on lastAttemptAt.
            if (!backoffPolicy.isEligible(record.sync, clock.now())) {
                skipped++
                continue
            }

            when (val result = push(record)) {
                is SyncPushResult.Acknowledged -> {
                    // 2xx: mark synchronized and store the remote id (Req 2.3).
                    syncRepository.markSynchronized(record.recordId, result.remoteId)
                    acknowledged++
                }

                is SyncPushResult.RetryableFailure,
                is SyncPushResult.NonRetryableFailure,
                -> {
                    // Any failure counts as one attempt: the repository
                    // increments retryCount and flips to FAILED at the 5th
                    // (Req 2.4, 2.5, 7.7). The record stays in the store with
                    // its payload intact (Req 2.4 / no data loss).
                    syncRepository.recordFailure(record.recordId)
                    failed++
                }
            }
        }

        return SyncCycleOutcome(
            processed = batch.size,
            acknowledged = acknowledged,
            failed = failed,
            skipped = skipped,
        )
    }

    /**
     * Push one record through the appropriate client. Escalations use the
     * multipart blob uploader (JSON + photo — Req 7.6); every other record type
     * uses the JSON push client. Idempotency is guaranteed by the record id:
     * replaying a push of an already-acknowledged record resolves to
     * [SyncPushResult.Acknowledged] without creating a duplicate.
     */
    private suspend fun push(record: SyncableRecord): SyncPushResult =
        when (record.type) {
            SyncableRecordType.ESCALATION -> {
                val blob = blobSource.escalationWithBlob(record.recordId)
                if (blob == null) {
                    // The row exists in the queue but its blob can't be resolved
                    // (e.g. a missing file). Treat as a retryable failure so the
                    // record is not acknowledged; it will be reattempted and,
                    // after the retry limit, parked as FAILED rather than lost.
                    SyncPushResult.RetryableFailure("escalation blob unavailable")
                } else {
                    blobUploadClient.upload(blob.escalation, blob.blobPath)
                }
            }

            SyncableRecordType.WELL_TEST,
            SyncableRecordType.WELLHEAD_LOG,
            SyncableRecordType.SUBCOOL_READING,
            -> pushClient.push(record)
        }

    companion object {
        /** Default number of records pulled per cycle. */
        const val DEFAULT_BATCH_SIZE: Int = 50
    }
}

/**
 * Summary of one [SyncCycleRunner.runSyncCycle] pass. The authoritative state
 * is persisted via the repository; this is a lightweight report for callers and
 * tests.
 *
 * @property processed records returned by the batch query this cycle.
 * @property acknowledged records whose push was acknowledged (now SYNCHRONIZED).
 * @property failed records whose push failed this cycle (retryCount advanced).
 * @property skipped records left untouched because their backoff window had not
 *   elapsed.
 */
data class SyncCycleOutcome(
    val processed: Int,
    val acknowledged: Int,
    val failed: Int,
    val skipped: Int,
)
