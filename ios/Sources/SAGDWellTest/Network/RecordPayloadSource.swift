//
//  Record payload source
//
//  The sync engine pushes by *descriptor* — it hands the push client only a
//  `SyncableRecord` (`id` + `kind` + sync metadata), never a payload (see
//  `SyncPushClient`). So the concrete REST client needs a way to load the full
//  record for an `id`/`kind` in order to build the request body. This seam
//  provides exactly that, mirroring the escalation-only `EscalationBlobSource`
//  the engine already uses.
//
//  Keeping payload loading behind a protocol (rather than having the REST
//  client depend on GRDB) means the client can be unit-tested with an
//  in-memory payload source and a stub transport, with no database and no
//  network.
//
//  Introduced by task 22.1.

import Foundation
import GRDB

/// Loads the concrete payload for a syncable record by its `id`. The REST
/// client calls the accessor matching the record's `kind`.
///
/// A `nil` return means the record is no longer in the store (e.g. it was
/// removed between batching and push). The client treats that as a
/// non-retryable failure — there is nothing to push and retrying cannot help.
public protocol RecordPayloadSource: Sendable {
    func wellTest(id: UUID) async -> WellTest?
    func wellheadLog(id: UUID) async -> WellheadLog?
    func subcoolReading(id: UUID) async -> SubcoolReading?
}

/// A GRDB-backed `RecordPayloadSource` used in the running app. Reads a single
/// row by primary key and maps it to its domain value.
public final class GRDBRecordPayloadSource: RecordPayloadSource {

    private let appDatabase: AppDatabase

    public init(appDatabase: AppDatabase) {
        self.appDatabase = appDatabase
    }

    public func wellTest(id: UUID) async -> WellTest? {
        let writer = appDatabase.dbWriter
        let record = try? await writer.read { db in
            try WellTestRecord.fetchOne(db, key: id.uuidString)
        }
        return record?.domain
    }

    public func wellheadLog(id: UUID) async -> WellheadLog? {
        let writer = appDatabase.dbWriter
        let record = try? await writer.read { db in
            try WellheadLogRecord.fetchOne(db, key: id.uuidString)
        }
        return record?.domain
    }

    public func subcoolReading(id: UUID) async -> SubcoolReading? {
        let writer = appDatabase.dbWriter
        let record = try? await writer.read { db in
            try SubcoolReadingRecord.fetchOne(db, key: id.uuidString)
        }
        return record?.domain
    }
}
