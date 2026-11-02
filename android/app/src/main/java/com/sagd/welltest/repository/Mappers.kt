/*
 * Entity <-> domain mapping.
 *
 * The repository layer is the boundary where the Room persistence types in
 * `com.sagd.welltest.localstore` meet the pure domain model in
 * `com.sagd.welltest.domain`. Keeping every conversion here leaves the domain
 * free of Room annotations and the entities free of domain-layer behaviour,
 * exactly as the iOS repository maps GRDB records to domain structs.
 *
 * The mappings are total and lossless for the captured fields, which is what
 * the persistence round-trip property relies on (Property 1): converting a
 * domain record to its entity and back yields an equal record.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.MaintenanceTaskEntity
import com.sagd.welltest.localstore.OptionalSyncMetadataEntity
import com.sagd.welltest.localstore.SubcoolReadingEntity
import com.sagd.welltest.localstore.SyncMetadataEntity
import com.sagd.welltest.localstore.WellEntity
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.localstore.WellheadLogEntity

// --- SyncMetadata ---

internal fun SyncMetadata.toEntity(): SyncMetadataEntity =
    SyncMetadataEntity(
        syncState = syncState,
        retryCount = retryCount,
        createdAt = createdAt,
        lastAttemptAt = lastAttemptAt,
        remoteId = remoteId,
    )

internal fun SyncMetadataEntity.toDomain(): SyncMetadata =
    SyncMetadata(
        syncState = syncState,
        retryCount = retryCount,
        createdAt = createdAt,
        lastAttemptAt = lastAttemptAt,
        remoteId = remoteId,
    )

/**
 * Collapse an optional embedded sync metadata back to a domain [SyncMetadata].
 *
 * Room reads the embedded object as null when every column is NULL; a
 * fully-present object maps field-for-field. A partially-populated object
 * cannot occur because the repository only ever writes all-or-nothing, but the
 * `createdAt`/`syncState`/`retryCount` guards keep the mapping total if the
 * store were ever in that state.
 */
internal fun OptionalSyncMetadataEntity?.toDomain(): SyncMetadata? {
    val meta = this ?: return null
    val state = meta.syncState ?: return null
    val created = meta.createdAt ?: return null
    return SyncMetadata(
        syncState = state,
        retryCount = meta.retryCount ?: 0,
        createdAt = created,
        lastAttemptAt = meta.lastAttemptAt,
        remoteId = meta.remoteId,
    )
}

internal fun SyncMetadata?.toOptionalEntity(): OptionalSyncMetadataEntity? {
    val meta = this ?: return null
    return OptionalSyncMetadataEntity(
        syncState = meta.syncState,
        retryCount = meta.retryCount,
        createdAt = meta.createdAt,
        lastAttemptAt = meta.lastAttemptAt,
        remoteId = meta.remoteId,
    )
}

// --- Well ---

internal fun WellEntity.toDomain(): Well =
    Well(
        id = id,
        name = name,
        type = type,
        padId = padId,
        updatedAt = updatedAt,
    )

internal fun Well.toEntity(): WellEntity =
    WellEntity(
        id = id,
        name = name,
        type = type,
        padId = padId,
        updatedAt = updatedAt,
    )

// --- WellTest ---

internal fun WellTestEntity.toDomain(): WellTest =
    WellTest(
        id = id,
        wellId = wellId,
        oilRatio = oilRatio,
        waterRatio = waterRatio,
        gasRatio = gasRatio,
        capturedAt = capturedAt,
        sync = sync.toDomain(),
    )

internal fun WellTest.toEntity(): WellTestEntity =
    WellTestEntity(
        id = id,
        wellId = wellId,
        oilRatio = oilRatio,
        waterRatio = waterRatio,
        gasRatio = gasRatio,
        capturedAt = capturedAt,
        sync = sync.toEntity(),
    )

// --- WellheadLog ---

internal fun WellheadLogEntity.toDomain(): WellheadLog =
    WellheadLog(
        id = id,
        wellId = wellId,
        logType = logType,
        capturedAt = capturedAt,
        emulsionFlowRate = emulsionFlowRate,
        casingGasPressure = casingGasPressure,
        espMotorTemperature = espMotorTemperature,
        waterCutPercent = waterCutPercent,
        steamInjectionPressure = steamInjectionPressure,
        steamTemperature = steamTemperature,
        steamFlowRate = steamFlowRate,
        sync = sync.toDomain(),
    )

internal fun WellheadLog.toEntity(): WellheadLogEntity =
    WellheadLogEntity(
        id = id,
        wellId = wellId,
        logType = logType,
        capturedAt = capturedAt,
        emulsionFlowRate = emulsionFlowRate,
        casingGasPressure = casingGasPressure,
        espMotorTemperature = espMotorTemperature,
        waterCutPercent = waterCutPercent,
        steamInjectionPressure = steamInjectionPressure,
        steamTemperature = steamTemperature,
        steamFlowRate = steamFlowRate,
        sync = sync.toEntity(),
    )

// --- SubcoolReading ---

internal fun SubcoolReadingEntity.toDomain(): SubcoolReading =
    SubcoolReading(
        id = id,
        wellId = wellId,
        casingPressure = casingPressure,
        pumpTemperature = pumpTemperature,
        saturationTemperature = saturationTemperature,
        subcoolValue = subcoolValue,
        isFlashing = isFlashing,
        capturedAt = capturedAt,
        sync = sync.toDomain(),
    )

internal fun SubcoolReading.toEntity(): SubcoolReadingEntity =
    SubcoolReadingEntity(
        id = id,
        wellId = wellId,
        casingPressure = casingPressure,
        pumpTemperature = pumpTemperature,
        saturationTemperature = saturationTemperature,
        subcoolValue = subcoolValue,
        isFlashing = isFlashing,
        capturedAt = capturedAt,
        sync = sync.toOptionalEntity(),
    )

// --- MaintenanceTask ---

internal fun MaintenanceTaskEntity.toDomain(): MaintenanceTask =
    MaintenanceTask(
        id = id,
        wellId = wellId,
        description = description,
        status = status,
        createdAt = createdAt,
    )

internal fun MaintenanceTask.toEntity(): MaintenanceTaskEntity =
    MaintenanceTaskEntity(
        id = id,
        wellId = wellId,
        description = description,
        status = status,
        createdAt = createdAt,
    )

// --- Escalation ---

internal fun EscalationEntity.toDomain(): Escalation =
    Escalation(
        id = id,
        wellId = wellId,
        priority = priority,
        photoRef = photoRef,
        latitude = latitude,
        longitude = longitude,
        locationUnavailable = locationUnavailable,
        capturedAt = capturedAt,
        sync = sync.toDomain(),
    )

/**
 * Map a domain escalation to its entity, substituting the [photoRef] returned
 * by the blob store. The domain [Escalation.photoRef] is the operator-facing
 * reference assembled before persistence; the row stored always points at the
 * ref the blob store actually wrote.
 */
internal fun Escalation.toEntity(photoRef: String): EscalationEntity =
    EscalationEntity(
        id = id,
        wellId = wellId,
        priority = priority,
        photoRef = photoRef,
        latitude = latitude,
        longitude = longitude,
        locationUnavailable = locationUnavailable,
        capturedAt = capturedAt,
        sync = sync.toEntity(),
    )
