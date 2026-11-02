//
//  Local Store schema and migrations
//
//  Defines the SQLite schema for the on-device source of truth via a GRDB
//  `DatabaseMigrator`. There is one table per domain entity — `well`,
//  `wellTest`, `wellheadLog`, `subcoolReading`, `maintenanceTask`, and
//  `escalation`. Every syncable table embeds the same `SyncMetadata` columns
//  (`syncState`, `retryCount`, `createdAt`, `lastAttemptAt`, `remoteId`) so the
//  sync engine can treat the push queue as one homogeneous stream.
//
//  The schema mirrors the design document's Data Models section exactly:
//    * `wellheadLog` is a single discriminated table (`logType`) with mutually
//      exclusive, nullable production and injection columns.
//    * `escalation` stores `photoRef`, nullable `latitude`/`longitude`, and a
//      `locationUnavailable` flag.
//    * Captured records carry `SyncMetadata`; pulled reference data (`well`,
//      `maintenanceTask`) does not.
//
//  Implemented by task 3.1.

import Foundation
import GRDB

// MARK: - Schema column names

/// Central definition of table and `SyncMetadata` column names so the
/// migrations and the record types below agree on a single source of truth.
enum Schema {
    /// Table names, matching the design's entity names.
    enum Table {
        static let well = "well"
        static let wellTest = "wellTest"
        static let wellheadLog = "wellheadLog"
        static let subcoolReading = "subcoolReading"
        static let maintenanceTask = "maintenanceTask"
        static let escalation = "escalation"
    }

    /// The embedded `SyncMetadata` column names, shared by every syncable table.
    enum SyncColumn {
        static let syncState = "syncState"
        static let retryCount = "retryCount"
        static let createdAt = "createdAt"
        static let lastAttemptAt = "lastAttemptAt"
        static let remoteId = "remoteId"
    }
}

// MARK: - Migrator

extension AppDatabase {
    /// Builds the `DatabaseMigrator` describing every schema version.
    ///
    /// Migrations are registered once and applied in order. New schema changes
    /// are added as additional `registerMigration` calls with new identifiers;
    /// existing migrations are never edited once shipped, so an existing
    /// database on disk migrates forward in place on launch (supporting the
    /// restart-reload requirement).
    static func makeMigrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()

        // During development, uncomment to wipe and rebuild on an incompatible
        // schema change. Left disabled so shipped data migrates in place.
        // #if DEBUG
        // migrator.eraseDatabaseOnSchemaChange = true
        // #endif

        migrator.registerMigration("v1_initialSchema") { db in
            try createWellTable(db)
            try createWellTestTable(db)
            try createWellheadLogTable(db)
            try createSubcoolReadingTable(db)
            try createMaintenanceTaskTable(db)
            try createEscalationTable(db)
        }

        return migrator
    }

    // MARK: - Shared sync-metadata columns

    /// Appends the embedded `SyncMetadata` columns to a table being defined.
    ///
    /// Used by every syncable table so the column set stays identical across
    /// records. `lastAttemptAt` and `remoteId` are nullable; the rest are
    /// non-null with sensible defaults matching a freshly created, pending
    /// record (`syncState = pending`, `retryCount = 0`).
    private static func appendSyncMetadataColumns(to t: TableDefinition) {
        t.column(Schema.SyncColumn.syncState, .text)
            .notNull()
            .defaults(to: SyncState.pending.rawValue)
        t.column(Schema.SyncColumn.retryCount, .integer)
            .notNull()
            .defaults(to: 0)
        // Stored as an ISO-8601 / epoch value by GRDB's Date handling; the
        // single ordering key for oldest-first push.
        t.column(Schema.SyncColumn.createdAt, .datetime)
            .notNull()
            .indexed()
        t.column(Schema.SyncColumn.lastAttemptAt, .datetime)
        t.column(Schema.SyncColumn.remoteId, .text)
    }

    // MARK: - Table definitions

    /// `well` — reference data populated by the cloud pull. `id` is the value
    /// encoded in the QR code and is the natural primary key. No sync metadata:
    /// wells are pulled, not pushed.
    private static func createWellTable(_ db: Database) throws {
        try db.create(table: Schema.Table.well) { t in
            t.column("id", .text).primaryKey()
            t.column("name", .text).notNull()
            t.column("type", .text).notNull()      // WellType raw value
            t.column("padId", .text)
            t.column("updatedAt", .datetime).notNull()
        }
    }

    /// `wellTest` — a captured oil/water/gas ratio session. Syncable.
    private static func createWellTestTable(_ db: Database) throws {
        try db.create(table: Schema.Table.wellTest) { t in
            t.column("id", .text).primaryKey()      // UUID
            t.column("wellId", .text)
                .notNull()
                .indexed()
                .references(Schema.Table.well, onDelete: .cascade)
            t.column("oilRatio", .double).notNull()
            t.column("waterRatio", .double).notNull()
            t.column("gasRatio", .double).notNull()
            t.column("capturedAt", .datetime).notNull()
            appendSyncMetadataColumns(to: t)
        }
    }

    /// `wellheadLog` — a single discriminated table. `logType` selects which of
    /// the mutually exclusive production / injection column groups is
    /// populated; all per-type columns are nullable. Syncable.
    private static func createWellheadLogTable(_ db: Database) throws {
        try db.create(table: Schema.Table.wellheadLog) { t in
            t.column("id", .text).primaryKey()      // UUID
            t.column("wellId", .text)
                .notNull()
                .indexed()
                .references(Schema.Table.well, onDelete: .cascade)
            t.column("logType", .text).notNull()    // WellheadLogType raw value
            t.column("capturedAt", .datetime).notNull()

            // Production fields — populated when logType == production.
            t.column("emulsionFlowRate", .double)
            t.column("casingGasPressure", .double)
            t.column("espMotorTemperature", .double)
            t.column("waterCutPercent", .double)

            // Injection fields — populated when logType == injection.
            t.column("steamInjectionPressure", .double)
            t.column("steamTemperature", .double)
            t.column("steamFlowRate", .double)

            appendSyncMetadataColumns(to: t)
        }
    }

    /// `subcoolReading` — an estimate persisted against its inputs and derived
    /// values. `wellId` is optional. Sync metadata is present only when the
    /// reading is persisted/synced, so every sync column is nullable here.
    private static func createSubcoolReadingTable(_ db: Database) throws {
        try db.create(table: Schema.Table.subcoolReading) { t in
            t.column("id", .text).primaryKey()      // UUID
            t.column("wellId", .text)
                .indexed()
                .references(Schema.Table.well, onDelete: .setNull)
            t.column("casingPressure", .double).notNull()
            t.column("pumpTemperature", .double).notNull()
            t.column("saturationTemperature", .double).notNull()
            t.column("subcoolValue", .double).notNull()
            t.column("isFlashing", .boolean).notNull()
            t.column("capturedAt", .datetime).notNull()

            // Optional embedded SyncMetadata — all nullable because the reading
            // may be held without ever being queued for sync.
            t.column(Schema.SyncColumn.syncState, .text)
            t.column(Schema.SyncColumn.retryCount, .integer)
            t.column(Schema.SyncColumn.createdAt, .datetime).indexed()
            t.column(Schema.SyncColumn.lastAttemptAt, .datetime)
            t.column(Schema.SyncColumn.remoteId, .text)
        }
    }

    /// `maintenanceTask` — reference data populated by the cloud pull and shown
    /// on QR lookup. No sync metadata: tasks are pulled, not pushed.
    private static func createMaintenanceTaskTable(_ db: Database) throws {
        try db.create(table: Schema.Table.maintenanceTask) { t in
            t.column("id", .text).primaryKey()      // UUID
            t.column("wellId", .text)
                .notNull()
                .indexed()
                .references(Schema.Table.well, onDelete: .cascade)
            t.column("description", .text).notNull()
            t.column("status", .text).notNull()     // MaintenanceTaskStatus raw value
            t.column("createdAt", .datetime).notNull()
        }
    }

    /// `escalation` — a geotagged photo escalation. `photoRef` points at the
    /// blob in the file store; `latitude`/`longitude` are nullable decimal
    /// degrees and `locationUnavailable` records a GPS timeout. Syncable.
    private static func createEscalationTable(_ db: Database) throws {
        try db.create(table: Schema.Table.escalation) { t in
            t.column("id", .text).primaryKey()      // UUID
            t.column("wellId", .text)
                .notNull()
                .indexed()
                .references(Schema.Table.well, onDelete: .cascade)
            t.column("priority", .text).notNull()   // EscalationPriority raw value
            t.column("photoRef", .text).notNull()
            t.column("latitude", .double)
            t.column("longitude", .double)
            t.column("locationUnavailable", .boolean)
                .notNull()
                .defaults(to: false)
            t.column("capturedAt", .datetime).notNull()
            appendSyncMetadataColumns(to: t)
        }
    }
}
