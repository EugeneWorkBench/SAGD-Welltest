/*
 * Room type converters.
 *
 * Maps the non-primitive domain field types onto the primitive affinities
 * SQLite understands, matching the storage choices made by the iOS GRDB schema
 * so both platforms persist byte-for-byte comparable data:
 *
 *   * [java.time.Instant]  <-> TEXT  (ISO-8601 / RFC-3339, UTC, millisecond
 *     precision via [DateTimeFormatter.ISO_INSTANT]). Lexicographic ordering of
 *     the ISO-8601 instant strings matches chronological ordering, so the
 *     `createdAt` index gives a correct oldest-first push order (Req 2.1).
 *   * [java.util.UUID]     <-> TEXT  (canonical 36-char form), matching the
 *     iOS `id` columns which store the UUID string.
 *   * domain enums         <-> TEXT  (the enum's declared name), matching the
 *     iOS raw values.
 *
 * Nullable inputs map to nullable outputs so optional columns (e.g.
 * `lastAttemptAt`, `remoteId`, the subcool reading's optional sync metadata)
 * round-trip cleanly.
 *
 * Implemented by task 4.1.
 */
package com.sagd.welltest.localstore

import androidx.room.TypeConverter
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.WellType
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Converters registered on the [AppDatabase]. Room applies these automatically
 * wherever a column's Kotlin type is one of the supported types below.
 *
 * All methods are null-tolerant: a null domain value stores as SQL NULL and a
 * SQL NULL reads back as null, which the entities rely on for their optional
 * columns.
 */
object Converters {

    // --- Instant <-> ISO-8601 TEXT (UTC) ---

    @TypeConverter
    fun instantToString(value: Instant?): String? =
        value?.let { DateTimeFormatter.ISO_INSTANT.format(it) }

    @TypeConverter
    fun stringToInstant(value: String?): Instant? =
        value?.let { Instant.from(DateTimeFormatter.ISO_INSTANT.parse(it)) }

    // --- UUID <-> canonical TEXT ---

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    // --- WellType <-> TEXT ---

    @TypeConverter
    fun wellTypeToString(value: WellType?): String? = value?.name

    @TypeConverter
    fun stringToWellType(value: String?): WellType? = value?.let(WellType::valueOf)

    // --- LogType <-> TEXT ---

    @TypeConverter
    fun logTypeToString(value: LogType?): String? = value?.name

    @TypeConverter
    fun stringToLogType(value: String?): LogType? = value?.let(LogType::valueOf)

    // --- MaintenanceStatus <-> TEXT ---

    @TypeConverter
    fun maintenanceStatusToString(value: MaintenanceStatus?): String? = value?.name

    @TypeConverter
    fun stringToMaintenanceStatus(value: String?): MaintenanceStatus? =
        value?.let(MaintenanceStatus::valueOf)

    // --- EscalationPriority <-> TEXT ---

    @TypeConverter
    fun escalationPriorityToString(value: EscalationPriority?): String? = value?.name

    @TypeConverter
    fun stringToEscalationPriority(value: String?): EscalationPriority? =
        value?.let(EscalationPriority::valueOf)

    // --- SyncState <-> TEXT ---

    @TypeConverter
    fun syncStateToString(value: SyncState?): String? = value?.name

    @TypeConverter
    fun stringToSyncState(value: String?): SyncState? = value?.let(SyncState::valueOf)
}
