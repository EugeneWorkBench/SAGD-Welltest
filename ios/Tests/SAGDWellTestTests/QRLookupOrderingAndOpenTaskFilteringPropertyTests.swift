//
//  QRLookupOrderingAndOpenTaskFilteringPropertyTests.swift
//  Property 19: QR lookup ordering and open-task filtering (iOS) — task 5.5.
//
//  Validates: Requirements 6.2
//
//  Design "Property 19: QR lookup ordering and open-task filtering":
//
//      For any Well in the Local_Store with an arbitrary set of well tests and
//      maintenance tasks, a successful lookup returns the well's tests ordered
//      from most recent to oldest and returns exactly those maintenance tasks
//      whose status is open.
//
//  This property exercises the real repository read path end to end:
//
//    * The store is a fresh, migrated in-memory `AppDatabase` per generated
//      case (`AppDatabase.makeInMemory()`), so each case starts from an empty
//      Local_Store with no cross-case leakage.
//    * A parent `Well` is seeded first because `wellTest` and
//      `maintenanceTask` carry a non-null `wellId` foreign key (Schema.swift).
//    * Well tests are seeded through the store's write path
//      (`GRDBLogRepository.saveWellTest`) — the same transactional save the UI
//      uses. Maintenance tasks have no repository (they are pulled reference
//      data), so they are seeded directly through `MaintenanceTaskRecord`,
//      still a write to the Local_Store.
//    * A second, unrelated "other" well plus its own tests and an open task are
//      also seeded so the query's `wellId` filter is exercised: tests and tasks
//      belonging to another well must never leak into the subject well's
//      results.
//    * The assertions read back exclusively through `GRDBWellRepository`
//      (`getWellTests` / `getOpenMaintenanceTasks`) — the subject of Req 6.2.
//
//  Ordering determinism. `getWellTests` orders by `capturedAt` descending and
//  breaks ties by the store-assigned `createdAt` (also descending). The
//  store re-assigns `createdAt = now()` at save time, so relying on `capturedAt`
//  ties would make the expected order depend on save timing. To keep the
//  expected order unambiguous, the generator draws *distinct* `capturedAt`
//  values for the subject well's tests; the expected result is then simply
//  those tests sorted by `capturedAt` descending.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 19: QR lookup ordering and open-task filtering`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class QRLookupOrderingAndOpenTaskFilteringPropertyTests: XCTestCase {

    func testQRLookupOrderingAndOpenTaskFiltering() {
        property(
            propertyName(19, "QR lookup ordering and open-task filtering"),
            arguments: pbtArguments()
        ) <- forAll(lookupCaseGenerator()) { (testCase: LookupCase) in
            do {
                return try self.lookupHolds(for: testCase)
            } catch {
                return false <?> "lookup threw: \(error)"
            }
        }
    }

    // MARK: - Property check

    /// Seeds the subject well (plus an unrelated well whose records must not
    /// leak), then reads back through `GRDBWellRepository` and asserts:
    ///   1. `getWellTests` returns exactly the subject well's tests, ordered
    ///      most-recent-first by `capturedAt`.
    ///   2. `getOpenMaintenanceTasks` returns exactly the subject well's
    ///      open-status tasks (no closed tasks, no other well's tasks).
    private func lookupHolds(for testCase: LookupCase) throws -> Testable {
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

        // Seed an unrelated well with its own tests and an open task so the
        // `wellId` filter is genuinely exercised (its records must not appear).
        try Self.seed(well: testCase.otherWell, into: appDB)
        for test in testCase.otherTests {
            _ = Self.runBlocking { await logRepo.saveWellTest(test) }
        }
        try appDB.dbWriter.write { db in
            for task in testCase.otherTasks {
                try MaintenanceTaskRecord(task).insert(db)
            }
        }

        // --- Read back through the WellRepository (the subject of Req 6.2). ---
        let returnedTests = Self.runBlocking { await wellRepo.getWellTests(wellId: testCase.well.id) }
        let returnedTasks = Self.runBlocking { await wellRepo.getOpenMaintenanceTasks(wellId: testCase.well.id) }

        // 1. Ordering + membership for well tests.
        let expectedTestIdsOrdered = testCase.tests
            .sorted { $0.capturedAt > $1.capturedAt }
            .map(\.id)
        let returnedTestIds = returnedTests.map(\.id)

        // a) Exactly the subject well's tests (no leakage from the other well).
        let testsMembershipOk = Set(returnedTestIds) == Set(testCase.tests.map(\.id))
        // b) Returned in most-recent-first order.
        let testsOrderOk = returnedTestIds == expectedTestIdsOrdered
        // c) Independently confirm the sequence is non-increasing in capturedAt.
        let capturedAts = returnedTests.map(\.capturedAt)
        let nonIncreasing = zip(capturedAts, capturedAts.dropFirst()).allSatisfy { $0 >= $1 }

        // 2. Open-task filtering.
        let expectedOpenTaskIds = Set(
            testCase.tasks.filter { $0.status == .open }.map(\.id)
        )
        let returnedTaskIds = Set(returnedTasks.map(\.id))
        // a) Exactly the subject well's open tasks.
        let tasksMembershipOk = returnedTaskIds == expectedOpenTaskIds
        // b) Every returned task is actually open (no closed task slips through).
        let allReturnedOpen = returnedTasks.allSatisfy { $0.status == .open }

        let holds = testsMembershipOk
            && testsOrderOk
            && nonIncreasing
            && tasksMembershipOk
            && allReturnedOpen

        return holds <?> """
            lookup mismatch for well \(testCase.well.id):
              tests: returned ids \(returnedTestIds) (count \(returnedTestIds.count)); \
            expected ordered \(expectedTestIdsOrdered); \
            membershipOk=\(testsMembershipOk) orderOk=\(testsOrderOk) nonIncreasing=\(nonIncreasing)
              tasks: returned \(returnedTaskIds); expected open \(expectedOpenTaskIds); \
            membershipOk=\(tasksMembershipOk) allOpen=\(allReturnedOpen)
            """
    }

    // MARK: - Store helpers

    private static func seed(well: Well, into appDB: AppDatabase) throws {
        try appDB.dbWriter.write { db in
            try WellRecord(well).insert(db)
        }
    }

    /// Runs an async operation to completion from the synchronous SwiftCheck
    /// predicate (the repositories are `async`).
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
    /// `storageFull`; this property is about read ordering/filtering.
    private struct AmpleStorageProbe: StorageProbe {
        func availableCapacityBytes() -> Int64? { Int64.max }
    }

    // MARK: - Generated case

    /// A subject well with an arbitrary set of tests (distinct `capturedAt`)
    /// and a mix of open/closed tasks, plus an unrelated "other" well whose
    /// records must be excluded by the `wellId` filter.
    private struct LookupCase {
        let well: Well
        let tests: [WellTest]
        let tasks: [MaintenanceTask]

        let otherWell: Well
        let otherTests: [WellTest]
        let otherTasks: [MaintenanceTask]
    }

    // MARK: - Generators

    private func lookupCaseGenerator() -> Gen<LookupCase> {
        Gen<LookupCase>.compose { c in
            // Two distinct well ids: subject and "other".
            let subjectId = "WELL-\(c.generate(using: Gen<Int>.choose((1, 500_000))))"
            let otherId = "OTHER-\(c.generate(using: Gen<Int>.choose((500_001, 1_000_000))))"

            let well = Self.makeWell(id: subjectId, c)
            let otherWell = Self.makeWell(id: otherId, c)

            // Subject tests: 0..8 tests with DISTINCT capturedAt so the
            // most-recent-first order is unambiguous.
            let distinctSeconds = Self.distinctSeconds(
                count: c.generate(using: Gen<Int>.choose((0, 8))),
                c
            )
            let tests = distinctSeconds.map { second in
                Self.makeWellTest(wellId: subjectId, capturedSecond: second, c)
            }

            // Subject tasks: 0..8 tasks, each independently open or closed.
            let taskCount = c.generate(using: Gen<Int>.choose((0, 8)))
            let tasks = (0..<taskCount).map { _ in
                Self.makeTask(wellId: subjectId, c)
            }

            // Other well's records: at least one of each so the filter has
            // something concrete to exclude.
            let otherTestSeconds = Self.distinctSeconds(
                count: c.generate(using: Gen<Int>.choose((1, 4))),
                c
            )
            let otherTests = otherTestSeconds.map { second in
                Self.makeWellTest(wellId: otherId, capturedSecond: second, c)
            }
            // Force at least one OPEN task on the other well — the strongest
            // check that open-task filtering is also scoped by wellId.
            let otherTasks = [Self.makeTask(wellId: otherId, status: .open, c)]

            return LookupCase(
                well: well,
                tests: tests,
                tasks: tasks,
                otherWell: otherWell,
                otherTests: otherTests,
                otherTasks: otherTasks
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

    /// A task whose status is drawn open/closed unless pinned.
    private static func makeTask(
        wellId: String,
        status: MaintenanceTaskStatus? = nil,
        _ c: GenComposer
    ) -> MaintenanceTask {
        let resolvedStatus = status ?? c.generate(
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
