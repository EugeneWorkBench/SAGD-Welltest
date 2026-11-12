//
//  UI layer — reference-data refresh (cloud -> local store)
//
//  The REST client exposes pull endpoints, but nothing in the app invoked them,
//  so the local `well` table (which the well list and QR lookup read) was never
//  filled from the backend. This refresh closes that gap: it pulls wells changed
//  since the last successful pull, then each pulled well's test history and open
//  maintenance tasks, all of which the client writes into the local store.
//
//  Failure behavior: the client's pull methods return an empty result on any
//  network/HTTP/decode failure and leave the cache untouched, so a failed
//  refresh simply changes nothing and the app keeps working from local data.
//
//  Known limit: tests and tasks are re-pulled only for wells whose own record
//  changed, so new tests on an unchanged well appear after that well next
//  updates. A per-well refresh on QR lookup would close that gap.

import Foundation

extension AppGraph {

    /// `UserDefaults` key holding the newest `updatedAt` seen in a pull.
    static let lastWellPullKey = "SAGDLastWellPullUpdatedAt"

    /// Concurrent per-well detail pulls, kept small to be polite to the server.
    private static let detailPullBatchSize = 4

    /// Pull changed wells and their details into the local store.
    ///
    /// The "since" marker is the newest `updatedAt` the server itself returned,
    /// not the phone's clock, so a wrong device clock cannot skip updates. It
    /// only advances after a pull that returned wells.
    public func refreshReferenceData(defaults: UserDefaults = .standard) async {
        let since = defaults.object(forKey: Self.lastWellPullKey) as? Date
            ?? Date(timeIntervalSince1970: 0)
        let client = backendClient

        let wells = await client.pullWellsUpdated(since: since)
        guard !wells.isEmpty else { return }

        var index = 0
        while index < wells.count {
            let batch = wells[index ..< min(index + Self.detailPullBatchSize, wells.count)]
            await withTaskGroup(of: Void.self) { group in
                for well in batch {
                    group.addTask { _ = await client.pullWellTests(wellId: well.id) }
                    group.addTask { _ = await client.pullOpenMaintenanceTasks(wellId: well.id) }
                }
            }
            index += Self.detailPullBatchSize
        }

        if let newest = wells.map(\.updatedAt).max() {
            defaults.set(newest, forKey: Self.lastWellPullKey)
        }
    }

    /// Whether a real backend address has been configured (anything other than
    /// the localhost fallback).
    static func isBackendConfigured(_ configuration: AppConfiguration) -> Bool {
        configuration.baseURL != AppConfiguration.fallbackBaseURL
    }
}
