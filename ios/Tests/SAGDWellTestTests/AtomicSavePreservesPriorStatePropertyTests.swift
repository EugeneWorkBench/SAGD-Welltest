//
//  AtomicSavePreservesPriorStatePropertyTests.swift
//
//  Property 3: Atomic save — failure preserves prior state (iOS) — task 5.4.
//
//  Validates: Requirements 1.4, 7.4
//
//  Design "Property 3: Atomic save — failure preserves prior state":
//
//      For any save that encounters an injected write failure, the save
//      returns `.failure` (never `.success`), the Local_Store's contents are
//      byte-for-byte identical to what they were immediately before the
//      attempt (no partial row is committed — Req 1.4), and for the escalation
//      path no orphan photo blob is left behind in the blob store (Req 7.4).
//
//  How the failure is injected (and why). The repositories never expose a seam
//  that lets a test *simulate* a mid-transaction crash directly, and injecting
//  one would require production changes. Instead this test drives the real save
//  paths into their genuine failure branches using the store's own integrity
//  rules, exactly as the task guidance suggests:
//
//    * Log / well-test paths (`GRDBLogRepository`). The `wellTest` and
//      `wellheadLog` tables declare `wellId NOT NULL REFERENCES well(...)`, and
//      the test database opens with foreign keys enforced (see
//      `AppDatabase.makeInMemory`). Saving a record whose `wellId` points at a
//      well that was never seeded makes `record.insert(db)` throw a GRDB
//      `DatabaseError` with `SQLITE_CONSTRAINT`. GRDB's `write` wraps the body
//      in a transaction and rolls it back on any thrown error, so nothing is
//      committed; `SaveErrorMapping` classifies the non-disk-full error as
//      `.failure`. This is a faithful "injected write failure" — a real I/O /
//      integrity error on the write path — not a mock.
//
//    * Escalation path (`GRDBEscalationRepository`). Two independent failure
//      points are exercised across the generated cases:
//        - A *blob-write* failure: the escalation repository writes the photo
//          blob before the row. The harness points the blob store at a
//          directory whose path is then replaced by a regular file, so
//          `BlobStore.write` (a plain `Data.write(to:options:.atomic)`) fails
//          with an I/O error before any row is attempted. Nothing is written to
//          either store, so there is trivially no orphan blob.
//        - A *row-insert* failure after the blob is written: the escalation's
//          `wellId` references an unseeded well, so the blob is written first,
//          then the row insert throws the FK `DatabaseError`. The repository's
//          catch deletes the just-written blob (Req 7.4). The harness asserts
//          the blob store is left with exactly the blobs it had before — no
//          orphan.
//
//  What "store unchanged" is checked against. Before each attempt the harness
//  snapshots every table (well, wellTest, wellheadLog, subcoolReading,
//  maintenanceTask, escalation) as sorted row arrays, plus the set of blob
//  files on disk. The prior state is non-trivial: a parent well and one
//  already-saved record of each syncable kind are seeded first, so the
//  assertion proves the failed save neither added a partial row nor disturbed
//  the existing rows. After the attempt the same snapshot is taken and compared
//  for exact equality.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 3: Atomic save — failure preserves
//  prior state`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class AtomicSavePreservesPriorStatePropertyTests: XCTestCase {

    func testAtomicSaveFailurePreservesPriorState() {
        property(
            propertyName(3, "Atomic save — failure preserves prior state"),
            arguments: pbtArguments()
        ) <- forAll(failureCaseGenerator()) { (testCase: FailureCase) in
            do {
                return try self.atomicSaveFailureHolds(for: testCase)
            } catch {
                return false <?> "harness threw: \(error)"
            }
        }
    }

    // MARK: - The property check for one generated case

    /// Seeds prior state, snapshots the store, drives the chosen save path into
    /// its injected-failure branch, and asserts: the result is `.failure`, the
    /// store is byte-for-byte unchanged, and (escalation) no orphan blob
    /// remains.
    private func atomicSaveFailureHolds(for testCase: FailureCase) throws -> Testable {
        let dbURL = Self.makeTempDatabaseURL()
        defer { try? FileManager.default.removeItem(at: dbURL) }

        let blobDir = Self.makeTempBlobDirectory()
        defer { try? FileManager.default.removeItem(at: blobDir) }

        let appDB = try Self.openOnDisk(dbURL)
        let blobStore = try BlobStore(directory: blobDir)

        // --- Establish non-trivial prior state the failed save must not touch. ---
        try Self.seedPriorState(testCase.priorState, into: appDB, blobStore: blobStore)

        let before = try Self.snapshot(appDB: appDB, blobDir: blobDir)

        // --- Attempt the save that is engineered to fail. ---
        let result = try Self.attemptFailingSave(
            testCase,
            appDB: appDB,
            blobDir: blobDir
        )

        let after = try Self.snapshot(appDB: appDB, blobDir: blobDir)

        // 1. The save must report failure, never success (and not be silently
        //    reclassified as storageFull — the injected error is an integrity /
        //    I/O failure, not disk-full).
        let reportedFailure: Bool
        switch result {
        case .failure:
            reportedFailure = true
        case .success, .storageFull:
            reportedFailure = false
        }

        // 2. The store (all tables) must be exactly as it was before.
        let storeUnchanged = before.rows == after.rows

        // 3. The blob set must be exactly as it was before — no orphan left and
        //    nothing pre-existing removed (Req 7.4).
        let blobsUnchanged = before.blobRefs == after.blobRefs

        let allHold = reportedFailure && storeUnchanged && blobsUnchanged

        return allHold
            <?> """
            Property 3 violated for \(testCase.label):
              result=\(result) (expected .failure)
              storeUnchanged=\(storeUnchanged) \
            (before rows=\(before.rows.counts), after rows=\(after.rows.counts))
              blobsUnchanged=\(blobsUnchanged) \
            (before blobs=\(before.blobRefs.sorted()), after blobs=\(after.blobRefs.sorted()))
            """
    }

    // MARK: - Driving each save path into failure

    private static func attemptFailingSave(
        _ testCase: FailureCase,
        appDB: AppDatabase,
        blobDir: URL
    ) throws -> SaveResult {
        switch testCase.injection {
        case .wellTestForeignKeyViolation(let test):
            // `test.wellId` references an unseeded well -> FK constraint ->
            // DatabaseError -> rollback -> .failure.
            let repo = GRDBLogRepository(appDatabase: appDB, storageProbe: AmpleStorageProbe())
            return runBlocking { await repo.saveWellTest(test) }

        case .wellheadLogForeignKeyViolation(let log):
            let repo = GRDBLogRepository(appDatabase: appDB, storageProbe: AmpleStorageProbe())
            return runBlocking { await repo.saveWellheadLog(log) }

        case .escalationBlobWriteFailure(let escalation, let photoData):
            // Force the *blob* write to fail by handing the repository a blob
            // store whose directory path is occupied by a regular file, so
            // `Data.write(to:)` into it fails before any row is attempted.
            let broken = try makeBlobStoreThatFailsWrites()
            defer { try? FileManager.default.removeItem(at: broken.filePath) }
            let repo = GRDBEscalationRepository(
                appDatabase: appDB,
                blobStore: broken.store,
                storageProbe: AmpleStorageProbe()
            )
            return runBlocking { await repo.saveEscalation(escalation, photoData: photoData) }

        case .escalationRowForeignKeyViolation(let escalation, let photoData):
            // A real blob store (same dir the snapshot watches): the blob is
            // written first, then the row insert fails on the FK violation, and
            // the repository must delete the orphan blob (Req 7.4).
            let blobStore = try BlobStore(directory: blobDir)
            let repo = GRDBEscalationRepository(
                appDatabase: appDB,
                blobStore: blobStore,
                storageProbe: AmpleStorageProbe()
            )
            return runBlocking { await repo.saveEscalation(escalation, photoData: photoData) }
        }
    }

    /// A blob store whose write path is guaranteed to fail, plus the path of
    /// the stray file standing in for its directory (so the caller can delete
    /// it afterwards).
    private struct BrokenBlobStore {
        let store: BlobStore
        let filePath: URL
    }

    /// Builds a `BlobStore` whose write path is guaranteed to fail: the store
    /// is created over a temp directory, the directory is then removed and a
    /// regular file is put in its place, so every `Data.write(to:)` into a
    /// child path throws an I/O error. The store itself is already constructed,
    /// so this does not trip `BlobStore.init`'s directory validation.
    private static func makeBlobStoreThatFailsWrites() throws -> BrokenBlobStore {
        let name = "sagd-prop3-broken-\(UUID().uuidString)"
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(name, isDirectory: true)
        let store = try BlobStore(directory: dir)
        // Replace the directory with a regular file at the same path, so every
        // `Data.write(to:)` into a child path (`<dir>/<ref>.blob`) fails with an
        // I/O error — the directory component is now a file, not a directory.
        try FileManager.default.removeItem(at: dir)
        let filePath = FileManager.default.temporaryDirectory
            .appendingPathComponent(name, isDirectory: false)
        try Data([0x00]).write(to: filePath, options: .atomic)
        return BrokenBlobStore(store: store, filePath: filePath)
    }

    // MARK: - Snapshotting the store

    /// A comparable snapshot of the whole local store: every table's rows
    /// (sorted for a stable comparison) plus the set of blob file names on disk.
    private struct StoreSnapshot {
        var rows: TableRows
        var blobRefs: Set<String>
    }

    /// Sorted row arrays for every table, compared for exact equality. Using
    /// the record types directly (which are `Equatable`) means any added,
    /// removed, or mutated column in any row changes the snapshot.
    private struct TableRows: Equatable {
        var wells: [WellRecord]
        var wellTests: [WellTestRecord]
        var wellheadLogs: [WellheadLogRecord]
        var subcoolReadings: [SubcoolReadingRecord]
        var maintenanceTasks: [MaintenanceTaskRecord]
        var escalations: [EscalationRecord]

        /// Per-table row counts, for a readable failure label.
        var counts: [String: Int] {
            [
                "well": wells.count,
                "wellTest": wellTests.count,
                "wellheadLog": wellheadLogs.count,
                "subcoolReading": subcoolReadings.count,
                "maintenanceTask": maintenanceTasks.count,
                "escalation": escalations.count,
            ]
        }
    }

    private static func snapshot(appDB: AppDatabase, blobDir: URL) throws -> StoreSnapshot {
        let rows: TableRows = try appDB.dbWriter.read { db in
            TableRows(
                wells: try WellRecord.fetchAll(db).sorted { $0.id < $1.id },
                wellTests: try WellTestRecord.fetchAll(db).sorted { $0.id < $1.id },
                wellheadLogs: try WellheadLogRecord.fetchAll(db).sorted { $0.id < $1.id },
                subcoolReadings: try SubcoolReadingRecord.fetchAll(db).sorted { $0.id < $1.id },
                maintenanceTasks: try MaintenanceTaskRecord.fetchAll(db).sorted { $0.id < $1.id },
                escalations: try EscalationRecord.fetchAll(db).sorted { $0.id < $1.id }
            )
        }
        let blobRefs = try Self.blobRefs(in: blobDir)
        return StoreSnapshot(rows: rows, blobRefs: blobRefs)
    }

    /// The set of blob file names currently on disk in the blob directory.
    private static func blobRefs(in blobDir: URL) throws -> Set<String> {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: blobDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        return Set(contents.map { $0.lastPathComponent })
    }

    // MARK: - Seeding prior state

    /// Seeds the parent well and one already-saved record of each syncable kind
    /// so the "store unchanged" assertion is checked against real prior data,
    /// not an empty store. The escalation's blob is written through the same
    /// blob store so a pre-existing blob is present on disk.
    private static func seedPriorState(
        _ prior: PriorState,
        into appDB: AppDatabase,
        blobStore: BlobStore
    ) throws {
        // Well + maintenance task are pulled reference rows: write directly.
        try appDB.dbWriter.write { db in
            try WellRecord(prior.well).insert(db)
            try MaintenanceTaskRecord(prior.maintenanceTask).insert(db)
        }

        // Syncable records: save through the repositories so prior state looks
        // exactly like genuinely-captured data (fresh pending metadata etc.).
        let logRepo = GRDBLogRepository(appDatabase: appDB, storageProbe: AmpleStorageProbe())
        let wtResult = runBlocking { await logRepo.saveWellTest(prior.wellTest) }
        guard wtResult == .success else {
            throw SeedError.saveFailed("prior wellTest: \(wtResult)")
        }
        let logResult = runBlocking { await logRepo.saveWellheadLog(prior.wellheadLog) }
        guard logResult == .success else {
            throw SeedError.saveFailed("prior wellheadLog: \(logResult)")
        }

        // Subcool reading has no repository; persist via its record type.
        try appDB.dbWriter.write { db in
            try SubcoolReadingRecord(prior.subcoolReading).insert(db)
        }

        let escRepo = GRDBEscalationRepository(
            appDatabase: appDB,
            blobStore: blobStore,
            storageProbe: AmpleStorageProbe()
        )
        let escResult = runBlocking {
            await escRepo.saveEscalation(prior.escalation, photoData: prior.escalationPhoto)
        }
        guard escResult == .success else {
            throw SeedError.saveFailed("prior escalation: \(escResult)")
        }
    }

    private enum SeedError: Error { case saveFailed(String) }

    // MARK: - Store helpers

    private static func openOnDisk(_ fileURL: URL) throws -> AppDatabase {
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        let queue = try DatabaseQueue(path: fileURL.path, configuration: configuration)
        return try AppDatabase(queue)
    }

    private static func makeTempDatabaseURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("atomicsave-\(UUID().uuidString).sqlite")
    }

    private static func makeTempBlobDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("atomicsave-blobs-\(UUID().uuidString)", isDirectory: true)
    }

    /// Bridges the repositories' `async` API to SwiftCheck's synchronous
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

    /// Always reports ample space so the pre-write free-space check never
    /// short-circuits this test into `storageFull`; the injected failure is an
    /// integrity / I/O error, which is the subject of this property.
    private struct AmpleStorageProbe: StorageProbe {
        func availableCapacityBytes() -> Int64? { Int64.max }
    }

    // MARK: - Generated case

    /// One generated failure scenario: the prior state to seed, the specific
    /// injected-failure save to attempt, and a human-readable label.
    private struct FailureCase {
        var priorState: PriorState
        var injection: Injection
        var label: String
    }

    /// The injected-failure save under test. Each case is engineered to drive a
    /// real repository save path into a genuine failure branch.
    private enum Injection {
        /// Well test whose `wellId` references an unseeded well (FK violation).
        case wellTestForeignKeyViolation(WellTest)
        /// Wellhead log whose `wellId` references an unseeded well (FK violation).
        case wellheadLogForeignKeyViolation(WellheadLog)
        /// Escalation whose blob write fails before any row is attempted.
        case escalationBlobWriteFailure(Escalation, photoData: Data)
        /// Escalation whose blob writes but the row insert fails (FK violation),
        /// so the repository must delete the orphan blob.
        case escalationRowForeignKeyViolation(Escalation, photoData: Data)
    }

    /// The non-trivial prior state seeded before each attempt: a parent well, a
    /// reference maintenance task, and one saved record of every syncable kind.
    private struct PriorState {
        var well: Well
        var maintenanceTask: MaintenanceTask
        var wellTest: WellTest
        var wellheadLog: WellheadLog
        var subcoolReading: SubcoolReading
        var escalation: Escalation
        var escalationPhoto: Data
    }

    // MARK: - Generators

    private func failureCaseGenerator() -> Gen<FailureCase> {
        Gen<FailureCase>.one(of: [
            wellTestFKCaseGenerator(),
            wellheadLogFKCaseGenerator(),
            escalationBlobFailureCaseGenerator(),
            escalationRowFKCaseGenerator(),
        ])
    }

    // MARK: Primitive draws

    private static let seededWellIdGen: Gen<String> =
        Gen<Int>.choose((1, 1_000_000)).map { "SEEDED-WELL-\($0)" }

    /// A well id guaranteed *not* to be the seeded parent, so a record carrying
    /// it violates the foreign key. Disjoint prefix from `seededWellIdGen`.
    private static let missingWellIdGen: Gen<String> =
        Gen<Int>.choose((1, 1_000_000)).map { "MISSING-WELL-\($0)" }

    private static let timestampGen: Gen<Date> =
        Gen<Int>.choose((1_000_000_000, 2_000_000_000))
            .map { Date(timeIntervalSince1970: TimeInterval($0)) }

    private static let ratioGen: Gen<Double> = Gen<Double>.choose((0.0, 1.0))
    private static let measurementGen: Gen<Double> = Gen<Double>.choose((0.0, 999_999.99))
    private static let percentGen: Gen<Double> = Gen<Double>.choose((0.0, 100.0))
    private static let steamPressureGen: Gen<Double> = Gen<Double>.choose((0.0, 25_000.0))
    private static let steamTemperatureGen: Gen<Double> = Gen<Double>.choose((0.0, 400.0))
    private static let steamFlowGen: Gen<Double> = Gen<Double>.choose((0.0, 2_000.0))

    private static func photoData(_ c: GenComposer) -> Data {
        let length: Int = c.generate(using: Gen<Int>.choose((1, 512)))
        return Data((0..<length).map { _ in
            UInt8(c.generate(using: Gen<Int>.choose((0, 255))))
        })
    }

    private static func priority(_ c: GenComposer) -> EscalationPriority {
        c.generate(using: Gen<EscalationPriority>.fromElements(of: EscalationPriority.allCases))
    }

    /// Builds the seeded parent well plus one valid saved record of each
    /// syncable kind, all associated with that well.
    private static func makePriorState(_ c: GenComposer) -> PriorState {
        let wellId: String = c.generate(using: seededWellIdGen)
        let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
        let well = Well(
            id: wellId,
            name: "Well \(wellId)",
            type: isProduction ? .production : .injection,
            padId: nil,
            updatedAt: c.generate(using: timestampGen)
        )

        let maintenanceTask = MaintenanceTask(
            wellId: wellId,
            description: "prior task",
            status: c.generate(
                using: Gen<MaintenanceTaskStatus>.fromElements(of: MaintenanceTaskStatus.allCases)
            ),
            createdAt: c.generate(using: timestampGen)
        )

        let wellTest = WellTest(
            wellId: wellId,
            oilRatio: c.generate(using: ratioGen),
            waterRatio: c.generate(using: ratioGen),
            gasRatio: c.generate(using: ratioGen),
            capturedAt: c.generate(using: timestampGen)
        )

        let wellheadLog = makeProductionLog(wellId: wellId, c: c)

        let pump: Double = c.generate(using: steamTemperatureGen)
        let tsat: Double = c.generate(using: steamTemperatureGen)
        let subcoolReading = SubcoolReading(
            wellId: wellId,
            casingPressure: c.generate(using: measurementGen),
            pumpTemperature: pump,
            saturationTemperature: tsat,
            subcoolValue: tsat - pump,
            isFlashing: (tsat - pump) <= 10.0,
            capturedAt: c.generate(using: timestampGen),
            sync: SyncMetadata(createdAt: c.generate(using: timestampGen))
        )

        let escalation = Escalation(
            wellId: wellId,
            priority: priority(c),
            photoRef: "placeholder",
            latitude: c.generate(using: Gen<Double>.choose((-90.0, 90.0))),
            longitude: c.generate(using: Gen<Double>.choose((-180.0, 180.0))),
            locationUnavailable: false,
            capturedAt: c.generate(using: timestampGen)
        )

        return PriorState(
            well: well,
            maintenanceTask: maintenanceTask,
            wellTest: wellTest,
            wellheadLog: wellheadLog,
            subcoolReading: subcoolReading,
            escalation: escalation,
            escalationPhoto: photoData(c)
        )
    }

    private static func makeProductionLog(wellId: String, c: GenComposer) -> WellheadLog {
        WellheadLog(
            wellId: wellId,
            logType: .production,
            capturedAt: c.generate(using: timestampGen),
            emulsionFlowRate: c.generate(using: measurementGen),
            casingGasPressure: c.generate(using: measurementGen),
            espMotorTemperature: c.generate(using: measurementGen),
            waterCutPercent: c.generate(using: percentGen)
        )
    }

    // MARK: Per-injection case generators

    private func wellTestFKCaseGenerator() -> Gen<FailureCase> {
        Gen<FailureCase>.compose { c in
            let prior = Self.makePriorState(c)
            let missingWellId: String = c.generate(using: Self.missingWellIdGen)
            let test = WellTest(
                wellId: missingWellId,
                oilRatio: c.generate(using: Self.ratioGen),
                waterRatio: c.generate(using: Self.ratioGen),
                gasRatio: c.generate(using: Self.ratioGen),
                capturedAt: c.generate(using: Self.timestampGen)
            )
            return FailureCase(
                priorState: prior,
                injection: .wellTestForeignKeyViolation(test),
                label: "wellTest FK violation (wellId=\(missingWellId))"
            )
        }
    }

    private func wellheadLogFKCaseGenerator() -> Gen<FailureCase> {
        Gen<FailureCase>.compose { c in
            let prior = Self.makePriorState(c)
            let missingWellId: String = c.generate(using: Self.missingWellIdGen)
            let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
            let log: WellheadLog
            if isProduction {
                log = WellheadLog(
                    wellId: missingWellId,
                    logType: .production,
                    capturedAt: c.generate(using: Self.timestampGen),
                    emulsionFlowRate: c.generate(using: Self.measurementGen),
                    casingGasPressure: c.generate(using: Self.measurementGen),
                    espMotorTemperature: c.generate(using: Self.measurementGen),
                    waterCutPercent: c.generate(using: Self.percentGen)
                )
            } else {
                log = WellheadLog(
                    wellId: missingWellId,
                    logType: .injection,
                    capturedAt: c.generate(using: Self.timestampGen),
                    steamInjectionPressure: c.generate(using: Self.steamPressureGen),
                    steamTemperature: c.generate(using: Self.steamTemperatureGen),
                    steamFlowRate: c.generate(using: Self.steamFlowGen)
                )
            }
            return FailureCase(
                priorState: prior,
                injection: .wellheadLogForeignKeyViolation(log),
                label: "wellheadLog FK violation (wellId=\(missingWellId))"
            )
        }
    }

    private func escalationBlobFailureCaseGenerator() -> Gen<FailureCase> {
        Gen<FailureCase>.compose { c in
            let prior = Self.makePriorState(c)
            // Associates with the seeded well, so only the blob write (not the
            // FK) is the failure point.
            let escalation = Escalation(
                wellId: prior.well.id,
                priority: Self.priority(c),
                photoRef: "placeholder",
                latitude: c.generate(using: Gen<Double>.choose((-90.0, 90.0))),
                longitude: c.generate(using: Gen<Double>.choose((-180.0, 180.0))),
                locationUnavailable: false,
                capturedAt: c.generate(using: Self.timestampGen)
            )
            return FailureCase(
                priorState: prior,
                injection: .escalationBlobWriteFailure(escalation, photoData: Self.photoData(c)),
                label: "escalation blob-write failure"
            )
        }
    }

    private func escalationRowFKCaseGenerator() -> Gen<FailureCase> {
        Gen<FailureCase>.compose { c in
            let prior = Self.makePriorState(c)
            let missingWellId: String = c.generate(using: Self.missingWellIdGen)
            // Blob writes successfully; the row insert fails on the FK, so the
            // repository must delete the orphan blob (Req 7.4).
            let escalation = Escalation(
                wellId: missingWellId,
                priority: Self.priority(c),
                photoRef: "placeholder",
                latitude: c.generate(using: Gen<Double>.choose((-90.0, 90.0))),
                longitude: c.generate(using: Gen<Double>.choose((-180.0, 180.0))),
                locationUnavailable: false,
                capturedAt: c.generate(using: Self.timestampGen)
            )
            return FailureCase(
                priorState: prior,
                injection: .escalationRowForeignKeyViolation(escalation, photoData: Self.photoData(c)),
                label: "escalation row FK violation (wellId=\(missingWellId))"
            )
        }
    }
}
