//
//  UI layer — debug-only sample data
//
//  The well list reads only the local store, which is normally filled by the
//  cloud pull. With no backend configured the list is empty, so debug builds
//  insert a few sample wells (and one open maintenance task) on first launch.
//  Release builds compile none of this. Seeding runs only when the `well`
//  table is empty, so real pulled data is never overwritten or mixed with it.

#if DEBUG
import Foundation
import GRDB

extension AppGraph {

    /// Insert sample wells and a maintenance task if the local store has no
    /// wells. Best-effort and synchronous so the data exists before the first
    /// render of the selection screen.
    func seedSampleDataIfEmpty() {
        let now = Date()
        let wells = [
            Well(id: "WELL-P-001", name: "Pad A Producer 1", type: .production, padId: "PAD-A", updatedAt: now),
            Well(id: "WELL-P-002", name: "Pad A Producer 2", type: .production, padId: "PAD-A", updatedAt: now),
            Well(id: "WELL-I-001", name: "Pad A Injector 1", type: .injection, padId: "PAD-A", updatedAt: now),
            Well(id: "WELL-P-003", name: "Pad B Producer 1", type: .production, padId: "PAD-B", updatedAt: now),
            Well(id: "WELL-I-002", name: "Pad B Injector 1", type: .injection, padId: "PAD-B", updatedAt: now)
        ]
        let task = MaintenanceTask(
            wellId: "WELL-P-001",
            description: "Sample task: inspect wellhead pressure gauge",
            status: .open,
            createdAt: now
        )

        try? appDatabase.dbWriter.write { db in
            guard try WellRecord.fetchCount(db) == 0 else { return }
            for well in wells {
                try WellRecord(well).save(db)
            }
            try MaintenanceTaskRecord(task).save(db)
        }
    }
}
#endif
