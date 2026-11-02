//
//  Sync push client
//
//  The abstract contract the sync engine depends on to push a single record to
//  the Cloud_Backend. Defining this as a protocol (rather than coupling the
//  engine to `URLSession`) keeps the engine unit-testable: tests inject a
//  recording stub, and the concrete `URLSession` REST client is supplied later
//  (task 22). The engine never knows an endpoint, a header, or a status code —
//  only whether a push was acknowledged, retryably failed, or non-retryably
//  failed.
//
//  Idempotency (design "Push ordering and acknowledgment"): the engine always
//  identifies a record by its client-generated `id`. A retried push of an
//  already-acknowledged record is recognized by the backend via that `id` and
//  returns an acknowledgment without creating a duplicate — so a redundant push
//  is safe and simply resolves to `.acknowledged` again.
//
//  Introduced by task 15.1. The concrete REST client is implemented by task 22.

import Foundation

/// The outcome of pushing one record to the Cloud_Backend, classified into the
/// three cases the sync-state machine cares about.
///
/// The engine's reaction to each case:
///   * `.acknowledged`  — 2xx; mark the record `synchronized` and store the
///                        returned `remoteId` (Req 2.3).
///   * `.retryableFailure` — a 5xx or a transport error / timeout; the record
///                        stays `pending`, its `retryCount` increments, and it
///                        is retried on a later connectivity event with backoff
///                        (Req 2.4). The 5th such failure flips it to `failed`
///                        (Req 2.5).
///   * `.nonRetryableFailure` — a definitive client error (e.g. 409 conflict
///                        from a stale duplicate, or 422 validation): retrying
///                        cannot succeed, so the engine treats it as a failed
///                        attempt as well (counting toward the retry limit)
///                        rather than looping on it forever.
public enum SyncPushResult: Sendable, Equatable {
    /// The backend acknowledged the record (2xx). Carries the server-assigned
    /// remote identifier echoed back for this record's client `id`.
    case acknowledged(remoteId: String)
    /// A transient failure (5xx, timeout, or transport error). Safe to retry.
    case retryableFailure(reason: String)
    /// A definitive client-side rejection (e.g. 409 / 422). Retrying the same
    /// payload cannot succeed.
    case nonRetryableFailure(reason: String)
}

/// Pushes a single syncable record to the Cloud_Backend.
///
/// The engine passes the record *descriptor* (`id` + `kind`); the concrete
/// client (task 22) is responsible for loading that record's payload from the
/// local store and addressing the correct endpoint for its `kind`. Keeping the
/// descriptor as the input lets the engine drive the queue without knowing any
/// payload shape.
///
/// Implementations MUST be idempotent with respect to the record `id`: pushing
/// the same `id` twice yields `.acknowledged` both times and never creates a
/// duplicate on the backend.
public protocol SyncPushClient: Sendable {
    /// Push the record identified by `record` to the Cloud_Backend.
    ///
    /// - Parameter record: The descriptor (`id`, `kind`, `sync`) of the record
    ///   to push. The client loads the payload for this `id`/`kind`.
    /// - Returns: The classified outcome the engine advances the state machine
    ///   on.
    func push(_ record: SyncableRecord) async -> SyncPushResult
}

/// Uploads an escalation's photo blob as a multipart request on a background
/// transfer session.
///
/// Separated from `SyncPushClient` because escalation sync is a multipart
/// upload (JSON part + the photo bytes streamed from the blob file) that, on
/// iOS, runs on a background `URLSession` so it survives app suspension (design
/// "Photo / blob upload", Req 7.6). The engine marks an escalation
/// `synchronized` only after this upload — including the photo — is
/// acknowledged.
public protocol BlobUploadClient: Sendable {
    /// Upload the escalation (its JSON fields plus the photo at `blobURL`) as a
    /// multipart request.
    ///
    /// - Parameters:
    ///   - escalation: The escalation descriptor whose fields form the JSON
    ///     part. Identified by its client `id` for idempotency.
    ///   - blobURL: The absolute file URL of the photo blob to stream.
    /// - Returns: The classified outcome, mirroring `SyncPushClient`.
    func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult
}
