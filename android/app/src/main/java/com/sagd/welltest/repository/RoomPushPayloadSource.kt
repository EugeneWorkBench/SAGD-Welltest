/*
 * Room-backed push payload source.
 *
 * The production [PushPayloadSource] the REST client uses to resolve a queued
 * record's payload before encoding it (task 23.1). It loads the row from the
 * owning DAO and maps it to the pure-domain type via the package-internal
 * entity→domain mappers. Lives in the repository layer for the same reason as
 * [RoomEscalationBlobSource]: the mappers are only visible here, keeping the
 * network layer dependent on the domain model alone.
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.SubcoolReadingDao
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.network.PushPayloadSource
import java.util.UUID

/**
 * @param wellTestDao DAO for the `wellTest` table.
 * @param wellheadLogDao DAO for the `wellheadLog` table.
 * @param subcoolReadingDao DAO for the `subcoolReading` table.
 */
class RoomPushPayloadSource(
    private val wellTestDao: WellTestDao,
    private val wellheadLogDao: WellheadLogDao,
    private val subcoolReadingDao: SubcoolReadingDao,
) : PushPayloadSource {

    override suspend fun wellTest(id: UUID): WellTest? =
        wellTestDao.getById(id)?.toDomain()

    override suspend fun wellheadLog(id: UUID): WellheadLog? =
        wellheadLogDao.getById(id)?.toDomain()

    override suspend fun subcoolReading(id: UUID): SubcoolReading? =
        subcoolReadingDao.getById(id)?.toDomain()
}
