//
//  DatabaseSchemaTests.swift
//  Unit tests for the GRDB schema, migrations, and database opening (task 3.1).
//
//  Covers:
//    * The migrator creates every table defined in the design's Data Models.
//    * Each syncable table carries the embedded SyncMetadata columns.
//    * The wellheadLog table is a single discriminated table with the nullable
//      production and injection columns.
//    * The escalation table carries photoRef, nullable latitude/longitude, and
//      locationUnavailable.
//    * Opening an existing on-disk database reloads persisted records after a
//      simulated restart (Req 1.1, Req 1.3).
//

import XCTest
import GRDB
@testable import SAGDWellTest

final class DatabaseSchemaTests: XCTestCase {

    // MARK: - Table creation

    func testMigratorCreatesAllTables() throws {
        let appDB = try AppDatabase.makeInMemory()

        try appDB.dbWriter.read { db in
            for table in [
                "well", "wellTest", "wellheadLog",
                "subcoolReading", "maintenanceTask", "escalation"
            ] {
                XCTAssertTrue(try db.tableExists(table), "missing table: \(table)")
            }
        }
    }

    func testSyncableTablesHaveEmbeddedSyncMetadataColumns() throws {
        let appDB = try AppDatabase.makeInMemory()
        let syncColumns = ["syncState", "retryCount", "createdAt", "lastAttemptAt", "remoteId"]

        try appDB.dbWriter.read { db in
            for table in ["wellTest", "wellheadLog", "subcoolReading", "escalation"] {
                let columns = try db.columns(in: table).map(\.name)
                for expected in syncColumns {
                    XCTAssertTrue(
                        columns.contains(expected),
                        "table \(table) missing sync column \(expected)"
                    )
                }
            }
        }
    }

    func testWellheadLogHasDiscriminatedNullableColumns() throws {
        let appDB = try AppDatabase.makeInMemory()

        try appDB.dbWriter.read { db in
            let columns = try db.columns(in: "wellheadLog")
            let byName = Dictionary(uniqueKeysWithValues: columns.map { ($0.name, $0) })

            XCTAssertNotNil(byName["logType"])

            // Production and injection fields are mutually exclusive, hence all
            // nullable (not-null == false).
            for field in [
                "emulsionFlowRate", "casingGasPressure", "espMotorTemperature",
                "waterCutPercent", "steamInjectionPressure", "steamTemperature",
                "steamFlowRate"
            ] {
                let column = try XCTUnwrap(byName[field], "missing column \(field)")
                XCTAssertFalse(column.isNotNull, "\(field) should be nullable")
            }
        }
    }

    func testEscalationHasPhotoRefLocationColumns() throws {
        let appDB = try AppDatabase.makeInMemory()

        try appDB.dbWriter.read { db in
            let columns = try db.columns(in: "escalation")
            let byName = Dictionary(uniqueKeysWithValues: columns.map { ($0.name, $0) })

            let photoRef = try XCTUnwrap(byName["photoRef"])
            XCTAssertTrue(photoRef.isNotNull, "photoRef should be required")

            let latitude = try XCTUnwrap(byName["latitude"])
            let longitude = try XCTUnwrap(byName["longitude"])
            XCTAssertFalse(latitude.isNotNull, "latitude should be nullable")
            XCTAssertFalse(longitude.isNotNull, "longitude should be nullable")

            XCTAssertNotNil(byName["locationUnavailable"])
        }
    }

    // MARK: - Persist and reload round-trip

    func testInsertedRecordRoundTripsThroughRecordType() throws {
        let appDB = try AppDatabase.makeInMemory()

        let well = Well(id: "W-1", name: "North Pad A1", type: .production, updatedAt: date(1_000))
        let log = WellheadLog(
            id: UUID(),
            wellId: "W-1",
            logType: .production,
            capturedAt: date(2_000),
            emulsionFlowRate: 12.5,
            casingGasPressure: 340.0,
            espMotorTemperature: 88.0,
            waterCutPercent: 42.0
        )

        try appDB.dbWriter.write { db in
            try WellRecord(well).insert(db)
            try WellheadLogRecord(log).insert(db)
        }

        let reloaded: WellheadLog? = try appDB.dbWriter.read { db in
            try WellheadLogRecord.fetchOne(db, key: log.id.uuidString)?.domain
        }

        XCTAssertEqual(reloaded, log)
    }

    // MARK: - Restart reload (Req 1.3)

    func testRecordsPersistAcrossReopen() throws {
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("schema-reopen-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: fileURL) }

        let well = Well(id: "W-42", name: "South Pad", type: .injection, updatedAt: date(10))
        let escalation = Escalation(
            id: UUID(),
            wellId: "W-42",
            priority: .high,
            photoRef: "blob-1.jpg",
            latitude: 56.73,
            longitude: -111.38,
            locationUnavailable: false,
            capturedAt: date(20)
        )

        // First "launch": open, write, then let the queue deallocate.
        do {
            let config = makeConfiguration()
            let queue = try DatabaseQueue(path: fileURL.path, configuration: config)
            let appDB = try AppDatabase(queue)
            try appDB.dbWriter.write { db in
                try WellRecord(well).insert(db)
                try EscalationRecord(escalation).insert(db)
            }
        }

        // Second "launch": reopen the existing file; records must reload.
        let config = makeConfiguration()
        let queue = try DatabaseQueue(path: fileURL.path, configuration: config)
        let reopened = try AppDatabase(queue)

        let reloaded: Escalation? = try reopened.dbWriter.read { db in
            try EscalationRecord.fetchOne(db, key: escalation.id.uuidString)?.domain
        }
        XCTAssertEqual(reloaded, escalation)
    }

    // MARK: - Helpers

    private func makeConfiguration() -> Configuration {
        var config = Configuration()
        config.foreignKeysEnabled = true
        return config
    }

    private func date(_ seconds: TimeInterval) -> Date {
        // 1-second precision mirrors the captured-timestamp contract.
        Date(timeIntervalSince1970: seconds.rounded())
    }
}
