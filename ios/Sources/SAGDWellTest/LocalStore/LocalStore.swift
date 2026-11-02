//
//  Local Store layer
//
//  SQLite database (GRDB) plus a file-based blob store for escalation photos.
//  The single source of truth on-device. Opens the existing database on launch
//  so persisted records reload after restart (Req 1.3).
//
//  This file provides `AppDatabase`: the GRDB `DatabaseQueue` wrapper that
//  resolves the on-disk database location, opens (or creates) it, and runs the
//  schema migrations defined in `Schema.swift` (task 3.1). The photo blob file
//  store is implemented in task 3.2.

import Foundation
import GRDB

/// Namespace marker for the Local Store layer.
enum LocalStoreLayer {}

/// Owns the GRDB `DatabaseQueue` for the on-device source of truth.
///
/// A single `DatabaseQueue` serializes all database access, which matches the
/// offline-first design: the UI and repositories read and write only through
/// this store. Opening the existing file on launch and running the migrator in
/// place means every record persisted before a restart is immediately
/// available again (Req 1.1, Req 1.3).
public final class AppDatabase {

    /// The underlying GRDB access point. Repositories (task 5) read and write
    /// through this writer; `DatabaseQueue` conforms to `DatabaseWriter`.
    public let dbWriter: any DatabaseWriter

    /// Creates an `AppDatabase` over an already-opened writer and applies all
    /// pending migrations. Prefer `AppDatabase.open(...)` for the on-disk
    /// database and `AppDatabase.makeInMemory()` for tests.
    ///
    /// - Parameter dbWriter: an opened GRDB writer (queue or pool).
    /// - Throws: any error raised while migrating the schema.
    public init(_ dbWriter: any DatabaseWriter) throws {
        self.dbWriter = dbWriter
        try migrator.migrate(dbWriter)
    }

    /// The migrator describing every schema version (see `Schema.swift`).
    var migrator: DatabaseMigrator { AppDatabase.makeMigrator() }
}

// MARK: - Opening the on-disk database

extension AppDatabase {

    /// The default on-disk database filename.
    public static let defaultDatabaseFileName = "sagd-welltest.sqlite"

    /// Opens (creating if needed) the on-disk database and returns a migrated
    /// `AppDatabase`.
    ///
    /// Called once at app launch. If the file already exists it is opened in
    /// place and the migrator brings it to the current schema without
    /// discarding data, so persisted records reload after restart (Req 1.3).
    /// The database lives in `Application Support`, which is excluded from
    /// cloud backups appropriately and persists across launches.
    ///
    /// - Parameter fileManager: injectable for testing; defaults to `.default`.
    /// - Returns: a migrated `AppDatabase` backed by a `DatabaseQueue`.
    /// - Throws: if the directory cannot be created or the database cannot be
    ///   opened or migrated.
    public static func open(fileManager: FileManager = .default) throws -> AppDatabase {
        let url = try databaseURL(fileManager: fileManager)
        var configuration = Configuration()
        // Enforce foreign keys so cascade/set-null rules in the schema hold.
        configuration.foreignKeysEnabled = true
        let dbQueue = try DatabaseQueue(path: url.path, configuration: configuration)
        return try AppDatabase(dbQueue)
    }

    /// Resolves the on-disk database URL, creating the containing directory if
    /// it does not yet exist.
    ///
    /// - Parameter fileManager: injectable for testing; defaults to `.default`.
    /// - Returns: the file URL of the database within `Application Support`.
    /// - Throws: if the support directory cannot be located or created.
    static func databaseURL(fileManager: FileManager = .default) throws -> URL {
        let appSupportURL = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directoryURL = appSupportURL.appendingPathComponent("SAGDWellTest", isDirectory: true)
        try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        return directoryURL.appendingPathComponent(defaultDatabaseFileName, isDirectory: false)
    }
}

// MARK: - In-memory database (tests)

extension AppDatabase {

    /// Creates a migrated, in-memory `AppDatabase`.
    ///
    /// Used by repository and property-based tests (tasks 5.x) that need a
    /// throwaway store with the full schema but no on-disk file.
    ///
    /// - Throws: if the in-memory queue cannot be opened or migrated.
    public static func makeInMemory() throws -> AppDatabase {
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        let dbQueue = try DatabaseQueue(configuration: configuration)
        return try AppDatabase(dbQueue)
    }
}
