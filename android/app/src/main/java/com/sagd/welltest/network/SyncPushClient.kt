/*
 * Sync push client.
 *
 * The abstract contract the sync engine depends on to push a single record to
 * the Cloud_Backend. Defining this as an interface (rather than coupling the
 * engine to Retrofit/OkHttp) keeps the core cycle unit-testable: tests inject a
 * recording stub, and the concrete HTTP client is supplied later (task 23). The
 * engine never knows an endpoint, a header, or a status code — only whether a
 * push was acknowledged, retryably failed, or non-retryably failed. Mirrors the
 * iOS `SyncPushClient` protocol exactly.
 *
 * Idempotency (design.md "Push ordering and acknowledgment"): the engine always
 * identifies a record by its client-generated `id`. A retried push of an
 * already-acknowledged record is recognized by the backend via that `id` and
 * returns an acknowledgment without creating a duplicate — so a redundant push
 * is safe and simply resolves to [SyncPushResult.Acknowledged] again.
 *
 * Introduced by task 16.1. The concrete REST client is implemented by task 23.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.SyncableRecord

/**
 * The outcome of pushing one record to the Cloud_Backend, classified into the
 * three cases the sync-state machine cares about. Mirrors the iOS
 * `SyncPushResult` enum.
 *
 * The engine's reaction to each case:
 *   - [Acknowledged]         2xx; mark the record SYNCHRONIZED and store the
 *                            returned remoteId (Req 2.3).
 *   - [RetryableFailure]     a 5xx or a transport error / timeout; the record
 *                            stays PENDING, its retryCount increments, and it is
 *                            retried on a later connectivity event with backoff
 *                            (Req 2.4). The 5th such failure flips it to FAILED
 *                            (Req 2.5).
 *   - [NonRetryableFailure]  a definitive client error (e.g. 409 conflict from a
 *                            stale duplicate, or 422 validation): retrying cannot
 *                            succeed, so the engine treats it as a failed attempt
 *                            as well (counting toward the retry limit) rather than
 *                            looping on it forever.
 */
sealed interface SyncPushResult {
    /**
     * The backend acknowledged the record (2xx). Carries the server-assigned
     * remote identifier echoed back for this record's client `id`.
     */
    data class Acknowledged(val remoteId: String) : SyncPushResult

    /** A transient failure (5xx, timeout, or transport error). Safe to retry. */
    data class RetryableFailure(val reason: String) : SyncPushResult

    /**
     * A definitive client-side rejection (e.g. 409 / 422). Retrying the same
     * payload cannot succeed.
     */
    data class NonRetryableFailure(val reason: String) : SyncPushResult
}

/**
 * Pushes a single syncable record to the Cloud_Backend.
 *
 * The engine passes the record *descriptor* ([SyncableRecord]: id + type); the
 * concrete client (task 23) is responsible for loading that record's payload
 * from the local store and addressing the correct endpoint for its type.
 * Keeping the descriptor as the input lets the engine drive the queue without
 * knowing any payload shape.
 *
 * Implementations MUST be idempotent with respect to the record `id`: pushing
 * the same `id` twice yields [SyncPushResult.Acknowledged] both times and never
 * creates a duplicate on the backend.
 */
interface SyncPushClient {
    /**
     * Push the record identified by [record] to the Cloud_Backend.
     *
     * @param record the descriptor (id, type, sync) of the record to push. The
     *   client loads the payload for this id/type.
     * @return the classified outcome the engine advances the state machine on.
     */
    suspend fun push(record: SyncableRecord): SyncPushResult
}

/**
 * Uploads an escalation's photo blob as a multipart request.
 *
 * Separated from [SyncPushClient] because escalation sync is a multipart upload
 * (JSON part + the photo bytes streamed from the blob file) that, on Android,
 * runs inside the `CoroutineWorker` which WorkManager guarantees to completion
 * (design.md "Photo / blob upload", Req 7.6). The engine marks an escalation
 * SYNCHRONIZED only after this upload — including the photo — is acknowledged.
 */
interface BlobUploadClient {
    /**
     * Upload the escalation (its JSON fields plus the photo at [blobPath]) as a
     * multipart request.
     *
     * @param escalation the escalation descriptor whose fields form the JSON
     *   part. Identified by its client `id` for idempotency.
     * @param blobPath the absolute filesystem path of the photo blob to stream.
     * @return the classified outcome, mirroring [SyncPushClient.push].
     */
    suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult
}
