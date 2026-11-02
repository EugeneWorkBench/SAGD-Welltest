//
//  GRDB-backed sync repository
//
//  Drives the uniform push queue across every syncable table. The four
//  syncable record types (`wellTest`, `wellheadLog`, `subcoolReading`,
//  `escalation`) each embed `SyncMetadata`, so the push queue is one
//  homogeneous stream ordered by `createdAt` ascending (oldest first — Req
//  2.1). State mutators advance each record's per-record state machine:
//
//    * `markSynchronized` — set `synchronized`, store `remoteId` (Req 2.3).
//    * `recordFailure`    — increment `retryCount`, stamp `lastAttemptAt`, and
//                           flip to `failed` at `syncMaxRetryCount` (Req 2.5).
//    * `manualRetry`      — reset a `failed` record to `pending`, clearing the
//                           retry count so it is eligible for automatic push
//                           again (Req 2.5, 7.7).
//    * `observeSyncState` — stream state changes via GRDB observation so the
//                           UI reflects them within 5 s (Req 2.7).
//
//  Records are keyed by UUID (stored as text) and may live in any of the four
//  tables; mutators locate the row by scanning the tables for the id. A
//  `failed` record is excluded from `nextPendingBatch` so a poison record never
//  blocks the queue.
//
//  Implemented by task 5.1.

import Foundation
import GRDB

/// The syncable tables and the record kind each maps to, in a single list so
/// the batch query and the mutators stay in agreement.
private struct SyncableTable {
    let name: String
    let kind: SyncableRecordKind
    /// Whether this table's `SyncMetadata` columns are nullable (true only for
    /// `subcoolReading`, whose rows may be held without ever being queued).
    let syncOptional: Bool
}

private let syncableTables: [SyncableTable] = [
    SyncableTable(name: Schema.Table.wellTest, kind: .wellTest, syncOptional: false),
    SyncableTable(name: Schema.Table.wellheadLog, kind: .wellheadLog, syncOptional: false),
    SyncableTable(name: Schema.Table.subcoolReading, kind: .subcoolReading, syncOptional: true),
    SyncableTable(name: Schema.Table.escalation, kind: .escalation, syncOptional: false),
]

public final class GRDBSyncRepository: SyncRepository {

    private let appDatabase: AppDatabase
    private let now: @Sendable () -> Date

    public init(
        appDatabase: AppDatabase,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.appDatabase = appDatabase
        self.now = now
    }

    // MARK: - Batching

    /// The next batch of pending records across all syncable tables, ordered by
    /// `createdAt` ascending (oldest first), up to `limit`. Excludes records in
    /// `failed` (and `synchronized`), so only `pending` work is returned
    /// (Req 2.1, Req 2.5 exclusion).
    public func nextPendingBatch(limit: Int) async -> [SyncableRecord] {
        guard limit > 0 else { return [] }
        let writer = appDatabase.dbWriter
        let records = (try? await writer.read { db -> [SyncableRecord] in
            var collected: [SyncableRecord] = []
            for table in syncableTables {
                // Only rows whose state is exactly `pending` are eligible.
                // For the optionally-synced subcool table, a NULL syncState
                // (never queued) is excluded by the equality filter.
                let rows = try Row.fetchAll(db, sql: """
                    SELECT id, \(Schema.SyncColumn.syncState) AS syncState,
                           \(Schema.SyncColumn.retryCount) AS retryCount,
                           \(Schema.SyncColumn.createdAt) AS createdAt,
                           \(Schema.SyncColumn.lastAttemptAt) AS lastAttemptAt,
                           \(Schema.SyncColumn.remoteId) AS remoteId
                    FROM \(table.name)
                    WHERE \(Schema.SyncColumn.syncState) = ?
                    """, arguments: [SyncState.pending.rawValue])
                for row in rows {
                    guard let record = Self.syncableRecord(from: row, kind: table.kind) else {
                        continue
                    }
                    collected.append(record)
                }
            }
            // Global oldest-first ordering across the merged tables. Ties
            // broken by id for determinism.
            collected.sort { lhs, rhs in
                if lhs.sync.createdAt != rhs.sync.createdAt {
                    return lhs.sync.createdAt < rhs.sync.createdAt
                }
                return lhs.id.uuidString < rhs.id.uuidString
            }
            return Array(collected.prefix(limit))
        }) ?? []
        return records
    }

    private static func syncableRecord(
        from row: Row,
        kind: SyncableRecordKind
    ) -> SyncableRecord? {
        guard let idString: String = row["id"],
              let uuid = UUID(uuidString: idString),
              let stateRaw: String = row["syncState"],
              let state = SyncState(rawValue: stateRaw),
              let createdAt: Date = row["createdAt"]
        else { return nil }

        let retryCount: Int = row["retryCount"] ?? 0
        let lastAttemptAt: Date? = row["lastAttemptAt"]
        let remoteId: String? = row["remoteId"]

        let sync = SyncMetadata(
            syncState: state,
            retryCount: retryCount,
            createdAt: createdAt,
            lastAttemptAt: lastAttemptAt,
            remoteId: remoteId
        )
        return SyncableRecord(id: uuid, kind: kind, sync: sync)
    }

    // MARK: - State transitions

    /// Mark a record synchronized and store its server-assigned remote id.
    /// Idempotent: re-marking an already-synchronized record is a no-op beyond
    /// refreshing `remoteId`, so a duplicate acknowledgment cannot regress the
    /// state (supports the sync-idempotency property).
    public func markSynchronized(recordId: UUID, remoteId: String) async {
        await mutate(recordId: recordId) { [now] db, table in
            try db.execute(sql: """
                UPDATE \(table.name)
                SET \(Schema.SyncColumn.syncState) = ?,
                    \(Schema.SyncColumn.remoteId) = ?,
                    \(Schema.SyncColumn.lastAttemptAt) = ?
                WHERE id = ?
                """, arguments: [
                    SyncState.synchronized.rawValue,
                    remoteId,
                    now(),
                    recordId.uuidString
                ])
        }
    }

    /// Record a failed attempt. Increments `retryCount`, stamps
    /// `lastAttemptAt`, and transitions the record to `failed` when the count
    /// reaches `syncMaxRetryCount` (Req 2.5). A record already `synchronized`
    /// or `failed` is left unchanged — only `pending` records accrue failures.
    public func recordFailure(recordId: UUID) async {
        await mutate(recordId: recordId) { [now] db, table in
            // Read the current state and count, then write the next state so
            // the 5th failure flips to `failed` deterministically.
            let row = try Row.fetchOne(db, sql: """
                SELECT \(Schema.SyncColumn.syncState) AS syncState,
                       \(Schema.SyncColumn.retryCount) AS retryCount
                FROM \(table.name) WHERE id = ?
                """, arguments: [recordId.uuidString])

            guard let row,
                  let stateRaw: String = row["syncState"],
                  let state = SyncState(rawValue: stateRaw),
                  state == .pending
            else { return }

            let currentRetry: Int = row["retryCount"] ?? 0
            let nextRetry = currentRetry + 1
            let nextState: SyncState = nextRetry >= syncMaxRetryCount ? .failed : .pending

            try db.execute(sql: """
                UPDATE \(table.name)
                SET \(Schema.SyncColumn.retryCount) = ?,
                    \(Schema.SyncColumn.syncState) = ?,
                    \(Schema.SyncColumn.lastAttemptAt) = ?
                WHERE id = ?
                """, arguments: [
                    nextRetry,
                    nextState.rawValue,
                    now(),
                    recordId.uuidString
                ])
        }
    }

    /// Reset a `failed` record to `pending` for a manual retry, clearing the
    /// retry count so it is eligible for the full retry budget again (Req 2.5,
    /// 7.7). Records not in `failed` are left unchanged.
    public func manualRetry(recordId: UUID) async {
        await mutate(recordId: recordId) { db, table in
            try db.execute(sql: """
                UPDATE \(table.name)
                SET \(Schema.SyncColumn.syncState) = ?,
                    \(Schema.SyncColumn.retryCount) = 0
                WHERE id = ? AND \(Schema.SyncColumn.syncState) = ?
                """, arguments: [
                    SyncState.pending.rawValue,
                    recordId.uuidString,
                    SyncState.failed.rawValue
                ])
        }
    }

    /// Runs `body` against whichever syncable table currently holds `recordId`.
    /// The lookup and the mutation share one transaction so the record cannot
    /// move between tables mid-update (it never does — ids are unique per
    /// record — but the single transaction keeps the read/modify/write atomic).
    private func mutate(
        recordId: UUID,
        _ body: @escaping @Sendable (Database, SyncableTableRef) throws -> Void
    ) async {
        let writer = appDatabase.dbWriter
        try? await writer.write { db in
            for table in syncableTables {
                let exists = try Bool.fetchOne(db, sql: """
                    SELECT EXISTS(SELECT 1 FROM \(table.name) WHERE id = ?)
                    """, arguments: [recordId.uuidString]) ?? false
                if exists {
                    try body(db, SyncableTableRef(name: table.name))
                    return
                }
            }
        }
    }

    // MARK: - Observation

    /// Observe a record's sync state as it changes. Emits the current state
    /// immediately, then a new value whenever the record's `syncState` column
    /// changes in any syncable table, driven by GRDB's `ValueObservation`.
    /// The UI uses this to reflect pending/synchronized/failed within 5 s
    /// (Req 2.7). The stream finishes if the record disappears.
    public func observeSyncState(recordId: UUID) -> AsyncStream<SyncState> {
        let writer = appDatabase.dbWriter
        let idString = recordId.uuidString

        let observation = ValueObservation.tracking { db -> SyncState? in
            for table in syncableTables {
                if let raw = try String.fetchOne(db, sql: """
                    SELECT \(Schema.SyncColumn.syncState)
                    FROM \(table.name) WHERE id = ?
                    """, arguments: [idString]),
                   let state = SyncState(rawValue: raw) {
                    return state
                }
            }
            return nil
        }

        return AsyncStream { continuation in
            let cancellable = observation.start(
                in: writer,
                scheduling: .async(onQueue: .main),
                onError: { _ in continuation.finish() },
                onChange: { state in
                    if let state {
                        continuation.yield(state)
                    }
                }
            )
            continuation.onTermination = { _ in
                cancellable.cancel()
            }
        }
    }
}

/// A lightweight reference to a table name, passed into mutation bodies so they
/// operate on the table that holds the target record without re-scanning.
private struct SyncableTableRef: Sendable {
    let name: String
}
