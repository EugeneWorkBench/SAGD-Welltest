//
//  LocationUnavailableFallbackPropertyTests.swift
//
//  Property 23: Location-unavailable fallback (iOS) — task 18.4.
//
//  Validates: Requirements 7.5
//
//  Design "Property 23: Location-unavailable fallback":
//
//      For any escalation captured when no GPS fix is produced within the
//      30-second bound, the saved escalation has its location-unavailable
//      indicator set, has null coordinates, and the save still succeeds.
//
//  The property is exercised end-to-end through the real
//  `EscalationCaptureCoordinator`, a real `GRDBEscalationRepository` over an
//  in-memory `AppDatabase`, and a real `BlobStore` in a temporary directory —
//  no mocks on the persistence path. The only injected seam is the location
//  provider: a fake `LocationProviding` that always resolves to `.timedOut`,
//  standing in for a GPS fix that never arrives within the bound (Req 7.5).
//
//  For each generated case the harness:
//    1. Seeds the parent well (foreign keys are enforced on the in-memory DB,
//       so the escalation's `wellId` must reference a real row).
//    2. Captures an escalation with valid selections (a priority and that
//       well), a non-empty photo, and the timed-out location provider, using a
//       fixed `now` clock whose instant carries a sub-second fraction.
//    3. Asserts the coordinator returns `.saved` (the operator can proceed) and
//       that the *persisted* row — read back from the store — has
//       `locationUnavailable == true`, nil latitude and longitude, and a
//       `capturedAt` equal to the clock instant truncated to a whole UTC
//       second (so the fraction is dropped, matching the 1-second precision the
//       escalation record stores).
//
//  Reading the row back (rather than trusting the returned value) proves the
//  fallback state is what actually landed in the Local_Store.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 23: Location-unavailable fallback`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class LocationUnavailableFallbackPropertyTests: XCTestCase {

    func testLocationUnavailableFallback() {
        property(
            propertyName(23, "Location-unavailable fallback"),
            arguments: pbtArguments()
        ) <- forAll(Self.caseGenerator()) { (testCase: FallbackCase) in
            do {
                return try self.fallbackHolds(for: testCase)
            } catch {
                return false <?> "harness threw: \(error)"
            }
        }
    }

    // MARK: - The property check for one generated case

    /// Seeds the parent well, captures through the coordinator with a
    /// permanently-timing-out location provider, and asserts the saved
    /// escalation records the unavailable-location fallback.
    private func fallbackHolds(for testCase: FallbackCase) throws -> Testable {
        let blobDir = Self.makeTempBlobDirectory()
        defer { try? FileManager.default.removeItem(at: blobDir) }

        let appDB = try AppDatabase.makeInMemory()
        let blobStore = try BlobStore(directory: blobDir)

        // Seed the parent well so the escalation's FK resolves.
        try appDB.dbWriter.write { db in
            try WellRecord(testCase.well).insert(db)
        }

        let repository = GRDBEscalationRepository(
            appDatabase: appDB,
            blobStore: blobStore
        )

        // Fixed clock with a sub-second fraction, so the "UTC 1-second"
        // assertion is meaningful: the fraction must be dropped.
        let clockInstant = testCase.nowInstant
        let coordinator = EscalationCaptureCoordinator(
            locationProvider: AlwaysTimesOutLocationProvider(),
            repository: repository,
            now: { clockInstant }
        )

        let input = EscalationInput(wellId: testCase.well.id, priority: testCase.priority)
        let result = Self.runBlocking {
            await coordinator.capture(input: input, photoData: testCase.photoData)
        }

        // 1. The operator can proceed: the capture succeeded despite the
        //    timeout (Req 7.5). Pull the assembled escalation out of `.saved`.
        let savedEscalation: Escalation
        switch result {
        case .saved(let escalation):
            savedEscalation = escalation
        case .invalid, .storageFull, .failed:
            return false <?> "expected .saved on location timeout; got \(result)"
        }

        // 2. Read the row back from the store — the fallback state must be what
        //    actually persisted, not just what was returned.
        let persisted: Escalation? = try appDB.dbWriter.read { db in
            try EscalationRecord
                .filter(Column("id") == savedEscalation.id.uuidString)
                .fetchOne(db)?
                .domain
        }

        guard let persisted else {
            return false <?> "escalation \(savedEscalation.id) was not found in the store after save"
        }

        let expectedCapturedAt = utcSecondTruncated(clockInstant)

        let locationUnavailableSet = (persisted.locationUnavailable == true)
            <?> "persisted.locationUnavailable must be true; got \(persisted.locationUnavailable)"

        let noCoordinates = (persisted.latitude == nil && persisted.longitude == nil)
            <?> "persisted coordinates must be nil; got lat=\(String(describing: persisted.latitude)), lon=\(String(describing: persisted.longitude))"

        let utcSecondCapturedAt = (persisted.capturedAt == expectedCapturedAt)
            <?> "persisted.capturedAt must be the clock instant truncated to a whole UTC second (\(expectedCapturedAt)); got \(persisted.capturedAt)"

        // `capturedAt` must carry no sub-second fraction (1-second precision).
        let whole = persisted.capturedAt.timeIntervalSince1970
        let wholeSecond = (whole == whole.rounded(.down))
            <?> "persisted.capturedAt must have whole-second precision; got \(whole)"

        return locationUnavailableSet ^&&^ noCoordinates ^&&^ utcSecondCapturedAt ^&&^ wholeSecond
    }

    // MARK: - Injected location seam

    /// A `LocationProviding` whose request always resolves to `.timedOut`,
    /// standing in for a GPS fix that never arrives within the 30-second bound.
    private struct AlwaysTimesOutLocationProvider: LocationProviding {
        func requestFix() async -> LocationResult { .timedOut }
    }

    // MARK: - Generated case

    /// One generated capture scenario: the parent well to seed, the valid
    /// selections (priority + that well), the photo bytes, and the fixed clock
    /// instant stamped onto the escalation when the fix times out.
    private struct FallbackCase {
        var well: Well
        var priority: EscalationPriority
        var photoData: Data
        var nowInstant: Date
    }

    // MARK: - Generators

    private static func caseGenerator() -> Gen<FallbackCase> {
        Gen<FallbackCase>.compose { c in
            let wellId: String = c.generate(using: wellIdGen)
            let wellType: WellType = c.generate(
                using: Gen<WellType>.fromElements(of: WellType.allCases)
            )
            let well = Well(
                id: wellId,
                name: "Well \(wellId)",
                type: wellType,
                padId: nil,
                updatedAt: c.generate(using: timestampGen)
            )
            return FallbackCase(
                well: well,
                priority: c.generate(
                    using: Gen<EscalationPriority>.fromElements(of: EscalationPriority.allCases)
                ),
                photoData: Self.photoData(c),
                nowInstant: c.generate(using: fractionalTimestampGen)
            )
        }
    }

    private static let wellIdGen: Gen<String> =
        Gen<Int>.choose((1, 1_000_000)).map { "WELL-\($0)" }

    /// A whole-second UNIX instant for the well's `updatedAt`.
    private static let timestampGen: Gen<Date> =
        Gen<Int>.choose((1_000_000_000, 2_000_000_000))
            .map { Date(timeIntervalSince1970: TimeInterval($0)) }

    /// A clock instant that carries a non-zero sub-second fraction, so the
    /// "truncate to a whole UTC second" assertion actually exercises the
    /// truncation rather than passing trivially.
    private static let fractionalTimestampGen: Gen<Date> =
        Gen<Date>.compose { c in
            let seconds: Int = c.generate(using: Gen<Int>.choose((1_000_000_000, 2_000_000_000)))
            let millis: Int = c.generate(using: Gen<Int>.choose((1, 999)))
            return Date(timeIntervalSince1970: TimeInterval(seconds) + Double(millis) / 1000.0)
        }

    private static func photoData(_ c: GenComposer) -> Data {
        let length: Int = c.generate(using: Gen<Int>.choose((1, 512)))
        return Data((0..<length).map { _ in
            UInt8(c.generate(using: Gen<Int>.choose((0, 255))))
        })
    }

    // MARK: - Async bridge

    /// Bridges the coordinator's `async` API to SwiftCheck's synchronous
    /// predicate.
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
}
