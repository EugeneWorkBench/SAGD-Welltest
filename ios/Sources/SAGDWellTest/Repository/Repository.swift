//
//  Repository layer
//
//  The only component the UI uses to read and write data. Wraps the Local
//  Store, assigns sync metadata on write, and signals the sync engine that new
//  work exists. Exposes observable queries so the UI updates reactively.
//
//  This file defines the repository protocols (task 1.2). Concrete GRDB-backed
//  implementations are provided in task 5.

import Foundation

/// Namespace marker for the Repository layer.
enum RepositoryLayer {}

// MARK: - Syncable record

/// The kind of record held in the uniform push queue. Lets the sync engine
/// select and address records without knowing their concrete payload.
public enum SyncableRecordKind: String, Codable, Sendable, Hashable {
    case wellTest
    case wellheadLog
    case subcoolReading
    case escalation
}

/// A type-erased view of a record in the push queue. The sync engine orders
/// these by `sync.createdAt` ascending (oldest first) and advances each one's
/// state machine; `kind` and `id` let it route the push to the right endpoint.
public struct SyncableRecord: Sendable, Equatable, Hashable, Identifiable {
    /// The underlying record's primary key.
    public var id: UUID
    /// Which concrete record type this is.
    public var kind: SyncableRecordKind
    /// Embedded sync-state metadata (ordering key, retry count, remote id).
    public var sync: SyncMetadata

    public init(id: UUID, kind: SyncableRecordKind, sync: SyncMetadata) {
        self.id = id
        self.kind = kind
        self.sync = sync
    }
}

// MARK: - Repository protocols

/// Reads well reference data and associated records from the local store.
public protocol WellRepository: Sendable {
    /// The well with the given id, or `nil` if it is not in the local store.
    func getWell(id: String) async -> Well?
    /// Well tests for a well, ordered most-recent first.
    func getWellTests(wellId: String) async -> [WellTest]
    /// Open maintenance tasks for a well (those with `status == .open`).
    func getOpenMaintenanceTasks(wellId: String) async -> [MaintenanceTask]
}

/// Persists wellhead logs and well tests transactionally.
public protocol LogRepository: Sendable {
    /// Save a wellhead log. Distinguishes `storageFull`; a `failure` rolls back
    /// and leaves prior state untouched.
    func saveWellheadLog(_ log: WellheadLog) async -> SaveResult
    /// Save a well test with the same transactional semantics.
    func saveWellTest(_ test: WellTest) async -> SaveResult
}

/// Persists escalations and their photo blobs transactionally.
public protocol EscalationRepository: Sendable {
    /// Save an escalation and its photo. The blob file is written before the
    /// referencing row; distinguishes `storageFull`.
    func saveEscalation(_ escalation: Escalation, photoData: Data) async -> SaveResult
}

/// Drives the sync queue: batching, state transitions, and observation.
public protocol SyncRepository: Sendable {
    /// The next batch of pending records, ordered by `createdAt` ascending
    /// (oldest first), up to `limit`. Excludes `failed` records.
    func nextPendingBatch(limit: Int) async -> [SyncableRecord]
    /// Mark a record synchronized and store its server-assigned remote id.
    func markSynchronized(recordId: UUID, remoteId: String) async
    /// Record a failed attempt: increments `retryCount`, and sets `failed` at
    /// `syncMaxRetryCount`.
    func recordFailure(recordId: UUID) async
    /// Reset a `failed` record to `pending` for a manual retry.
    func manualRetry(recordId: UUID) async
    /// Observe a record's sync state as it changes.
    func observeSyncState(recordId: UUID) -> AsyncStream<SyncState>
}
