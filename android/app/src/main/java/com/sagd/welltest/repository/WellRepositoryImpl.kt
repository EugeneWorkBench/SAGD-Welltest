/*
 * WellRepository over Room.
 *
 * Read access to well reference data and the records attached to a well, used
 * by the QR-lookup use case (Req 6.2). The ordering and filtering that the
 * requirements pin down are pushed into the DAO queries so both platforms
 * behave identically:
 *
 *   - getWellTests returns tests most-recent-first (ORDER BY capturedAt DESC).
 *   - getOpenMaintenanceTasks returns only status = OPEN rows.
 *
 * All reads are plain suspend queries with no network dependency, satisfying
 * the offline-first contract (Req 1.2, 6.5): a lookup against a well already in
 * the local store succeeds regardless of connectivity.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellRepository
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.localstore.MaintenanceTaskDao
import com.sagd.welltest.localstore.WellDao
import com.sagd.welltest.localstore.WellTestDao

/**
 * @param wellDao DAO for the `well` reference table.
 * @param wellTestDao DAO for the `wellTest` table (most-recent-first query).
 * @param maintenanceTaskDao DAO for the `maintenanceTask` table (open-only query).
 */
class WellRepositoryImpl(
    private val wellDao: WellDao,
    private val wellTestDao: WellTestDao,
    private val maintenanceTaskDao: MaintenanceTaskDao,
) : WellRepository {

    override suspend fun getWell(id: String): Well? =
        wellDao.getWell(id)?.toDomain()

    /** Historical tests ordered most-recent-first (Req 6.2). */
    override suspend fun getWellTests(wellId: String): List<WellTest> =
        wellTestDao.getForWell(wellId).map { it.toDomain() }

    /** Only tasks with status = OPEN (Req 6.2). */
    override suspend fun getOpenMaintenanceTasks(wellId: String): List<MaintenanceTask> =
        maintenanceTaskDao.getOpenForWell(wellId).map { it.toDomain() }
}
