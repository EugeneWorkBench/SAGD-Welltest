//
//  UI layer — well list source for the selection screen
//
//  The well-selection screen needs the set of wells the operator can pick from.
//  The domain `WellRepository` protocol addresses a single well by id (its job
//  is QR lookup and associated-record reads), so listing the locally cached
//  wells for selection is a UI-layer concern expressed here as a small
//  `WellListProviding` source. The GRDB-backed default reads the `well` table
//  (pulled reference data) directly through the shared `AppDatabase`; it never
//  requires the network (Req 1.2).
//
//  Implemented by task 20.1 to support well selection.

import Foundation
import GRDB

/// Supplies the wells shown on the selection screen.
public protocol WellListProviding: Sendable {
    /// All locally cached wells, ordered by display name for a stable list.
    func allWells() async -> [Well]
}

/// GRDB-backed well list source reading the `well` table from the shared store.
public struct GRDBWellListProvider: WellListProviding {

    private let appDatabase: AppDatabase

    public init(appDatabase: AppDatabase) {
        self.appDatabase = appDatabase
    }

    public func allWells() async -> [Well] {
        let writer = appDatabase.dbWriter
        let records = (try? await writer.read { db in
            try WellRecord
                .order(Column("name").asc, Column("id").asc)
                .fetchAll(db)
        }) ?? []
        return records.compactMap(\.domain)
    }
}
