//
//  EscalationGeotagAttachmentPropertyTests.swift
//
//  Property 21: Escalation geotag attachment (iOS) — task 18.3.
//
//  Validates: Requirements 7.1
//
//  Requirement 7.1 says that WHEN an Operator captures a photo for an
//  Escalation, THE App SHALL attach the GPS_Coordinates as decimal-degree
//  latitude and longitude and a capture timestamp recorded to the second in
//  UTC to the Escalation.
//
//  This property exercises the real capture path end to end. For any GPS fix
//  (decimal-degree latitude/longitude and a capture instant), driving the
//  `EscalationCaptureCoordinator` with a fake `LocationProviding` that returns
//  that `.fix`, over the real `GRDBEscalationRepository` (in-memory
//  `AppDatabase` + a temporary-directory `BlobStore`), must:
//
//    * yield a `.saved` escalation whose latitude/longitude equal the fix's
//      decimal-degree coordinates exactly, with `locationUnavailable == false`,
//      and whose `capturedAt` equals the fix's instant truncated to whole-second
//      UTC; and
//    * persist that same escalation to the Local_Store, so the row reloaded by
//      id carries the identical coordinates and whole-second-UTC `capturedAt`.
//
//  The fix's capture instant is generated at sub-second precision so the
//  whole-second-UTC assertion is meaningful: the stored `capturedAt` must equal
//  the fix instant floored to the second (`utcSecondTruncated`), never carrying
//  a fractional component. The fake provider supplies the fix already truncated
//  to the second (as the real `LocationFix` contract requires), so the test
//  asserts the coordinator and repository carry that whole-second value through
//  unchanged to both the result and the persisted row.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged through
//  `propertyName(_:_:)`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class EscalationGeotagAttachmentPropertyTests: XCTestCase {

    // MARK: - Fake location provider

    /// A `LocationProviding` that always resolves to a fixed `.fix`. Stands in
    /// for CoreLocation so the capture path is driven deterministically with a
    /// known decimal-degree latitude/longitude and whole-second-UTC instant.
    private struct FakeLocationProvider: LocationProviding {
        let fix: LocationFix
        func requestFix() async -> LocationResult { .fix(fix) }
    }

    // MARK: - Property 21

    func testEscalationGeotagAttachment() {
        property(
            propertyName(21, "Escalation geotag attachment"),
            arguments: pbtArguments()
        ) <- forAll(Self.geotagCaseGenerator()) { (testCase: GeotagCase) in
            guard let outcome = Self.run(testCase) else {
                return false <?> "capture harness threw"
            }

            // The fix's own instant floored to whole-second UTC is the value the
            // escalation must carry for `capturedAt` (Req 7.1). The fake already
            // supplies a whole-second fix, so this equals the fix instant.
            let expectedCapturedAt = utcSecondTruncated(testCase.fix.capturedAt)

            // 1. The capture result is `.saved` carrying the fix's decimal-degree
            //    coordinates and the whole-second-UTC capture timestamp, with
            //    location marked available.
            guard case .saved(let saved) = outcome.result else {
                return false <?> "expected .saved, got \(outcome.result)"
            }
            let resultLat = (saved.latitude == testCase.fix.latitude)
                <?> "saved latitude \(String(describing: saved.latitude)) != fix \(testCase.fix.latitude)"
            let resultLon = (saved.longitude == testCase.fix.longitude)
                <?> "saved longitude \(String(describing: saved.longitude)) != fix \(testCase.fix.longitude)"
            let resultAvailable = (saved.locationUnavailable == false)
                <?> "a fix must not mark location unavailable"
            let resultCapturedAt = (saved.capturedAt == expectedCapturedAt)
                <?> "saved capturedAt \(saved.capturedAt) != whole-second-UTC fix \(expectedCapturedAt)"
            let capturedAtWholeSecond = (Self.isWholeSecond(saved.capturedAt))
                <?> "saved capturedAt \(saved.capturedAt) is not truncated to the second"

            // 2. The persisted row reloaded by id carries the identical geotag:
            //    same decimal-degree coordinates and whole-second-UTC capturedAt.
            guard let persisted = outcome.persisted else {
                return false <?> "escalation was not persisted to the Local_Store"
            }
            let rowLat = (persisted.latitude == testCase.fix.latitude)
                <?> "persisted latitude \(String(describing: persisted.latitude)) != fix \(testCase.fix.latitude)"
            let rowLon = (persisted.longitude == testCase.fix.longitude)
                <?> "persisted longitude \(String(describing: persisted.longitude)) != fix \(testCase.fix.longitude)"
            let rowAvailable = (persisted.locationUnavailable == false)
                <?> "persisted row must not mark location unavailable for a fix"
            let rowCapturedAt = (persisted.capturedAt == expectedCapturedAt)
                <?> "persisted capturedAt \(persisted.capturedAt) != whole-second-UTC fix \(expectedCapturedAt)"

            return resultLat ^&&^ resultLon ^&&^ resultAvailable
                ^&&^ resultCapturedAt ^&&^ capturedAtWholeSecond
                ^&&^ rowLat ^&&^ rowLon ^&&^ rowAvailable ^&&^ rowCapturedAt
        }
    }

    // MARK: - Harness

    /// One capture run's observable outcome: the coordinator result and the
    /// escalation reloaded from the Local_Store (nil if no row was written).
    private struct CaptureOutcome {
        var result: EscalationCaptureResult
        var persisted: Escalation?
    }

    /// Builds a fresh in-memory store, seeds the parent well (FK), runs the
    /// coordinator over the real `GRDBEscalationRepository` with a fake location
    /// provider returning the case's `.fix`, and reloads the persisted row by
    /// id. Returns `nil` if any setup step throws.
    private static func run(_ testCase: GeotagCase) -> CaptureOutcome? {
        let appDB: AppDatabase
        let blobStore: BlobStore
        do {
            appDB = try AppDatabase.makeInMemory()
            blobStore = try BlobStore(directory: makeTempBlobDirectory())
            // The escalation references its well under an enforced foreign key;
            // seed the parent well (cached reference data, a pure local write).
            try appDB.dbWriter.write { db in
                try WellRecord(testCase.well).insert(db)
            }
        } catch {
            return nil
        }

        let repository = GRDBEscalationRepository(
            appDatabase: appDB,
            blobStore: blobStore,
            syncSignal: NoopSyncWorkSignal()
        )
        let coordinator = EscalationCaptureCoordinator(
            locationProvider: FakeLocationProvider(fix: testCase.fix),
            repository: repository
        )

        return bridge {
            let result = await coordinator.capture(
                input: EscalationInput(
                    wellId: testCase.wellId,
                    priority: testCase.priority
                ),
                photoData: testCase.photoData
            )

            // Reload every escalation row for the well; the capture generates a
            // fresh id, so match on the sole persisted row for this well.
            let persisted: Escalation? = try? await appDB.dbWriter.read { db in
                try EscalationRecord
                    .filter(Column("wellId") == testCase.wellId)
                    .fetchOne(db)?
                    .domain
            }

            return CaptureOutcome(result: result, persisted: persisted)
        }
    }

    /// Whether a date sits exactly on a whole-second boundary (no fractional
    /// part), i.e. `capturedAt` is recorded to the second (Req 7.1).
    private static func isWholeSecond(_ date: Date) -> Bool {
        let seconds = date.timeIntervalSince1970
        return seconds == seconds.rounded(.down)
    }

    /// A throwaway blob directory under the system temp dir, unique per run so
    /// parallel cases never collide.
    private static func makeTempBlobDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("sagd-prop21-\(UUID().uuidString)", isDirectory: true)
    }

    // MARK: - Async bridge for SwiftCheck

    /// Runs an async body to completion from SwiftCheck's synchronous `forAll`.
    /// Returns `nil` if the body throws.
    private static func bridge<T>(_ body: @escaping () async throws -> T) -> T? {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            box.value = try? await body()
            semaphore.signal()
        }
        semaphore.wait()
        return box.value
    }

    private final class ResultBox<T> {
        var value: T?
    }

    // MARK: - Generated inputs

    /// One generated capture case: a valid well + priority to pass validation,
    /// a GPS fix to attach, and photo bytes to persist.
    private struct GeotagCase {
        var wellId: String
        var well: Well
        var priority: EscalationPriority
        var fix: LocationFix
        var photoData: Data
    }

    /// Generates a valid, internally consistent capture case. Latitude and
    /// longitude span the full decimal-degree ranges; the fix's capture instant
    /// is a whole-second UTC value (as the `LocationFix` contract requires),
    /// generated from a sub-second instant floored to the second so the
    /// whole-second-UTC assertion is exercised against non-trivial instants.
    private static func geotagCaseGenerator() -> Gen<GeotagCase> {
        let wellIdGen = Gen<Int>.choose((1, 100_000)).map { "WELL-\($0)" }
        let latitudeGen = Gen<Double>.choose((-90.0, 90.0))
        let longitudeGen = Gen<Double>.choose((-180.0, 180.0))
        // A whole-second epoch plus a sub-second offset, then floored, so the
        // fix always lands on a whole second regardless of the fractional part.
        let wholeSecondGen = Gen<Int>.choose((1_600_000_000, 1_800_000_000))
        let fractionGen = Gen<Double>.choose((0.0, 0.999_999))

        return Gen<GeotagCase>.compose { c in
            let wellId: String = c.generate(using: wellIdGen)
            let wellType: WellType = c.generate(
                using: Gen<WellType>.fromElements(of: WellType.allCases)
            )

            let wholeSecond = c.generate(using: wholeSecondGen)
            let fraction = c.generate(using: fractionGen)
            let fixInstant = utcSecondTruncated(
                Date(timeIntervalSince1970: Double(wholeSecond) + fraction)
            )

            let well = Well(
                id: wellId,
                name: "Well \(wellId)",
                type: wellType,
                padId: nil,
                updatedAt: fixInstant
            )

            let fix = LocationFix(
                latitude: c.generate(using: latitudeGen),
                longitude: c.generate(using: longitudeGen),
                capturedAt: fixInstant
            )

            let priority: EscalationPriority = c.generate(
                using: Gen<EscalationPriority>.fromElements(of: EscalationPriority.allCases)
            )

            let photoLength: Int = c.generate(using: Gen<Int>.choose((1, 256)))
            let photoData = Data((0..<photoLength).map { _ in
                UInt8(c.generate(using: Gen<Int>.choose((0, 255))))
            })

            return GeotagCase(
                wellId: wellId,
                well: well,
                priority: priority,
                fix: fix,
                photoData: photoData
            )
        }
    }
}
