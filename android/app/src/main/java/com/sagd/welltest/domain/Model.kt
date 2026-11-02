/*
 * Shared domain model types.
 *
 * Plain Kotlin data/sealed classes mirroring the iOS domain model exactly so
 * both native codebases stay aligned. These types are pure and
 * platform-agnostic: no Room, Compose, or networking annotations here. The
 * localstore layer maps these to Room @Entity classes; the network layer maps
 * them to its DTOs.
 *
 * See design.md "Data Models".
 */
package com.sagd.welltest.domain

import java.time.Instant
import java.util.UUID

/** Well category. Drives which wellhead-log form the UI presents (Req 3.1 vs 4.1). */
enum class WellType {
    PRODUCTION,
    INJECTION,
}

/** Discriminator for [WellheadLog]; production and injection fields are mutually exclusive. */
enum class LogType {
    PRODUCTION,
    INJECTION,
}

/** Maintenance-task lifecycle state. "Open" tasks are those with [OPEN]. */
enum class MaintenanceStatus {
    OPEN,
    CLOSED,
}

/** Escalation priority. One of these is required to save an escalation (Req 7.2). */
enum class EscalationPriority {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Per-record synchronization state (Req 2.5, 2.6).
 *
 * A record enters [PENDING] on creation, moves to [SYNCHRONIZED] on cloud
 * acknowledgment, and becomes [FAILED] after five consecutive failures.
 */
enum class SyncState {
    PENDING,
    SYNCHRONIZED,
    FAILED,
}

/**
 * Sync bookkeeping embedded uniformly in every syncable record so the sync
 * engine can treat the push queue as one homogeneous stream (design.md
 * "Embedded: SyncMetadata").
 *
 * @property createdAt single ordering key for the oldest-first push queue (Req 2.1).
 * @property retryCount bounded at 5; the record becomes [SyncState.FAILED] at 5 (Req 2.5).
 * @property lastAttemptAt drives exponential backoff scheduling.
 * @property remoteId server-assigned id set on cloud acknowledgment.
 */
data class SyncMetadata(
    val syncState: SyncState = SyncState.PENDING,
    val retryCount: Int = 0,
    val createdAt: Instant,
    val lastAttemptAt: Instant? = null,
    val remoteId: String? = null,
)

/**
 * Well reference data. Primarily populated by the cloud pull (historical data);
 * the [id] is the value encoded in the QR code. (Req 6.)
 */
data class Well(
    val id: String,
    val name: String,
    val type: WellType,
    val padId: String? = null,
    val updatedAt: Instant,
)

/** A captured well-test record. Syncable (Req 2); shown on QR lookup (Req 6.2). */
data class WellTest(
    val id: UUID,
    val wellId: String,
    val oilRatio: Double,
    val waterRatio: Double,
    val gasRatio: Double,
    val capturedAt: Instant,
    val sync: SyncMetadata,
)

/**
 * A wellhead log discriminated by [logType]. Production and injection fields are
 * mutually exclusive and null when not applicable to the log type. (Req 3, Req 4.)
 */
data class WellheadLog(
    val id: UUID,
    val wellId: String,
    val logType: LogType,
    val capturedAt: Instant,
    // Production fields — present when logType == PRODUCTION.
    val emulsionFlowRate: Double? = null,
    val casingGasPressure: Double? = null,
    val espMotorTemperature: Double? = null,
    val waterCutPercent: Double? = null,
    // Injection fields — present when logType == INJECTION.
    val steamInjectionPressure: Double? = null,
    val steamTemperature: Double? = null,
    val steamFlowRate: Double? = null,
    val sync: SyncMetadata,
)

/**
 * A subcool estimate. [subcoolValue] = [saturationTemperature] - [pumpTemperature]
 * (Req 5.1); [isFlashing] = subcoolValue <= flashing threshold (Req 5.3).
 * [sync] is null until the reading is persisted/synced.
 */
data class SubcoolReading(
    val id: UUID,
    val wellId: String? = null,
    val casingPressure: Double,
    val pumpTemperature: Double,
    val saturationTemperature: Double,
    val subcoolValue: Double,
    val isFlashing: Boolean,
    val capturedAt: Instant,
    val sync: SyncMetadata? = null,
)

/** A maintenance task. Primarily populated by cloud pull; displayed on QR lookup (Req 6.2). */
data class MaintenanceTask(
    val id: UUID,
    val wellId: String,
    val description: String,
    val status: MaintenanceStatus,
    val createdAt: Instant,
)

/**
 * A geotagged photo escalation. Requires a [priority] and [wellId] to save (Req 7.2).
 * [latitude]/[longitude] are decimal degrees (Req 7.1); [locationUnavailable] is true
 * when the GPS fix timed out (Req 7.5). [photoRef] points to the blob in the local file
 * store (the photo is never a BLOB column).
 */
data class Escalation(
    val id: UUID,
    val wellId: String,
    val priority: EscalationPriority,
    val photoRef: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUnavailable: Boolean = false,
    val capturedAt: Instant,
    val sync: SyncMetadata,
)
