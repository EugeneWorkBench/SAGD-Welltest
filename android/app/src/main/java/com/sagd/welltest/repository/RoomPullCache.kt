/*
 * Room-backed pull cache.
 *
 * The production [PullCache] the REST pull client writes through so pulled
 * reference data (wells, historical tests, open maintenance tasks) is persisted
 * locally and QR lookup works offline (Req 6.2, 6.5). Lives in the repository
 * layer because the domain→entity mappers it uses are package-internal here,
 * mirroring [RoomPushPayloadSource] and [RoomEscalationBlobSource].
 *
 * Upserts use the DAOs' REPLACE/`@Upsert` semantics so a re-pull refreshes rows
 * in place rather than duplicating them. Open maintenance tasks for a well are
 * replaced wholesale (delete-then-insert) so a task closed on the backend
 * disappears from the cached open set.
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.localstore.MaintenanceTaskDao
import com.sagd.welltest.localstore.WellDao
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.network.PullCache

/**
 * @param wellDao DAO for the `well` reference table.
 * @param wellTestDao DAO for captured/cached `wellTest` rows.
 * @param maintenanceTaskDao DAO for the `maintenanceTask` reference table.
 */
class RoomPullCache(
    private val wellDao: WellDao,
    private val wellTestDao: WellTestDao,
    private val maintenanceTaskDao: MaintenanceTaskDao,
) : PullCache {

    override suspend fun cacheWell(well: Well) {
        wellDao.upsert(well.toEntity())
    }

    override suspend fun cacheWells(wells: List<Well>) {
        wellDao.upsertAll(wells.map { it.toEntity() })
    }

    override suspend fun cacheWellTests(wellId: String, tests: List<WellTest>) {
        // WellTestDao exposes a single-row REPLACE upsert; re-pulling the same
        // id overwrites in place, so pulled history never duplicates locally
        // captured rows that share a client id.
        tests.forEach { wellTestDao.upsert(it.toEntity()) }
    }

    override suspend fun cacheMaintenanceTasks(wellId: String, tasks: List<MaintenanceTask>) {
        // Replace the whole open set for this well so a task that is no longer
        // open on the backend drops out of the cached open-task list (Req 6.2).
        maintenanceTaskDao.deleteForWell(wellId)
        maintenanceTaskDao.upsertAll(tasks.map { it.toEntity() })
    }
}
