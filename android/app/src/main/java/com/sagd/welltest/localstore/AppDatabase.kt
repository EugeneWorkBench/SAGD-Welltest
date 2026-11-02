/*
 * Room database — the on-device source of truth.
 *
 * Declares the six entities and exposes one DAO per entity. The database is
 * opened against a stable on-disk file, so launching the app reopens the
 * existing database and every record persisted before a restart is immediately
 * available for reading and editing (Req 1.3). Records left in PENDING/FAILED
 * stay in those states and are re-picked-up by the sync engine on the next
 * connectivity event.
 *
 * Schema evolution follows the same forward-migration discipline as the iOS
 * GRDB migrator: `version` is bumped and a `Migration` is added for each
 * shipped schema change; existing migrations are never edited. For the initial
 * schema (version 1) there are no migrations yet. `fallbackToDestructive
 * Migration` is deliberately NOT enabled, so shipped data is migrated in place
 * rather than wiped.
 *
 * Mirrors the iOS `AppDatabase` / `DatabaseQueue` setup and the GRDB schema in
 * `Schema.swift`. Implemented by task 4.1.
 */
package com.sagd.welltest.localstore

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The application's Room database.
 *
 * Foreign-key enforcement is enabled on open (see [create]) so the on-delete
 * CASCADE / SET_NULL behaviour declared on the entities is actually applied by
 * SQLite, matching the iOS GRDB schema.
 */
@Database(
    entities = [
        WellEntity::class,
        WellTestEntity::class,
        WellheadLogEntity::class,
        SubcoolReadingEntity::class,
        MaintenanceTaskEntity::class,
        EscalationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wellDao(): WellDao

    abstract fun wellTestDao(): WellTestDao

    abstract fun wellheadLogDao(): WellheadLogDao

    abstract fun subcoolReadingDao(): SubcoolReadingDao

    abstract fun maintenanceTaskDao(): MaintenanceTaskDao

    abstract fun escalationDao(): EscalationDao

    companion object {
        /** On-disk database file name. Kept stable so the DB reopens on launch (Req 1.3). */
        const val DATABASE_NAME = "sagd_welltest.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Returns the process-wide singleton, opening the existing on-disk
         * database on first access and reusing it thereafter. Safe to call from
         * any thread.
         */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                // Enforce the declared foreign keys (CASCADE / SET_NULL) like iOS.
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA foreign_keys = ON;")
                        }
                    },
                )
                // No fallbackToDestructiveMigration: migrate shipped data in
                // place. Add .addMigrations(...) here as the schema evolves.
                .build()
    }
}
