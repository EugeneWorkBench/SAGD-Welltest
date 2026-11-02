/*
 * Repository contracts.
 *
 * The repository layer is the only component the UI uses to read/write data. It
 * wraps the local store, assigns sync metadata on write, and exposes observable
 * queries so the UI updates reactively. These interfaces mirror the iOS
 * repository protocols; implementations over Room live in the repository layer.
 *
 * Observable queries use kotlinx.coroutines Flow (the Kotlin analogue of the
 * iOS Combine/async stream). See design.md "Repository components".
 */
package com.sagd.welltest.domain

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Read access to well reference data and the records attached to a well (Req 6.2). */
interface WellRepository {
    suspend fun getWell(id: String): Well?

    /** Historical tests ordered most-recent-first (Req 6.2). */
    suspend fun getWellTests(wellId: String): List<WellTest>

    /** Only tasks with status = open (Req 6.2). */
    suspend fun getOpenMaintenanceTasks(wellId: String): List<MaintenanceTask>
}

/** Transactional, storage-aware persistence of captured logs and tests (Req 1). */
interface LogRepository {
    suspend fun saveWellheadLog(log: WellheadLog): SaveResult

    suspend fun saveWellTest(test: WellTest): SaveResult
}

/** Persistence of escalations together with their photo blob (Req 7.3, 7.4). */
interface EscalationRepository {
    /** Writes the blob file first, then the referencing row, in a single transaction. */
    suspend fun saveEscalation(escalation: Escalation, photoData: ByteArray): SaveResult
}

/**
 * The sync engine's view of the local store: the push queue plus the
 * per-record state-machine mutators and observers (Req 2).
 */
interface SyncRepository {
    /** Oldest [SyncMetadata.createdAt] first; excludes failed records (Req 2.1). */
    suspend fun nextPendingBatch(limit: Int): List<SyncableRecord>

    /** Moves a record to [SyncState.SYNCHRONIZED] and stores its remoteId (Req 2.3). */
    suspend fun markSynchronized(recordId: UUID, remoteId: String)

    /** Increments retryCount; sets [SyncState.FAILED] at 5 (Req 2.4, 2.5). */
    suspend fun recordFailure(recordId: UUID)

    /** Resets a failed record to [SyncState.PENDING] (Req 2.5, 7.7). */
    suspend fun manualRetry(recordId: UUID)

    /** Observable per-record sync state; emits within 5s of change (Req 2.7). */
    fun observeSyncState(recordId: UUID): Flow<SyncState>
}

/**
 * A record in the push queue, surfaced uniformly regardless of its concrete
 * type so the sync engine can process one homogeneous stream.
 */
data class SyncableRecord(
    val recordId: UUID,
    val type: SyncableRecordType,
    val sync: SyncMetadata,
)

/** The concrete entity a [SyncableRecord] refers to. */
enum class SyncableRecordType {
    WELL_TEST,
    WELLHEAD_LOG,
    SUBCOOL_READING,
    ESCALATION,
}
