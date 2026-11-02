//
//  QRLookupIndependentOfConnectivityPropertyTests.swift
//  Property 20: QR lookup independent of connectivity (iOS) — task 10.2.
//
//  Validates: Requirements 6.5
//
//  Design "Property 20: QR lookup independent of connectivity":
//
//      For any Well present in the Local_Store, the lookup returns the same
//      Found result (same well, same ordered tests, same open tasks)
//      regardless of whether network connectivity is available.
//
//  This exercises the real `LocalWellLookupUseCase` over a real, migrated
//  in-memory `AppDatabase` + `GRDBWellRepository`:
//
//    * A fresh in-memory `AppDatabase` is built per generated case
//      (`AppDatabase.makeInMemory()`), so each case starts from an empty
//      Local_Store with no cross-case leakage.
//    * A subject `Well` is seeded, then its well tests are written through the
//      store's transactional save path (`GRDBLogRepository.saveWellTest`) and
//      its maintenance tasks are written directly (they are pulled reference
//      data with no repository), both into the Local_Store.
//    * Two `LocalWellLookupUseCase` instances are built over the *same* store,
//      differing only in the injected `ConnectivityProviding.isOnline` — one
//      online (`true`), one offline (`false`). Because the well exists locally,
//      `lookup` must return byte-for-byte the same `.found(...)` result from
//      both: same well, same ordered tests, same open tasks (Req 6.5).
//
//  Miss behaviour is asserted in the same property so the connectivity branch
//  is covered too: for a well id that is NOT in the store, the online lookup
//  must return `.notFound` and the offline lookup must return
//  `.unavailableOffline`.
//
//  Ordering determinism. `getWellTests` orders by `capturedAt` descending,
//  breaking ties by the store-assigned `createdAt`. The store re-assigns
//  `createdAt = now()` at save time, so to keep both lookups returning an
//  unambiguous, identical order the generator draws *distinct* `capturedAt`
//  values for the subject well's tests. (Equality here is between two reads of
//  the *same* store, so even tie-broken order would match; distinct timestamps
//  simply make the invariant self-evident.)
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 20: QR lookup independent of connectivity`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class QRLookupIndependentOfConnectivityPropertyTests: XCTestCase {

    func testQRLookupIndependentOfConnectivity() {
        property(
            propertyName(20, "QR lookup independent of connectivity"),
            arguments: pbtArguments()
        ) <- forAll(lookupCaseGenerator()) { (testCase: LookupCase) in
            do {
                return try self.lookupIndependentOfConnectivityHolds(for: testCase)
            } catch {
                return false <?> "lookup threw: \(error)"
            }
        }
    }

    // MARK: - Property check

    /// Seeds the subject well, then runs the lookup twice over the same store —
    /// once online, once offline — and asserts:
    ///   1. A present well yields identical `.found(...)` results online vs
    ///      offline (same well, same ordered tests, same open tasks — Req 6.5).
    ///   2. A well id absent from the store yields `.notFound` online and
    ///      `.unavailableOffline` offline (the connectivity branch for a miss).
    private func lookupIndependentOfConnectivityHolds(for testCase: LookupCase) throws -> Testable {
        let appDB = try AppDatabase.makeInMemory()
        let logRepo = GRDBLogRepository(appDatabase: appDB, storageProbe: AmpleStorageProbe())
        let wellRepo = GRDBWellRepository(appDatabase: appDB)

        // Seed the subject well.
        try Self.seed(well: testCase.well, into: appDB)

        // Seed the subject well's tests through the store's write path.
        for test in testCase.tests {
            let result = Self.runBlocking { await logRepo.saveWellTest(test) }
            guard result == .success else {
                return false <?> "seeding well test did not succeed: \(result)"
            }
        }

        // Seed the subject well's maintenance tasks directly (no repository).
        try appDB.dbWriter.write { db in
            for task in testCase.tasks {
                try MaintenanceTaskRecord(task).insert(db)
            }
        }

        // Two use cases over the SAME store, differing only in connectivity.
        let onlineLookup = LocalWellLookupUseCase(
            repository: wellRepo,
            connectivity: StaticConnectivityProvider(isOnline: true)
        )
        let offlineLookup = LocalWellLookupUseCase(
            repository: wellRepo,
            connectivity: StaticConnectivityProvider(isOnline: false)
        )

        // --- 1. Present well: identical Found result regardless of connectivity. ---
        let onlineResult = Self.runBlocking { await onlineLookup.lookup(wellId: testCase.well.id) }
        let offlineResult = Self.runBlocking { await offlineLookup.lookup(wellId: testCase.well.id) }

        // Both must be `.found` (the well is in the store on both paths).
        guard case .found = onlineResult else {
            return false <?> "online lookup of present well was not .found: \(onlineResult)"
        }
        guard case .found = offlineResult else {
            return false <?> "offline lookup of present well was not .found: \(offlineResult)"
        }
        // And byte-for-byte equal (same well, same ordered tests, same open
        // tasks). `WellLookupResult` is `Equatable`.
        let foundResultsEqual = onlineResult == offlineResult

        // Sanity: the found payload reflects the seeded data (expected
        // most-recent-first tests, exactly the open tasks).
        let expectedOrderedTestIds = testCase.tests
            .sorted { $0.capturedAt > $1.capturedAt }
            .map(\.id)
        let expectedOpenTaskIds = Set(
            testCase.tasks.filter { $0.status == .open }.map(\.id)
        )
        var payloadOk = false
        if case let .found(well, tests, openTasks) = onlineResult {
            payloadOk = well.id == testCase.well.id
                && tests.map(\.id) == expectedOrderedTestIds
                && Set(openTasks.map(\.id)) == expectedOpenTaskIds
                && openTasks.allSatisfy { $0.status == .open }
        }

        // --- 2. Absent well: online -> .notFound, offline -> .unavailableOffline. ---
        let missingId = testCase.missingWellId
        let onlineMiss = Self.runBlocking { await onlineLookup.lookup(wellId: missingId) }
        let offlineMiss = Self.runBlocking { await offlineLookup.lookup(wellId: missingId) }
        let onlineMissOk = onlineMiss == .notFound
        let offlineMissOk = offlineMiss == .unavailableOffline

        let holds = foundResultsEqual
            && payloadOk
            && onlineMissOk
            && offlineMissOk

        return holds <?> """
            connectivity-independence mismatch for well \(testCase.well.id):
              present: foundResultsEqual=\(foundResultsEqual) payloadOk=\(payloadOk)
                online=\(onlineResult)
                offline=\(offlineResult)
              missing (\(missingId)): onlineMissOk=\(onlineMissOk) (\(onlineMiss)) \
            offlineMissOk=\(offlineMissOk) (\(offlineMiss))
            """
    }

    // MARK: - Store helpers

    private static func seed(well: Well, into appDB: AppDatabase) throws {
        try appDB.dbWriter.write { db in
            try WellRecord(well).insert(db)
        }
    }

    /// Runs an async operation to completion from the synchronous SwiftCheck
    /// predicate (the use case and repositories are `async`).
    private static func runBlocking<T: Sendable>(
        _ operation: @escaping @Sendable () async -> T
    ) -> T {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            let value = await operation()
            box.value = value
            semaphore.signal()
        }
        semaphore.wait()
        return box.value!
    }

    private final class ResultBox<T>: @unchecked Sendable {
        var value: T?
    }

    /// Reports ample free space so seeding saves never short-circuit to
    /// `storageFull`; this property is about lookup, not storage limits.
    private struct AmpleStorageProbe: StorageProbe {
        func availableCapacityBytes() -> Int64? { Int64.max }
    }

    // MARK: - Generated case

    /// A subject well present in the store (with an arbitrary set of tests with
    /// distinct `capturedAt`, and a mix of open/closed tasks) plus a distinct
    /// well id guaranteed NOT to be in the store (the miss case).
    private struct LookupCase {
        let well: Well
        let tests: [WellTest]
        let tasks: [MaintenanceTask]
        /// A well id that is never seeded, exercising the miss branch.
        let missingWellId: String
    }

    // MARK: - Generators

    private func lookupCaseGenerator() -> Gen<LookupCase> {
        Gen<LookupCase>.compose { c in
            // Subject well id and a disjoint "missing" id (different prefix and
            // range so it can never collide with the seeded subject).
            let subjectId = "WELL-\(c.generate(using: Gen<Int>.choose((1, 500_000))))"
            let missingId = "MISSING-\(c.generate(using: Gen<Int>.choose((500_001, 1_000_000))))"

            let well = Self.makeWell(id: subjectId, c)

            // 0..8 tests with DISTINCT capturedAt so most-recent-first order is
            // unambiguous.
            let distinctSeconds = Self.distinctSeconds(
                count: c.generate(using: Gen<Int>.choose((0, 8))),
                c
            )
            let tests = distinctSeconds.map { second in
                Self.makeWellTest(wellId: subjectId, capturedSecond: second, c)
            }

            // 0..8 tasks, each independently open or closed.
            let taskCount = c.generate(using: Gen<Int>.choose((0, 8)))
            let tasks = (0..<taskCount).map { _ in
                Self.makeTask(wellId: subjectId, c)
            }

            return LookupCase(
                well: well,
                tests: tests,
                tasks: tasks,
                missingWellId: missingId
            )
        }
    }

    // MARK: Primitive builders

    private static func makeWell(id: String, _ c: GenComposer) -> Well {
        let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
        return Well(
            id: id,
            name: "Well \(id)",
            type: isProduction ? .production : .injection,
            padId: nil,
            updatedAt: Self.date(c.generate(using: Gen<Int>.choose((0, 2_000_000_000))))
        )
    }

    /// Draws `count` distinct epoch-second values (1-second precision, the
    /// captured-timestamp contract) so sorting by `capturedAt` is total.
    private static func distinctSeconds(count: Int, _ c: GenComposer) -> [Int] {
        var seen = Set<Int>()
        var attempts = 0
        while seen.count < count && attempts < count * 20 + 50 {
            let s = c.generate(using: Gen<Int>.choose((0, 2_000_000_000)))
            seen.insert(s)
            attempts += 1
        }
        return Array(seen)
    }

    private static func makeWellTest(
        wellId: String,
        capturedSecond: Int,
        _ c: GenComposer
    ) -> WellTest {
        WellTest(
            wellId: wellId,
            oilRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
            waterRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
            gasRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
            capturedAt: Self.date(capturedSecond)
        )
    }

    /// A task whose status is drawn open/closed.
    private static func makeTask(wellId: String, _ c: GenComposer) -> MaintenanceTask {
        let resolvedStatus = c.generate(
            using: Gen<MaintenanceTaskStatus>.fromElements(of: MaintenanceTaskStatus.allCases)
        )
        let n = c.generate(using: Gen<Int>.choose((0, 1_000_000)))
        return MaintenanceTask(
            wellId: wellId,
            description: "Task \(n)",
            status: resolvedStatus,
            createdAt: Self.date(c.generate(using: Gen<Int>.choose((0, 2_000_000_000))))
        )
    }

    private static func date(_ seconds: Int) -> Date {
        Date(timeIntervalSince1970: TimeInterval(seconds))
    }
}
