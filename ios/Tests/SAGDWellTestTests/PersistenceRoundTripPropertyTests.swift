//
//  PersistenceRoundTripPropertyTests.swift
//  Property 1: Persistence round-trip (iOS) — task 5.2.
//
//  Validates: Requirements 1.1, 1.3, 7.3
//
//  Design "Property 1: Persistence round-trip":
//
//      For any valid syncable record (well test, wellhead log, subcool
//      reading, or escalation), saving it to the Local_Store and then
//      reloading from the store (including after the store is closed and
//      reopened) yields a record whose captured fields are equal to those
//      originally saved.
//
//  This property exercises the real persistence path end to end:
//
//    * The store is a GRDB `DatabaseQueue` backed by an on-disk temporary file
//      (not in-memory), so the AppDatabase can be *closed and reopened* to
//      prove persistence survives an app restart (Req 1.3). A fresh temp file
//      is used per generated case and removed afterwards.
//    * A parent `Well` is seeded first because `wellTest`, `wellheadLog`, and
//      `escalation` carry a non-null `wellId` foreign key (Schema.swift).
//    * Well tests and wellhead logs are saved through `GRDBLogRepository`;
//      escalations through `GRDBEscalationRepository` (blob store + repo), which
//      writes the photo blob before the row; subcool readings have no
//      repository, so they are saved directly to the store through their
//      record type — still a save to the Local_Store, the subject of this
//      property.
//    * After saving, the AppDatabase is deallocated and a *new* AppDatabase is
//      opened over the same file, and the record is read back from the reopened
//      store.
//
//  "Captured fields" vs. assigned metadata. The repositories deliberately
//  (re)assign sync metadata at save time — `syncState = pending`,
//  `retryCount = 0`, `createdAt = now`, `lastAttemptAt = nil`,
//  `remoteId = nil` (see `GRDBLogRepository.freshPendingMetadata` and
//  `GRDBEscalationRepository.saveEscalation`) — and the escalation repository
//  also assigns the `photoRef` from the blob store. Those are store-assigned
//  values, not operator-captured input, so this property asserts equality of
//  the *captured* fields (identity, associations, measurements, timestamps,
//  location, flags) rather than of the transient sync bookkeeping. For the
//  escalation photo (Req 7.3) the captured photo bytes are verified to round
//  trip by reading them back from the blob store.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 1: Persistence round-trip`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class PersistenceRoundTripPropertyTests: XCTestCase {

    func testPersistenceRoundTrip() {
        property(
            propertyName(1, "Persistence round-trip"),
            arguments: pbtArguments()
        ) <- forAll(syncableCaseGenerator()) { (testCase: SyncableCase) in
            do {
                return try self.roundTripHolds(for: testCase)
            } catch {
                return false <?> "round-trip threw: \(error)"
            }
        }
    }

    // MARK: - Round-trip check

    /// Seeds the parent well, saves the generated record, closes and reopens
    /// the on-disk store, reads the record back, and asserts the captured
    /// fields match. Returns a labelled `Testable` so a mismatch names the
    /// offending case.
    private func roundTripHolds(for testCase: SyncableCase) throws -> Testable {
        let fileURL = Self.makeTempDatabaseURL()
        defer { try? FileManager.default.removeItem(at: fileURL) }

        let blobDir = Self.makeTempBlobDirectory()
        defer { try? FileManager.default.removeItem(at: blobDir) }

        // --- First "launch": open the on-disk store, seed the well, save. ---
        do {
            let appDB = try Self.openOnDisk(fileURL)
            try Self.seed(well: testCase.well, into: appDB)

            let blobStore = try BlobStore(directory: blobDir)
            let result = try Self.save(testCase, into: appDB, blobStore: blobStore)
            guard result == .success else {
                return false <?> "save did not succeed: \(result)"
            }
            // `appDB` (and its DatabaseQueue) deallocates at scope exit,
            // closing the SQLite connection — a genuine close before reopen.
        }

        // --- Second "launch": reopen the same file; the record must reload. ---
        let reopened = try Self.openOnDisk(fileURL)
        let blobStore = try BlobStore(directory: blobDir)

        return try Self.capturedFieldsMatch(testCase, reopened: reopened, blobStore: blobStore)
    }

    // MARK: - Save (via the repositories / store)

    private static func save(
        _ testCase: SyncableCase,
        into appDB: AppDatabase,
        blobStore: BlobStore
    ) throws -> SaveResult {
        switch testCase.record {
        case .wellTest(let test):
            let repo = GRDBLogRepository(appDatabase: appDB, storageProbe: AmpleStorageProbe())
            return runBlocking { await repo.saveWellTest(test) }

        case .wellheadLog(let log):
            let repo = GRDBLogRepository(appDatabase: appDB, storageProbe: AmpleStorageProbe())
            return runBlocking { await repo.saveWellheadLog(log) }

        case .escalation(let escalation, let photoData):
            let repo = GRDBEscalationRepository(
                appDatabase: appDB,
                blobStore: blobStore,
                storageProbe: AmpleStorageProbe()
            )
            return runBlocking { await repo.saveEscalation(escalation, photoData: photoData) }

        case .subcoolReading(let reading):
            // No repository for subcool readings: persist through the record
            // type directly — a save to the Local_Store all the same.
            try appDB.dbWriter.write { db in
                try SubcoolReadingRecord(reading).insert(db)
            }
            return .success
        }
    }

    // MARK: - Captured-field equality after reopen

    private static func capturedFieldsMatch(
        _ testCase: SyncableCase,
        reopened appDB: AppDatabase,
        blobStore: BlobStore
    ) throws -> Testable {
        switch testCase.record {
        case .wellTest(let saved):
            let reloaded: WellTest? = try appDB.dbWriter.read { db in
                try WellTestRecord.fetchOne(db, key: saved.id.uuidString)?.domain
            }
            guard let reloaded else {
                return false <?> "well test \(saved.id) did not reload after reopen"
            }
            let equal = reloaded.id == saved.id
                && reloaded.wellId == saved.wellId
                && reloaded.oilRatio == saved.oilRatio
                && reloaded.waterRatio == saved.waterRatio
                && reloaded.gasRatio == saved.gasRatio
                && reloaded.capturedAt == saved.capturedAt
            return equal
                <?> "well test captured fields differ after reopen: saved \(saved) vs reloaded \(reloaded)"

        case .wellheadLog(let saved):
            let reloaded: WellheadLog? = try appDB.dbWriter.read { db in
                try WellheadLogRecord.fetchOne(db, key: saved.id.uuidString)?.domain
            }
            guard let reloaded else {
                return false <?> "wellhead log \(saved.id) did not reload after reopen"
            }
            let equal = reloaded.id == saved.id
                && reloaded.wellId == saved.wellId
                && reloaded.logType == saved.logType
                && reloaded.capturedAt == saved.capturedAt
                && reloaded.emulsionFlowRate == saved.emulsionFlowRate
                && reloaded.casingGasPressure == saved.casingGasPressure
                && reloaded.espMotorTemperature == saved.espMotorTemperature
                && reloaded.waterCutPercent == saved.waterCutPercent
                && reloaded.steamInjectionPressure == saved.steamInjectionPressure
                && reloaded.steamTemperature == saved.steamTemperature
                && reloaded.steamFlowRate == saved.steamFlowRate
            return equal
                <?> "wellhead log captured fields differ after reopen: saved \(saved) vs reloaded \(reloaded)"

        case .subcoolReading(let saved):
            let reloaded: SubcoolReading? = try appDB.dbWriter.read { db in
                try SubcoolReadingRecord.fetchOne(db, key: saved.id.uuidString)?.domain
            }
            guard let reloaded else {
                return false <?> "subcool reading \(saved.id) did not reload after reopen"
            }
            // Saved directly through the record type, so the whole value —
            // including its optional sync metadata — must round-trip exactly.
            let equal = reloaded == saved
            return equal
                <?> "subcool reading differs after reopen: saved \(saved) vs reloaded \(reloaded)"

        case .escalation(let saved, let photoData):
            let reloaded: Escalation? = try appDB.dbWriter.read { db in
                try EscalationRecord.fetchOne(db, key: saved.id.uuidString)?.domain
            }
            guard let reloaded else {
                return false <?> "escalation \(saved.id) did not reload after reopen"
            }
            // `photoRef` is assigned by the blob store at save time, so compare
            // the operator-captured fields rather than the store-assigned ref.
            let fieldsEqual = reloaded.id == saved.id
                && reloaded.wellId == saved.wellId
                && reloaded.priority == saved.priority
                && reloaded.latitude == saved.latitude
                && reloaded.longitude == saved.longitude
                && reloaded.locationUnavailable == saved.locationUnavailable
                && reloaded.capturedAt == saved.capturedAt

            // The captured photo (Req 7.3) must round-trip byte-for-byte: read
            // it back from the blob store using the reloaded row's photoRef.
            let reloadedPhoto = try blobStore.read(photoRef: reloaded.photoRef)
            let photoEqual = reloadedPhoto == photoData
            let escalationEqual = fieldsEqual && photoEqual

            return escalationEqual
                <?> "escalation captured fields/photo differ after reopen: saved \(saved) vs reloaded \(reloaded); photoMatch=\(photoEqual)"
        }
    }

    // MARK: - Store helpers

    /// Opens (creating if needed) a migrated AppDatabase over an on-disk file,
    /// with foreign keys enforced so the seeded-well FK is required.
    private static func openOnDisk(_ fileURL: URL) throws -> AppDatabase {
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        let queue = try DatabaseQueue(path: fileURL.path, configuration: configuration)
        return try AppDatabase(queue)
    }

    private static func seed(well: Well, into appDB: AppDatabase) throws {
        try appDB.dbWriter.write { db in
            try WellRecord(well).insert(db)
        }
    }

    private static func makeTempDatabaseURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("roundtrip-\(UUID().uuidString).sqlite")
    }

    private static func makeTempBlobDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("roundtrip-blobs-\(UUID().uuidString)", isDirectory: true)
    }

    /// Runs an async operation to completion from the synchronous SwiftCheck
    /// predicate. The repositories are `async`; this bridges them without
    /// changing their contract.
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

    /// A storage probe that always reports ample free space, so the
    /// repositories' pre-write free-space check never turns this
    /// persistence property into a `storageFull` result. This test is about
    /// the round trip of a successfully saved record; the `storageFull`
    /// boundary is covered separately by task 5.6.
    private struct AmpleStorageProbe: StorageProbe {
        func availableCapacityBytes() -> Int64? { Int64.max }
    }

    // MARK: - Generated case

    /// One syncable record of some type, plus the parent well it associates
    /// with (seeded before the save so the FK holds).
    private struct SyncableCase {
        let well: Well
        let record: Record

        enum Record {
            case wellTest(WellTest)
            case wellheadLog(WellheadLog)
            case subcoolReading(SubcoolReading)
            case escalation(Escalation, photoData: Data)
        }
    }

    // MARK: - Generators

    // Generators use `Gen.compose` throughout (the pattern already proven in
    // CaptureRequiresNoNetworkPropertyTests) so each case is assembled from
    // independent primitive draws without relying on high-arity `Gen.zip`.

    /// Draws a syncable case of one of the four types with equal weight, so
    /// every syncable record type is exercised across the 100+ iterations.
    private func syncableCaseGenerator() -> Gen<SyncableCase> {
        Gen<SyncableCase>.one(of: [
            wellTestCaseGenerator(),
            wellheadLogCaseGenerator(),
            subcoolReadingCaseGenerator(),
            escalationCaseGenerator(),
        ])
    }

    // MARK: Primitive draws

    private static let wellIdGen: Gen<String> =
        Gen<Int>.choose((1, 1_000_000)).map { "WELL-\($0)" }

    /// Capture timestamps at 1-second precision (the captured-timestamp
    /// contract), as plain epoch seconds so the stored/reloaded `Date`
    /// compares exactly.
    private static let timestampGen: Gen<Date> =
        Gen<Int>.choose((0, 2_000_000_000)).map { Date(timeIntervalSince1970: TimeInterval($0)) }

    /// Well-test oil/water/gas ratio in a plausible [0, 1] range.
    private static let ratioGen: Gen<Double> = Gen<Double>.choose((0.0, 1.0))

    /// A production-field measurement in [0.00, 999,999.99].
    private static let measurementGen: Gen<Double> = Gen<Double>.choose((0.0, 999_999.99))

    /// Water cut percentage in [0, 100].
    private static let percentGen: Gen<Double> = Gen<Double>.choose((0.0, 100.0))

    /// Steam injection pressure in [0, 25,000].
    private static let steamPressureGen: Gen<Double> = Gen<Double>.choose((0.0, 25_000.0))

    /// Steam temperature / pump temperature in [0, 400].
    private static let steamTemperatureGen: Gen<Double> = Gen<Double>.choose((0.0, 400.0))

    /// Steam flow rate in [0, 2,000].
    private static let steamFlowGen: Gen<Double> = Gen<Double>.choose((0.0, 2_000.0))

    /// A parent well whose id each generated record associates with. The type
    /// is drawn so both production and injection parents appear; the FK only
    /// needs the id to exist, so the type does not constrain the child record.
    private static func makeWell(_ c: GenComposer) -> Well {
        let id: String = c.generate(using: wellIdGen)
        let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
        return Well(
            id: id,
            name: "Well \(id)",
            type: isProduction ? .production : .injection,
            padId: nil,
            updatedAt: c.generate(using: timestampGen)
        )
    }

    // MARK: Per-type case generators

    private func wellTestCaseGenerator() -> Gen<SyncableCase> {
        Gen<SyncableCase>.compose { c in
            let well = Self.makeWell(c)
            let test = WellTest(
                wellId: well.id,
                oilRatio: c.generate(using: Self.ratioGen),
                waterRatio: c.generate(using: Self.ratioGen),
                gasRatio: c.generate(using: Self.ratioGen),
                capturedAt: c.generate(using: Self.timestampGen)
            )
            return SyncableCase(well: well, record: .wellTest(test))
        }
    }

    private func wellheadLogCaseGenerator() -> Gen<SyncableCase> {
        Gen<SyncableCase>.compose { c in
            let well = Self.makeWell(c)
            let capturedAt: Date = c.generate(using: Self.timestampGen)
            let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))

            // Each branch populates exactly its own mutually exclusive field
            // group, leaving the other group nil.
            let log: WellheadLog
            if isProduction {
                log = WellheadLog(
                    wellId: well.id,
                    logType: .production,
                    capturedAt: capturedAt,
                    emulsionFlowRate: c.generate(using: Self.measurementGen),
                    casingGasPressure: c.generate(using: Self.measurementGen),
                    espMotorTemperature: c.generate(using: Self.measurementGen),
                    waterCutPercent: c.generate(using: Self.percentGen)
                )
            } else {
                log = WellheadLog(
                    wellId: well.id,
                    logType: .injection,
                    capturedAt: capturedAt,
                    steamInjectionPressure: c.generate(using: Self.steamPressureGen),
                    steamTemperature: c.generate(using: Self.steamTemperatureGen),
                    steamFlowRate: c.generate(using: Self.steamFlowGen)
                )
            }
            return SyncableCase(well: well, record: .wellheadLog(log))
        }
    }

    private func subcoolReadingCaseGenerator() -> Gen<SyncableCase> {
        Gen<SyncableCase>.compose { c in
            let well = Self.makeWell(c)
            let capturedAt: Date = c.generate(using: Self.timestampGen)

            let casing: Double = c.generate(using: Self.measurementGen)
            let pump: Double = c.generate(using: Self.steamTemperatureGen)
            let tsat: Double = c.generate(using: Self.steamTemperatureGen)
            let subcool = tsat - pump

            // `wellId` is optional on a subcool reading (FK is set-null):
            // generate both an attached and a standalone (nil) reading so the
            // optional association round-trips either way. Sync metadata is
            // optional here too; generate both present and absent.
            let attach: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
            let hasSync: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))

            let reading = SubcoolReading(
                wellId: attach ? well.id : nil,
                casingPressure: casing,
                pumpTemperature: pump,
                saturationTemperature: tsat,
                subcoolValue: subcool,
                isFlashing: subcool <= 10.0,
                capturedAt: capturedAt,
                sync: hasSync ? SyncMetadata(createdAt: capturedAt) : nil
            )
            return SyncableCase(well: well, record: .subcoolReading(reading))
        }
    }

    private func escalationCaseGenerator() -> Gen<SyncableCase> {
        Gen<SyncableCase>.compose { c in
            let well = Self.makeWell(c)
            let capturedAt: Date = c.generate(using: Self.timestampGen)
            let priority: EscalationPriority = c.generate(
                using: Gen<EscalationPriority>.fromElements(of: EscalationPriority.allCases)
            )

            // Either a decimal-degree lat/long fix, or a "location unavailable"
            // fix (nil coordinates, flag set) — both captured outcomes
            // (Req 7.1, 7.5) must round-trip.
            let hasFix: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
            let latitude: Double? = hasFix ? c.generate(using: Gen<Double>.choose((-90.0, 90.0))) : nil
            let longitude: Double? = hasFix ? c.generate(using: Gen<Double>.choose((-180.0, 180.0))) : nil

            // Non-empty photo bytes of varied length so the blob round-trip is
            // a real byte-for-byte comparison, not a trivial empty payload.
            let photoLength: Int = c.generate(using: Gen<Int>.choose((1, 512)))
            let photoData = Data((0..<photoLength).map { _ in
                UInt8(c.generate(using: Gen<Int>.choose((0, 255))))
            })

            let escalation = Escalation(
                wellId: well.id,
                priority: priority,
                // The repository assigns the real photoRef (blob written
                // first), so a placeholder here is overwritten on save.
                photoRef: "placeholder",
                latitude: latitude,
                longitude: longitude,
                locationUnavailable: !hasFix,
                capturedAt: capturedAt
            )
            return SyncableCase(well: well, record: .escalation(escalation, photoData: photoData))
        }
    }
}
