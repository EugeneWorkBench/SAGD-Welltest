//
//  Sync metadata and sync state
//
//  Every syncable record embeds `SyncMetadata`, giving each record its own
//  bounded per-record sync-state machine. `createdAt` is the single ordering
//  key for oldest-first push; `retryCount` is bounded at 5, isolating a poison
//  record from blocking the queue.
//
//  Introduced by task 1.2.

import Foundation

/// The sync-state machine state carried by every syncable record.
///
/// Transitions (realized by the sync engine in later tasks):
/// `pending -> synchronized` on cloud acknowledgment;
/// `pending -> pending` on failure while `retryCount < 5`;
/// `pending -> failed` on the 5th consecutive failure;
/// `failed -> pending` only via operator manual retry.
public enum SyncState: String, Codable, Sendable, CaseIterable, Hashable {
    /// Created locally and awaiting (or retrying) cloud acknowledgment.
    case pending
    /// Acknowledged by the cloud backend.
    case synchronized
    /// Permanently failed after the retry limit; excluded from auto batches
    /// until an operator-triggered manual retry.
    case failed
}

/// The maximum number of consecutive failed sync attempts before a record
/// transitions to `.failed` and automatic retries stop.
public let syncMaxRetryCount: Int = 5

/// Sync-state metadata embedded uniformly in every syncable record so the sync
/// engine can treat the push queue as one homogeneous stream.
public struct SyncMetadata: Codable, Sendable, Hashable {
    /// Current sync-state machine state.
    public var syncState: SyncState
    /// Consecutive failed attempts, 0...`syncMaxRetryCount`. Sync fails
    /// permanently at `syncMaxRetryCount`.
    public var retryCount: Int
    /// Record creation time (UTC); drives oldest-first push ordering.
    public var createdAt: Date
    /// Timestamp of the last push attempt, used for backoff scheduling.
    public var lastAttemptAt: Date?
    /// Server-assigned identifier set on cloud acknowledgment.
    public var remoteId: String?

    public init(
        syncState: SyncState = .pending,
        retryCount: Int = 0,
        createdAt: Date = Date(),
        lastAttemptAt: Date? = nil,
        remoteId: String? = nil
    ) {
        self.syncState = syncState
        self.retryCount = retryCount
        self.createdAt = createdAt
        self.lastAttemptAt = lastAttemptAt
        self.remoteId = remoteId
    }
}
