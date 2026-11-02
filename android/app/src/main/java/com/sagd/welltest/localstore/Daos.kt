/*
 * Room DAOs.
 *
 * One DAO per entity, exposing the queries the repository and sync layers need.
 * The query shapes mirror the design's repository contracts and the iOS
 * realization so both platforms behave identically:
 *
 *   * [WellTestDao.getForWell] orders tests most-recent-first (Req 6.2).
 *   * [MaintenanceTaskDao.getOpenForWell] filters `status = OPEN` (Req 6.2).
 *   * The per-table `nextPending*` queries order by `createdAt` ASC
 *     (oldest-first) and exclude FAILED records, feeding the sync engine's
 *     homogeneous push queue (Req 2.1).
 *   * Sync-state mutators (`markSynchronized`, `recordFailure`, `manualRetry`)
 *     advance the per-record state machine (Req 2.3-2.5, 7.7).
 *   * `observe*` queries return a [Flow] so the UI/sync engine react to changes
 *     within 5s (Req 2.7).
 *
 * Writes use REPLACE conflict handling so an idempotent re-upsert of a
 * client-generated `id` overwrites in place rather than creating a duplicate,
 * matching the sync idempotency contract.
 *
 * The repository layer (task 6.1) composes these DAOs into the domain-facing
 * repositories; the transactional save/rollback and storage-full semantics live
 * there, not here.
 *
 * Implemented by task 4.1.
 */
package com.sagd.welltest.localstore

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.SyncState
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Reads/writes for `well` reference data. */
@Dao
interface WellDao {
    @Upsert
    suspend fun upsert(well: WellEntity)

    @Upsert
    suspend fun upsertAll(wells: List<WellEntity>)

    @Query("SELECT * FROM ${Tables.WELL} WHERE id = :id")
    suspend fun getWell(id: String): WellEntity?

    @Query("SELECT * FROM ${Tables.WELL} WHERE id = :id")
    fun observeWell(id: String): Flow<WellEntity?>

    /**
     * All wells, name-ordered. Backs the well-selection screen's list at the
     * composition root; emits again whenever the pulled reference set changes so
     * the selection list stays current.
     */
    @Query("SELECT * FROM ${Tables.WELL} ORDER BY name ASC")
    fun observeAll(): Flow<List<WellEntity>>

    @Query("DELETE FROM ${Tables.WELL}")
    suspend fun deleteAll()
}

/** Reads/writes for captured `wellTest` records. */
@Dao
interface WellTestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(test: WellTestEntity)

    @Query("SELECT * FROM ${Tables.WELL_TEST} WHERE id = :id")
    suspend fun getById(id: UUID): WellTestEntity?

    /** Historical tests ordered most-recent-first (Req 6.2). */
    @Query(
        "SELECT * FROM ${Tables.WELL_TEST} WHERE wellId = :wellId " +
            "ORDER BY capturedAt DESC",
    )
    suspend fun getForWell(wellId: String): List<WellTestEntity>

    /** Oldest `createdAt` first, excluding FAILED, for the push queue (Req 2.1). */
    @Query(
        "SELECT * FROM ${Tables.WELL_TEST} WHERE syncState = :pending " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun nextPending(
        limit: Int,
        pending: SyncState = SyncState.PENDING,
    ): List<WellTestEntity>

    @Query("SELECT syncState FROM ${Tables.WELL_TEST} WHERE id = :id")
    fun observeSyncState(id: UUID): Flow<SyncState?>

    @Update
    suspend fun update(test: WellTestEntity)
}

/** Reads/writes for captured `wellheadLog` records. */
@Dao
interface WellheadLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: WellheadLogEntity)

    @Query("SELECT * FROM ${Tables.WELLHEAD_LOG} WHERE id = :id")
    suspend fun getById(id: UUID): WellheadLogEntity?

    @Query(
        "SELECT * FROM ${Tables.WELLHEAD_LOG} WHERE wellId = :wellId " +
            "ORDER BY capturedAt DESC",
    )
    suspend fun getForWell(wellId: String): List<WellheadLogEntity>

    @Query(
        "SELECT * FROM ${Tables.WELLHEAD_LOG} WHERE syncState = :pending " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun nextPending(
        limit: Int,
        pending: SyncState = SyncState.PENDING,
    ): List<WellheadLogEntity>

    @Query("SELECT syncState FROM ${Tables.WELLHEAD_LOG} WHERE id = :id")
    fun observeSyncState(id: UUID): Flow<SyncState?>

    @Update
    suspend fun update(log: WellheadLogEntity)
}

/** Reads/writes for `subcoolReading` records (optional sync metadata). */
@Dao
interface SubcoolReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: SubcoolReadingEntity)

    @Query("SELECT * FROM ${Tables.SUBCOOL_READING} WHERE id = :id")
    suspend fun getById(id: UUID): SubcoolReadingEntity?

    @Query(
        "SELECT * FROM ${Tables.SUBCOOL_READING} WHERE wellId = :wellId " +
            "ORDER BY capturedAt DESC",
    )
    suspend fun getForWell(wellId: String): List<SubcoolReadingEntity>

    /**
     * Pending readings only. Readings without sync metadata have a NULL
     * `syncState` and are therefore excluded, as are SYNCHRONIZED/FAILED.
     */
    @Query(
        "SELECT * FROM ${Tables.SUBCOOL_READING} WHERE syncState = :pending " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun nextPending(
        limit: Int,
        pending: SyncState = SyncState.PENDING,
    ): List<SubcoolReadingEntity>

    @Query("SELECT syncState FROM ${Tables.SUBCOOL_READING} WHERE id = :id")
    fun observeSyncState(id: UUID): Flow<SyncState?>

    @Update
    suspend fun update(reading: SubcoolReadingEntity)
}

/** Reads/writes for `maintenanceTask` reference data. */
@Dao
interface MaintenanceTaskDao {
    @Upsert
    suspend fun upsert(task: MaintenanceTaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<MaintenanceTaskEntity>)

    /** Only tasks with status = OPEN (Req 6.2). */
    @Query(
        "SELECT * FROM ${Tables.MAINTENANCE_TASK} WHERE wellId = :wellId " +
            "AND status = :open ORDER BY createdAt DESC",
    )
    suspend fun getOpenForWell(
        wellId: String,
        open: MaintenanceStatus = MaintenanceStatus.OPEN,
    ): List<MaintenanceTaskEntity>

    @Query("DELETE FROM ${Tables.MAINTENANCE_TASK} WHERE wellId = :wellId")
    suspend fun deleteForWell(wellId: String)
}

/** Reads/writes for captured `escalation` records. */
@Dao
interface EscalationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(escalation: EscalationEntity)

    @Query("SELECT * FROM ${Tables.ESCALATION} WHERE id = :id")
    suspend fun getById(id: UUID): EscalationEntity?

    @Query(
        "SELECT * FROM ${Tables.ESCALATION} WHERE syncState = :pending " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun nextPending(
        limit: Int,
        pending: SyncState = SyncState.PENDING,
    ): List<EscalationEntity>

    @Query("SELECT syncState FROM ${Tables.ESCALATION} WHERE id = :id")
    fun observeSyncState(id: UUID): Flow<SyncState?>

    /**
     * Every photo blob reference still held by a persisted escalation row. Feeds
     * the launch-time orphan-blob sweep: any blob file whose ref is not in this
     * set was left by a crash between blob-write and row-commit and is reclaimed
     * (Req 7.3, 7.4). See [com.sagd.welltest.localstore.BlobStore.garbageCollect].
     */
    @Query("SELECT photoRef FROM ${Tables.ESCALATION}")
    suspend fun allPhotoRefs(): List<String>

    @Update
    suspend fun update(escalation: EscalationEntity)

    @Delete
    suspend fun delete(escalation: EscalationEntity)
}
