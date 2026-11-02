//
//  Pulled-data cache
//
//  The pull endpoints exist so QR lookup works offline: reference data (wells,
//  historical tests, open maintenance tasks) fetched while online is written
//  into the `Local_Store`, and the lookup use case reads from the store
//  thereafter (design "Pull endpoints"; Req 6.5). This seam is the write side
//  of that cache. Keeping it behind a protocol lets the REST client be tested
//  with an in-memory cache that merely records what would have been written.
//
//  Upserts use GRDB's `save` (insert-or-update on the primary key) so a repeat
//  pull of the same well/test/task refreshes the cached row rather than
//  failing on a duplicate key.
//
//  Introduced by task 22.1.

import Foundation
import GRDB

/// Writes pulled reference data into the local store so later reads (and QR
/// lookup) succeed offline.
public protocol PulledDataCache: Sendable {
    func cacheWell(_ well: Well) async
    func cacheWellTests(_ tests: [WellTest]) async
    func cacheMaintenanceTasks(_ tasks: [MaintenanceTask]) async
    func cacheWells(_ wells: [Well]) async
}

/// A GRDB-backed `PulledDataCache` used in the running app.
public final class GRDBPulledDataCache: PulledDataCache {

    private let appDatabase: AppDatabase

    public init(appDatabase: AppDatabase) {
        self.appDatabase = appDatabase
    }

    public func cacheWell(_ well: Well) async {
        let writer = appDatabase.dbWriter
        try? await writer.write { db in
            try WellRecord(well).save(db)
        }
    }

    public func cacheWells(_ wells: [Well]) async {
        let writer = appDatabase.dbWriter
        try? await writer.write { db in
            for well in wells {
                try WellRecord(well).save(db)
            }
        }
    }

    /// Caches pulled well tests. Pulled tests are already backend-accepted, so
    /// they are stored `synchronized` (see `WellTestPullDTO.domain`); a `save`
    /// refreshes an existing row without disturbing a locally-pending test of
    /// the same id (ids are client-generated UUIDs, so a collision means it is
    /// the same record echoed back).
    public func cacheWellTests(_ tests: [WellTest]) async {
        let writer = appDatabase.dbWriter
        try? await writer.write { db in
            for test in tests {
                try WellTestRecord(test).save(db)
            }
        }
    }

    public func cacheMaintenanceTasks(_ tasks: [MaintenanceTask]) async {
        let writer = appDatabase.dbWriter
        try? await writer.write { db in
            for task in tasks {
                try MaintenanceTaskRecord(task).save(db)
            }
        }
    }
}
