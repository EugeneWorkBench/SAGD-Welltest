//
//  StorageFullRejectionTests.swift
//
//  Task 5.6 — Unit test: storage-full rejection (iOS).
//
//  Requirements: 1.5
//
//  Req 1.5: "IF available device storage is insufficient to persist a record,
//  THEN THE App SHALL reject the save operation, retain the Operator's entered
//  data in the active entry form, and display an error message indicating that
//  storage is full."
//
//  The repository layer enforces this with a pre-write free-space check driven
//  by an injectable `StorageProbe` (see `StorageProbe.swift`,
//  `GRDBLogRepository`, `GRDBEscalationRepository`). Rather than actually
//  filling the disk, this test injects a probe that reports available capacity
//  *below* the required threshold and asserts the save:
//
//    * returns `.storageFull` (so the UI can show the dedicated storage-full
//      message and retain the in-form data — the "retain entered data"
//      obligation is satisfied by the save returning without consuming the
//      operator's values); and
//    * writes nothing to the store — the table's row count is unchanged — so no
//      partial record is left behind; and
//    * for an escalation additionally writes no photo blob — the blob store is
//      left empty — confirming the free-space check happens *before* the
//      blob-first write path.
//
//  Boundary. The log repositories reject when `available < minimumFreeSpaceBytes`
//  and the escalation repository rejects when
//  `available < minimumFreeSpaceBytes + photoData.count`. The "insufficient
//  storage" cases drive the probe one byte below each threshold; the companion
//  "exactly at the threshold" cases drive the probe to exactly the threshold,
//  where the save must instead *succeed* and persist the row (and, for an
//  escalation, the blob). Pinning both sides of the `<` comparison catches a
//  regression that flips the boundary from `<` to `<=`.
//
//  The store is a throwaway in-memory `AppDatabase.makeInMemory()`; the
//  escalation blob store is a temporary directory removed after each case.

import XCTest
import GRDB
@testable import SAGDWellTest

final class StorageFullRejectionTests: XCTestCase {

    // MARK: - Controllable storage probe

    /// A `StorageProbe` that reports a fixed available-capacity value, so the
    /// repositories' pre-write free-space check can be driven deterministically
    /// without touching the real filesystem.
    private struct FixedStorageProbe: StorageProbe {
        let available: Int64
        func availableCapacityBytes() -> Int64? { available }
    }

    // MARK: - Fixtures

    /// A well id seeded as the FK parent for the saved records.
    private static let wellId = "WELL-1"

    private func makeWellTest() -> WellTest {
        WellTest(
            wellId: Self.wellId,
            oilRatio: 0.5,
            waterRatio: 0.3,
            gasRatio: 0.2,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    private func makeProductionLog() -> WellheadLog {
        WellheadLog(
            wellId: Self.wellId,
            logType: .production,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000),
            emulsionFlowRate: 12.5,
            casingGasPressure: 340.0,
            espMotorTemperature: 88.0,
            waterCutPercent: 42.0
        )
    }

    private func makeEscalation() -> Escalation {
        Escalation(
            wellId: Self.wellId,
            priority: .high,
            photoRef: "placeholder",
            latitude: 56.7,
            longitude: -111.4,
            locationUnavailable: false,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    /// A small non-empty photo payload for escalation saves.
    private static let photoData = Data([0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04])

    // MARK: - Store helpers

    /// A migrated in-memory store with the FK parent well already seeded.
    private func makeSeededStore() throws -> AppDatabase {
        let appDB = try AppDatabase.makeInMemory()
        try appDB.dbWriter.write { db in
            try WellRecord(
                Well(id: Self.wellId, name: "Well 1", type: .production,
                     updatedAt: Date(timeIntervalSince1970: 1_700_000_000))
            ).insert(db)
        }
        return appDB
    }

    /// Row count of a given table in the store.
    private func rowCount(_ table: String, in appDB: AppDatabase) throws -> Int {
        try appDB.dbWriter.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM \(table)") ?? 0
        }
    }

    /// A temporary blob directory removed when the returned teardown runs.
    private func makeTempBlobStore() throws -> (store: BlobStore, teardown: () -> Void) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("storage-full-blobs-\(UUID().uuidString)", isDirectory: true)
        let store = try BlobStore(directory: dir)
        return (store, { try? FileManager.default.removeItem(at: dir) })
    }

    /// Number of regular files currently in the blob directory.
    private func blobFileCount(_ store: BlobStore) throws -> Int {
        let contents = try FileManager.default.contentsOfDirectory(
            at: store.directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )
        return contents.count
    }

    /// Runs an async repository call to completion from a synchronous test.
    private func runBlocking<T: Sendable>(
        _ operation: @escaping @Sendable () async -> T
    ) -> T {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            box.value = await operation()
            semaphore.signal()
        }
        semaphore.wait()
        return box.value!
    }

    private final class ResultBox<T>: @unchecked Sendable {
        var value: T?
    }

    // MARK: - Insufficient storage rejects the save and writes nothing

    /// `saveWellTest` with free space one byte below the minimum returns
    /// `.storageFull` and leaves the `wellTest` table empty.
    func testSaveWellTestRejectedWhenStorageInsufficient() throws {
        let appDB = try makeSeededStore()
        let repo = GRDBLogRepository(
            appDatabase: appDB,
            storageProbe: FixedStorageProbe(available: minimumFreeSpaceBytes - 1)
        )

        let before = try rowCount(Schema.Table.wellTest, in: appDB)
        let result = runBlocking { await repo.saveWellTest(self.makeWellTest()) }
        let after = try rowCount(Schema.Table.wellTest, in: appDB)

        XCTAssertEqual(result, .storageFull,
                       "insufficient storage must reject saveWellTest with .storageFull")
        XCTAssertEqual(after, before,
                       "a rejected saveWellTest must not write any row")
        XCTAssertEqual(after, 0, "nothing should have been persisted")
    }

    /// `saveWellheadLog` with free space one byte below the minimum returns
    /// `.storageFull` and leaves the `wellheadLog` table empty.
    func testSaveWellheadLogRejectedWhenStorageInsufficient() throws {
        let appDB = try makeSeededStore()
        let repo = GRDBLogRepository(
            appDatabase: appDB,
            storageProbe: FixedStorageProbe(available: minimumFreeSpaceBytes - 1)
        )

        let before = try rowCount(Schema.Table.wellheadLog, in: appDB)
        let result = runBlocking { await repo.saveWellheadLog(self.makeProductionLog()) }
        let after = try rowCount(Schema.Table.wellheadLog, in: appDB)

        XCTAssertEqual(result, .storageFull,
                       "insufficient storage must reject saveWellheadLog with .storageFull")
        XCTAssertEqual(after, before,
                       "a rejected saveWellheadLog must not write any row")
        XCTAssertEqual(after, 0, "nothing should have been persisted")
    }

    /// `saveEscalation` with free space one byte below the (baseline + photo)
    /// threshold returns `.storageFull`, writes no `escalation` row, and writes
    /// no photo blob.
    func testSaveEscalationRejectedWhenStorageInsufficient() throws {
        let appDB = try makeSeededStore()
        let (blobStore, teardown) = try makeTempBlobStore()
        defer { teardown() }

        // Escalation requires baseline + photo bytes; one byte short rejects.
        let required = minimumFreeSpaceBytes + Int64(Self.photoData.count)
        let repo = GRDBEscalationRepository(
            appDatabase: appDB,
            blobStore: blobStore,
            storageProbe: FixedStorageProbe(available: required - 1)
        )

        let rowsBefore = try rowCount(Schema.Table.escalation, in: appDB)
        let blobsBefore = try blobFileCount(blobStore)
        let result = runBlocking {
            await repo.saveEscalation(self.makeEscalation(), photoData: Self.photoData)
        }
        let rowsAfter = try rowCount(Schema.Table.escalation, in: appDB)
        let blobsAfter = try blobFileCount(blobStore)

        XCTAssertEqual(result, .storageFull,
                       "insufficient storage must reject saveEscalation with .storageFull")
        XCTAssertEqual(rowsAfter, rowsBefore,
                       "a rejected saveEscalation must not write any row")
        XCTAssertEqual(rowsAfter, 0, "nothing should have been persisted")
        XCTAssertEqual(blobsAfter, blobsBefore,
                       "a rejected saveEscalation must not write a photo blob")
        XCTAssertEqual(blobsAfter, 0, "no blob should exist after a rejected save")
    }

    // MARK: - Boundary: exactly at the threshold the save succeeds

    /// With free space *exactly* at the minimum, `saveWellTest` is not rejected:
    /// the comparison is strict (`available < minimum`), so the row is written.
    func testSaveWellTestSucceedsExactlyAtThreshold() throws {
        let appDB = try makeSeededStore()
        let repo = GRDBLogRepository(
            appDatabase: appDB,
            storageProbe: FixedStorageProbe(available: minimumFreeSpaceBytes)
        )

        let result = runBlocking { await repo.saveWellTest(self.makeWellTest()) }
        let after = try rowCount(Schema.Table.wellTest, in: appDB)

        XCTAssertEqual(result, .success,
                       "exactly-at-threshold free space must not be treated as storage-full")
        XCTAssertEqual(after, 1, "the well test must be persisted at the boundary")
    }

    /// With free space exactly at the minimum, `saveWellheadLog` succeeds and
    /// persists the row.
    func testSaveWellheadLogSucceedsExactlyAtThreshold() throws {
        let appDB = try makeSeededStore()
        let repo = GRDBLogRepository(
            appDatabase: appDB,
            storageProbe: FixedStorageProbe(available: minimumFreeSpaceBytes)
        )

        let result = runBlocking { await repo.saveWellheadLog(self.makeProductionLog()) }
        let after = try rowCount(Schema.Table.wellheadLog, in: appDB)

        XCTAssertEqual(result, .success,
                       "exactly-at-threshold free space must not be treated as storage-full")
        XCTAssertEqual(after, 1, "the wellhead log must be persisted at the boundary")
    }

    /// With free space exactly at the (baseline + photo) threshold,
    /// `saveEscalation` succeeds, persisting both the row and the photo blob.
    func testSaveEscalationSucceedsExactlyAtThreshold() throws {
        let appDB = try makeSeededStore()
        let (blobStore, teardown) = try makeTempBlobStore()
        defer { teardown() }

        let required = minimumFreeSpaceBytes + Int64(Self.photoData.count)
        let repo = GRDBEscalationRepository(
            appDatabase: appDB,
            blobStore: blobStore,
            storageProbe: FixedStorageProbe(available: required)
        )

        let result = runBlocking {
            await repo.saveEscalation(self.makeEscalation(), photoData: Self.photoData)
        }
        let rowsAfter = try rowCount(Schema.Table.escalation, in: appDB)
        let blobsAfter = try blobFileCount(blobStore)

        XCTAssertEqual(result, .success,
                       "exactly-at-threshold free space must not be treated as storage-full")
        XCTAssertEqual(rowsAfter, 1, "the escalation row must be persisted at the boundary")
        XCTAssertEqual(blobsAfter, 1, "the photo blob must be written at the boundary")
    }
}
