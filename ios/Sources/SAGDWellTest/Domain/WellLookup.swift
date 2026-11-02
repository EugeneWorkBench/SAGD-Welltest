//
//  Well-lookup use case
//
//  Resolves a well identifier (decoded from a QR code) against the local store
//  and assembles the view model shown after a scan: the well, its well tests
//  ordered most-recent-first, and its open maintenance tasks. The use case
//  reads *only* the local store via `WellRepository` and never touches the
//  network, so a local hit returns the same `Found` result regardless of
//  connectivity. Connectivity is consulted only to classify a *miss*: an online
//  miss is `notFound` (the well genuinely is not known), while an offline miss
//  is `unavailableOffline` (the well may exist but has not been pulled yet).
//
//  Deterministic over its inputs and the injected collaborators, with no I/O of
//  its own beyond the repository reads. Implemented by task 10.1.
//
//  Requirements: 6.2, 6.5.
//  Correctness properties: 19 (lookup ordering + open-task filtering, exercised
//  via the repository), 20 (lookup independent of connectivity).

import Foundation

// MARK: - Connectivity provider

/// A minimal connectivity signal the well-lookup use case consults *only* to
/// classify a lookup miss. It is deliberately tiny — a single boolean — so the
/// domain layer stays free of any platform networking type (`NWPathMonitor`,
/// `NetworkCallback`, …); those platform monitors conform to this protocol in
/// the integration layer, and tests supply a trivial fake.
///
/// The provider is never consulted on a local hit: when the well exists in the
/// local store the result is identical whether online or offline (Req 6.5).
public protocol ConnectivityProviding: Sendable {
    /// Whether the device currently has network connectivity. Used only to
    /// distinguish an online miss (`notFound`) from an offline miss
    /// (`unavailableOffline`).
    var isOnline: Bool { get }
}

/// A fixed connectivity signal, useful for wiring a known state and for tests.
public struct StaticConnectivityProvider: ConnectivityProviding {
    public let isOnline: Bool

    public init(isOnline: Bool) {
        self.isOnline = isOnline
    }
}

// MARK: - Well-lookup use case

/// The default `WellLookupUseCase`: looks a well up in the local store and,
/// on a hit, assembles the well together with its tests (most-recent-first,
/// as ordered by the repository) and its open maintenance tasks. On a miss it
/// consults the injected connectivity provider to return `notFound` (online)
/// or `unavailableOffline` (offline).
///
/// The repository is the sole source of data: `lookup` issues only local reads
/// and performs no network access. This is what makes a `Found` result
/// independent of connectivity (Req 6.5, Property 20) and what keeps the
/// most-recent-first / open-only ordering a property of the repository layer
/// (Req 6.2, Property 19).
public struct LocalWellLookupUseCase: WellLookupUseCase {

    /// The local-store-backed repository. Only its read methods are used.
    private let repository: WellRepository

    /// Connectivity signal, consulted only to classify a miss.
    private let connectivity: ConnectivityProviding

    /// - Parameters:
    ///   - repository: Reads wells, tests, and tasks from the local store.
    ///   - connectivity: Supplies the online/offline signal used to classify a
    ///     lookup miss. It is never consulted on a hit.
    public init(repository: WellRepository, connectivity: ConnectivityProviding) {
        self.repository = repository
        self.connectivity = connectivity
    }

    public func lookup(wellId: String) async -> WellLookupResult {
        // Local read only — never the network. A present well yields the same
        // `Found` result whether the device is online or offline.
        guard let well = await repository.getWell(id: wellId) else {
            // Miss: branch on connectivity. Online means the well is genuinely
            // not known; offline means it may simply not have been pulled yet.
            return connectivity.isOnline ? .notFound : .unavailableOffline
        }

        // Hit: assemble the well with its tests (most-recent-first per the
        // repository contract) and its open maintenance tasks (the repository
        // filters to `status == .open`). No connectivity check on this path.
        let tests = await repository.getWellTests(wellId: wellId)
        let openTasks = await repository.getOpenMaintenanceTasks(wellId: wellId)

        return .found(well: well, wellTests: tests, openMaintenanceTasks: openTasks)
    }
}
