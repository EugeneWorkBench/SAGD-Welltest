/*
 * Room entities for the on-device source of truth.
 *
 * One `@Entity` per domain entity — `well`, `wellTest`, `wellheadLog`,
 * `subcoolReading`, `maintenanceTask`, and `escalation` — mirroring the design
 * document's Data Models section and the iOS GRDB schema (`Schema.swift`)
 * exactly:
 *
 *   * Every syncable table embeds the same [SyncMetadataEntity] columns
 *     (`syncState`, `retryCount`, `createdAt`, `lastAttemptAt`, `remoteId`) via
 *     `@Embedded`, so the sync engine can treat the push queue as one
 *     homogeneous stream. `well` and `maintenanceTask` are pulled reference
 *     data and carry no sync metadata.
 *   * `wellheadLog` is a single discriminated table (`logType`) with mutually
 *     exclusive, nullable production and injection columns.
 *   * `subcoolReading` carries an optional (nullable) [SyncMetadataEntity]
 *     because a reading may be held without ever being queued for sync; every
 *     embedded sync column is therefore nullable.
 *   * Foreign keys reference `well` with the same on-delete behaviour as iOS:
 *     CASCADE for owned rows, SET_NULL for the subcool reading's optional well.
 *
 * These entities are the persistence representation only. Mapping between them
 * and the pure `com.sagd.welltest.domain` model types lives in the repository
 * layer (task 6.1), keeping the domain free of Room annotations.
 *
 * Implemented by task 4.1.
 */
package com.sagd.welltest.localstore

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.WellType
import java.time.Instant
import java.util.UUID

/** Table name constants shared with the DAOs so queries and entities agree. */
internal object Tables {
    const val WELL = "well"
    const val WELL_TEST = "wellTest"
    const val WELLHEAD_LOG = "wellheadLog"
    const val SUBCOOL_READING = "subcoolReading"
    const val MAINTENANCE_TASK = "maintenanceTask"
    const val ESCALATION = "escalation"
}

/**
 * The embedded `SyncMetadata` columns, shared by every syncable table via
 * `@Embedded`. Mirrors `Schema.SyncColumn` on iOS and the domain
 * [com.sagd.welltest.domain.SyncMetadata] shape.
 *
 * When embedded with `@Embedded` (no prefix) the fields map to columns named
 * exactly `syncState`, `retryCount`, `createdAt`, `lastAttemptAt`, `remoteId`.
 * On the subcool reading this whole object is embedded as nullable, which Room
 * permits because every field below is itself nullable there — see
 * [SubcoolReadingEntity].
 *
 * @property createdAt single ordering key for the oldest-first push queue;
 *   indexed on the owning tables (Req 2.1).
 */
data class SyncMetadataEntity(
    val syncState: SyncState,
    val retryCount: Int,
    val createdAt: Instant,
    val lastAttemptAt: Instant? = null,
    val remoteId: String? = null,
)

/**
 * As [SyncMetadataEntity] but with every field nullable, for the one table
 * (`subcoolReading`) whose sync metadata is optional. Keeping it a distinct
 * type lets Room treat the embedded object as nullable: when all five columns
 * are NULL the embedded value reads back as null.
 */
data class OptionalSyncMetadataEntity(
    val syncState: SyncState? = null,
    val retryCount: Int? = null,
    val createdAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val remoteId: String? = null,
)

/**
 * `well` — reference data populated by the cloud pull. [id] is the value
 * encoded in the QR code and is the natural primary key. No sync metadata:
 * wells are pulled, not pushed.
 */
@Entity(tableName = Tables.WELL)
data class WellEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: WellType,
    val padId: String? = null,
    val updatedAt: Instant,
)

/** `wellTest` — a captured oil/water/gas ratio session. Syncable. */
@Entity(
    tableName = Tables.WELL_TEST,
    foreignKeys = [
        ForeignKey(
            entity = WellEntity::class,
            parentColumns = ["id"],
            childColumns = ["wellId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("wellId"), Index("createdAt")],
)
data class WellTestEntity(
    @PrimaryKey val id: UUID,
    val wellId: String,
    val oilRatio: Double,
    val waterRatio: Double,
    val gasRatio: Double,
    val capturedAt: Instant,
    @Embedded val sync: SyncMetadataEntity,
)

/**
 * `wellheadLog` — a single discriminated table. [logType] selects which of the
 * mutually exclusive production / injection column groups is populated; all
 * per-type columns are nullable. Syncable.
 */
@Entity(
    tableName = Tables.WELLHEAD_LOG,
    foreignKeys = [
        ForeignKey(
            entity = WellEntity::class,
            parentColumns = ["id"],
            childColumns = ["wellId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("wellId"), Index("createdAt")],
)
data class WellheadLogEntity(
    @PrimaryKey val id: UUID,
    val wellId: String,
    val logType: LogType,
    val capturedAt: Instant,
    // Production fields — populated when logType == PRODUCTION.
    val emulsionFlowRate: Double? = null,
    val casingGasPressure: Double? = null,
    val espMotorTemperature: Double? = null,
    val waterCutPercent: Double? = null,
    // Injection fields — populated when logType == INJECTION.
    val steamInjectionPressure: Double? = null,
    val steamTemperature: Double? = null,
    val steamFlowRate: Double? = null,
    @Embedded val sync: SyncMetadataEntity,
)

/**
 * `subcoolReading` — an estimate persisted against its inputs and derived
 * values. [wellId] is optional (SET_NULL on well delete). Sync metadata is
 * present only when the reading is persisted/synced, so the embedded
 * [OptionalSyncMetadataEntity] is nullable and all its columns are nullable.
 */
@Entity(
    tableName = Tables.SUBCOOL_READING,
    foreignKeys = [
        ForeignKey(
            entity = WellEntity::class,
            parentColumns = ["id"],
            childColumns = ["wellId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("wellId"), Index("createdAt")],
)
data class SubcoolReadingEntity(
    @PrimaryKey val id: UUID,
    val wellId: String? = null,
    val casingPressure: Double,
    val pumpTemperature: Double,
    val saturationTemperature: Double,
    val subcoolValue: Double,
    val isFlashing: Boolean,
    val capturedAt: Instant,
    @Embedded val sync: OptionalSyncMetadataEntity? = null,
)

/**
 * `maintenanceTask` — reference data populated by the cloud pull and shown on
 * QR lookup. No sync metadata: tasks are pulled, not pushed.
 */
@Entity(
    tableName = Tables.MAINTENANCE_TASK,
    foreignKeys = [
        ForeignKey(
            entity = WellEntity::class,
            parentColumns = ["id"],
            childColumns = ["wellId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("wellId")],
)
data class MaintenanceTaskEntity(
    @PrimaryKey val id: UUID,
    val wellId: String,
    val description: String,
    val status: MaintenanceStatus,
    val createdAt: Instant,
)

/**
 * `escalation` — a geotagged photo escalation. [photoRef] points at the blob in
 * the file store; [latitude]/[longitude] are nullable decimal degrees and
 * [locationUnavailable] records a GPS timeout. Syncable.
 */
@Entity(
    tableName = Tables.ESCALATION,
    foreignKeys = [
        ForeignKey(
            entity = WellEntity::class,
            parentColumns = ["id"],
            childColumns = ["wellId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("wellId"), Index("createdAt")],
)
data class EscalationEntity(
    @PrimaryKey val id: UUID,
    val wellId: String,
    val priority: EscalationPriority,
    val photoRef: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUnavailable: Boolean = false,
    val capturedAt: Instant,
    @Embedded val sync: SyncMetadataEntity,
)
