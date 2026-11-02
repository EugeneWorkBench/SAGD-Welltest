//
//  Local Store record types
//
//  GRDB row types that map the pure domain entities onto the flat SQLite
//  schema defined in `Schema.swift`. The domain structs embed `SyncMetadata`
//  as a nested value; the schema stores those fields as flat columns
//  (`syncState`, `retryCount`, `createdAt`, `lastAttemptAt`, `remoteId`). These
//  row types perform that flattening in both directions so the repository
//  layer (task 5) can persist and reload domain values without GRDB trying to
//  encode a nested struct into a single column.
//
//  Each row type is a `Codable` `FetchableRecord` + `PersistableRecord` whose
//  stored properties are exactly the table's columns, plus a `domain` accessor
//  and an `init(_: DomainType)` for conversion. Introduced by task 3.1 to
//  anchor the schema; the repositories wire them in task 5.

import Foundation
import GRDB

// MARK: - WellRecord

/// Row mapping for the `well` table (pulled reference data; no sync metadata).
struct WellRecord: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = Schema.Table.well

    var id: String
    var name: String
    var type: String
    var padId: String?
    var updatedAt: Date

    init(_ well: Well) {
        self.id = well.id
        self.name = well.name
        self.type = well.type.rawValue
        self.padId = well.padId
        self.updatedAt = well.updatedAt
    }

    /// The domain `Well`, or `nil` if a stored enum value is unrecognized.
    var domain: Well? {
        guard let type = WellType(rawValue: type) else { return nil }
        return Well(id: id, name: name, type: type, padId: padId, updatedAt: updatedAt)
    }
}

// MARK: - WellTestRecord

/// Row mapping for the `wellTest` table with flattened `SyncMetadata`.
struct WellTestRecord: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = Schema.Table.wellTest

    var id: String
    var wellId: String
    var oilRatio: Double
    var waterRatio: Double
    var gasRatio: Double
    var capturedAt: Date

    // Flattened SyncMetadata columns.
    var syncState: String
    var retryCount: Int
    var createdAt: Date
    var lastAttemptAt: Date?
    var remoteId: String?

    init(_ test: WellTest) {
        self.id = test.id.uuidString
        self.wellId = test.wellId
        self.oilRatio = test.oilRatio
        self.waterRatio = test.waterRatio
        self.gasRatio = test.gasRatio
        self.capturedAt = test.capturedAt
        self.syncState = test.sync.syncState.rawValue
        self.retryCount = test.sync.retryCount
        self.createdAt = test.sync.createdAt
        self.lastAttemptAt = test.sync.lastAttemptAt
        self.remoteId = test.sync.remoteId
    }

    /// The domain `WellTest`, or `nil` if the id or sync state cannot be parsed.
    var domain: WellTest? {
        guard let uuid = UUID(uuidString: id),
              let sync = makeSyncMetadata(
                  syncState: syncState,
                  retryCount: retryCount,
                  createdAt: createdAt,
                  lastAttemptAt: lastAttemptAt,
                  remoteId: remoteId
              )
        else { return nil }
        return WellTest(
            id: uuid,
            wellId: wellId,
            oilRatio: oilRatio,
            waterRatio: waterRatio,
            gasRatio: gasRatio,
            capturedAt: capturedAt,
            sync: sync
        )
    }
}

// MARK: - WellheadLogRecord

/// Row mapping for the discriminated `wellheadLog` table with flattened
/// `SyncMetadata`. Production and injection columns are mutually exclusive and
/// nullable; `logType` selects which group is populated.
struct WellheadLogRecord: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = Schema.Table.wellheadLog

    var id: String
    var wellId: String
    var logType: String
    var capturedAt: Date

    // Production fields.
    var emulsionFlowRate: Double?
    var casingGasPressure: Double?
    var espMotorTemperature: Double?
    var waterCutPercent: Double?

    // Injection fields.
    var steamInjectionPressure: Double?
    var steamTemperature: Double?
    var steamFlowRate: Double?

    // Flattened SyncMetadata columns.
    var syncState: String
    var retryCount: Int
    var createdAt: Date
    var lastAttemptAt: Date?
    var remoteId: String?

    init(_ log: WellheadLog) {
        self.id = log.id.uuidString
        self.wellId = log.wellId
        self.logType = log.logType.rawValue
        self.capturedAt = log.capturedAt
        self.emulsionFlowRate = log.emulsionFlowRate
        self.casingGasPressure = log.casingGasPressure
        self.espMotorTemperature = log.espMotorTemperature
        self.waterCutPercent = log.waterCutPercent
        self.steamInjectionPressure = log.steamInjectionPressure
        self.steamTemperature = log.steamTemperature
        self.steamFlowRate = log.steamFlowRate
        self.syncState = log.sync.syncState.rawValue
        self.retryCount = log.sync.retryCount
        self.createdAt = log.sync.createdAt
        self.lastAttemptAt = log.sync.lastAttemptAt
        self.remoteId = log.sync.remoteId
    }

    /// The domain `WellheadLog`, or `nil` if the id, log type, or sync state
    /// cannot be parsed.
    var domain: WellheadLog? {
        guard let uuid = UUID(uuidString: id),
              let type = WellheadLogType(rawValue: logType),
              let sync = makeSyncMetadata(
                  syncState: syncState,
                  retryCount: retryCount,
                  createdAt: createdAt,
                  lastAttemptAt: lastAttemptAt,
                  remoteId: remoteId
              )
        else { return nil }
        return WellheadLog(
            id: uuid,
            wellId: wellId,
            logType: type,
            capturedAt: capturedAt,
            emulsionFlowRate: emulsionFlowRate,
            casingGasPressure: casingGasPressure,
            espMotorTemperature: espMotorTemperature,
            waterCutPercent: waterCutPercent,
            steamInjectionPressure: steamInjectionPressure,
            steamTemperature: steamTemperature,
            steamFlowRate: steamFlowRate,
            sync: sync
        )
    }
}

// MARK: - SubcoolReadingRecord

/// Row mapping for the `subcoolReading` table. The embedded `SyncMetadata` is
/// optional: a reading may be held without ever being queued for sync, so the
/// sync columns are all nullable and reconstructed into a `SyncMetadata?`.
struct SubcoolReadingRecord: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = Schema.Table.subcoolReading

    var id: String
    var wellId: String?
    var casingPressure: Double
    var pumpTemperature: Double
    var saturationTemperature: Double
    var subcoolValue: Double
    var isFlashing: Bool
    var capturedAt: Date

    // Optional flattened SyncMetadata columns (nil when never queued).
    var syncState: String?
    var retryCount: Int?
    var createdAt: Date?
    var lastAttemptAt: Date?
    var remoteId: String?

    init(_ reading: SubcoolReading) {
        self.id = reading.id.uuidString
        self.wellId = reading.wellId
        self.casingPressure = reading.casingPressure
        self.pumpTemperature = reading.pumpTemperature
        self.saturationTemperature = reading.saturationTemperature
        self.subcoolValue = reading.subcoolValue
        self.isFlashing = reading.isFlashing
        self.capturedAt = reading.capturedAt
        self.syncState = reading.sync?.syncState.rawValue
        self.retryCount = reading.sync?.retryCount
        self.createdAt = reading.sync?.createdAt
        self.lastAttemptAt = reading.sync?.lastAttemptAt
        self.remoteId = reading.sync?.remoteId
    }

    /// The domain `SubcoolReading`, or `nil` if the id cannot be parsed. Sync
    /// metadata is reconstructed only when a stored sync state is present.
    var domain: SubcoolReading? {
        guard let uuid = UUID(uuidString: id) else { return nil }

        var sync: SyncMetadata?
        if let syncState, let createdAt {
            sync = makeSyncMetadata(
                syncState: syncState,
                retryCount: retryCount ?? 0,
                createdAt: createdAt,
                lastAttemptAt: lastAttemptAt,
                remoteId: remoteId
            )
        }

        return SubcoolReading(
            id: uuid,
            wellId: wellId,
            casingPressure: casingPressure,
            pumpTemperature: pumpTemperature,
            saturationTemperature: saturationTemperature,
            subcoolValue: subcoolValue,
            isFlashing: isFlashing,
            capturedAt: capturedAt,
            sync: sync
        )
    }
}

// MARK: - MaintenanceTaskRecord

/// Row mapping for the `maintenanceTask` table (pulled reference data; no sync
/// metadata).
struct MaintenanceTaskRecord: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = Schema.Table.maintenanceTask

    var id: String
    var wellId: String
    var description: String
    var status: String
    var createdAt: Date

    init(_ task: MaintenanceTask) {
        self.id = task.id.uuidString
        self.wellId = task.wellId
        self.description = task.description
        self.status = task.status.rawValue
        self.createdAt = task.createdAt
    }

    /// The domain `MaintenanceTask`, or `nil` if the id or status cannot be
    /// parsed.
    var domain: MaintenanceTask? {
        guard let uuid = UUID(uuidString: id),
              let status = MaintenanceTaskStatus(rawValue: status)
        else { return nil }
        return MaintenanceTask(
            id: uuid,
            wellId: wellId,
            description: description,
            status: status,
            createdAt: createdAt
        )
    }
}

// MARK: - EscalationRecord

/// Row mapping for the `escalation` table with flattened `SyncMetadata`.
/// `photoRef` points at the blob in the file store; `latitude`/`longitude` are
/// nullable decimal degrees and `locationUnavailable` records a GPS timeout.
struct EscalationRecord: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = Schema.Table.escalation

    var id: String
    var wellId: String
    var priority: String
    var photoRef: String
    var latitude: Double?
    var longitude: Double?
    var locationUnavailable: Bool
    var capturedAt: Date

    // Flattened SyncMetadata columns.
    var syncState: String
    var retryCount: Int
    var createdAt: Date
    var lastAttemptAt: Date?
    var remoteId: String?

    init(_ escalation: Escalation) {
        self.id = escalation.id.uuidString
        self.wellId = escalation.wellId
        self.priority = escalation.priority.rawValue
        self.photoRef = escalation.photoRef
        self.latitude = escalation.latitude
        self.longitude = escalation.longitude
        self.locationUnavailable = escalation.locationUnavailable
        self.capturedAt = escalation.capturedAt
        self.syncState = escalation.sync.syncState.rawValue
        self.retryCount = escalation.sync.retryCount
        self.createdAt = escalation.sync.createdAt
        self.lastAttemptAt = escalation.sync.lastAttemptAt
        self.remoteId = escalation.sync.remoteId
    }

    /// The domain `Escalation`, or `nil` if the id, priority, or sync state
    /// cannot be parsed.
    var domain: Escalation? {
        guard let uuid = UUID(uuidString: id),
              let priority = EscalationPriority(rawValue: priority),
              let sync = makeSyncMetadata(
                  syncState: syncState,
                  retryCount: retryCount,
                  createdAt: createdAt,
                  lastAttemptAt: lastAttemptAt,
                  remoteId: remoteId
              )
        else { return nil }
        return Escalation(
            id: uuid,
            wellId: wellId,
            priority: priority,
            photoRef: photoRef,
            latitude: latitude,
            longitude: longitude,
            locationUnavailable: locationUnavailable,
            capturedAt: capturedAt,
            sync: sync
        )
    }
}

// MARK: - SyncMetadata reconstruction

/// Rebuilds a `SyncMetadata` from flat column values, returning `nil` if the
/// stored `syncState` raw value is unrecognized. Shared by all syncable row
/// types so reconstruction stays consistent.
private func makeSyncMetadata(
    syncState: String,
    retryCount: Int,
    createdAt: Date,
    lastAttemptAt: Date?,
    remoteId: String?
) -> SyncMetadata? {
    guard let state = SyncState(rawValue: syncState) else { return nil }
    return SyncMetadata(
        syncState: state,
        retryCount: retryCount,
        createdAt: createdAt,
        lastAttemptAt: lastAttemptAt,
        remoteId: remoteId
    )
}
