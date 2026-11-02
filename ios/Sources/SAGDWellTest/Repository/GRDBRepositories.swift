//
//  GRDB-backed repositories
//
//  Concrete implementations of the repository protocols over the GRDB
//  `AppDatabase`. The repository layer is the only component the UI uses to
//  read and write data: it assigns sync metadata on write, performs every save
//  inside a single transaction (so a failure leaves prior persisted state
//  untouched — Req 1.4), rejects saves up front when free space is
//  insufficient (`storageFull` — Req 1.5), and signals the sync engine that new
//  pending work exists.
//
//  Implemented by task 5.1. Properties verified by tasks 5.2–5.6.

import Foundation
import GRDB

// MARK: - WellRepository

/// Reads well reference data and associated records from the local store.
///
/// All reads are pure queries against the store and never require the network
/// (Req 1.2, Req 6.5). `getWellTests` is ordered most-recent-first and
/// `getOpenMaintenanceTasks` returns only `status == .open` rows (Req 6.2).
public final class GRDBWellRepository: WellRepository {

    private let appDatabase: AppDatabase

    public init(appDatabase: AppDatabase) {
        self.appDatabase = appDatabase
    }

    public func getWell(id: String) async -> Well? {
        let writer = appDatabase.dbWriter
        let record = try? await writer.read { db in
            try WellRecord.fetchOne(db, key: id)
        }
        return record?.domain
    }

    /// Well tests for a well, ordered most-recent first by `capturedAt`
    /// (ties broken by `createdAt`, also descending) for a deterministic order.
    public func getWellTests(wellId: String) async -> [WellTest] {
        let writer = appDatabase.dbWriter
        let records = (try? await writer.read { db in
            try WellTestRecord
                .filter(Column("wellId") == wellId)
                .order(
                    Column("capturedAt").desc,
                    Column(Schema.SyncColumn.createdAt).desc
                )
                .fetchAll(db)
        }) ?? []
        return records.compactMap(\.domain)
    }

    /// Open maintenance tasks for a well — exactly those with
    /// `status == .open` — ordered newest-first for stable display.
    public func getOpenMaintenanceTasks(wellId: String) async -> [MaintenanceTask] {
        let writer = appDatabase.dbWriter
        let records = (try? await writer.read { db in
            try MaintenanceTaskRecord
                .filter(Column("wellId") == wellId)
                .filter(Column("status") == MaintenanceTaskStatus.open.rawValue)
                .order(Column("createdAt").desc)
                .fetchAll(db)
        }) ?? []
        return records.compactMap(\.domain)
    }
}

// MARK: - LogRepository

/// Persists wellhead logs and well tests transactionally.
///
/// Both saves share the same shape: a pre-write free-space check (Req 1.5),
/// then a single transaction that commits the record with `syncState =
/// pending`, `createdAt = now`, `retryCount = 0` (per the design's "Schema and
/// transactions"). A thrown I/O error rolls the transaction back and surfaces
/// as `failure`, leaving prior state untouched (Req 1.4); an out-of-space
/// error surfaces as `storageFull`.
public final class GRDBLogRepository: LogRepository {

    private let appDatabase: AppDatabase
    private let storageProbe: StorageProbe
    private let syncSignal: SyncWorkSignaling
    private let now: @Sendable () -> Date

    public init(
        appDatabase: AppDatabase,
        storageProbe: StorageProbe = SystemStorageProbe(),
        syncSignal: SyncWorkSignaling = NoopSyncWorkSignal(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.appDatabase = appDatabase
        self.storageProbe = storageProbe
        self.syncSignal = syncSignal
        self.now = now
    }

    public func saveWellheadLog(_ log: WellheadLog) async -> SaveResult {
        var log = log
        log.sync = freshPendingMetadata()
        let record = WellheadLogRecord(log)
        return await insert(record)
    }

    public func saveWellTest(_ test: WellTest) async -> SaveResult {
        var test = test
        test.sync = freshPendingMetadata()
        let record = WellTestRecord(test)
        return await insert(record)
    }

    // MARK: - Shared insert path

    /// Sync metadata for a freshly created record: committed `pending`, with
    /// `retryCount = 0` and `createdAt = now`. The ordering key is assigned
    /// authoritatively at save time (per the design's "Schema and
    /// transactions"); the caller's incoming `sync` values are discarded.
    private func freshPendingMetadata() -> SyncMetadata {
        SyncMetadata(
            syncState: .pending,
            retryCount: 0,
            createdAt: now(),
            lastAttemptAt: nil,
            remoteId: nil
        )
    }

    private func insert<R: PersistableRecord>(_ record: R) async -> SaveResult {
        // Pre-write free-space check: reject before touching the store so the
        // form data is retained and nothing is written (Req 1.5).
        if let available = storageProbe.availableCapacityBytes(),
           available < minimumFreeSpaceBytes {
            return .storageFull
        }

        let writer = appDatabase.dbWriter
        do {
            // A single transaction: either the row commits or the store is left
            // exactly as it was (Req 1.4). GRDB's `write` wraps the body in a
            // transaction that rolls back on any thrown error.
            try await writer.write { db in
                try record.insert(db)
            }
        } catch {
            return SaveErrorMapping.saveResult(for: error)
        }

        await syncSignal.enqueuePendingWork()
        return .success
    }
}
