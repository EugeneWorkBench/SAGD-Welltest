/*
 * SyncRepository over Room.
 *
 * The sync engine's view of the local store: the oldest-first push queue plus
 * the per-record state-machine mutators and observers (design.md "Sync engine
 * component", "Per-record sync-state machine"). Because every syncable table
 * embeds the same SyncMetadata, this repository presents the four concrete
 * tables (wellTest, wellheadLog, subcoolReading, escalation) as one homogeneous
 * stream of [SyncableRecord] and routes each mutator to whichever table owns
 * the id.
 *
 * Ordering (Req 2.1): each DAO's `nextPending` query already returns PENDING
 * rows oldest-first by `createdAt`; this repository merges the per-table
 * results and re-sorts by `createdAt` ASC so the global batch is oldest-first
 * across record types, then truncates to `limit`. SYNCHRONIZED and FAILED rows
 * are excluded because the queries select `syncState = PENDING` only.
 *
 * State machine (Req 2.3-2.5, 7.7):
 *   - markSynchronized -> SYNCHRONIZED, store remoteId.
 *   - recordFailure    -> retryCount + 1, stamp lastAttemptAt; at the 5th
 *     consecutive failure (retryCount reaches [syncMaxRetryCount]) the state
 *     becomes FAILED and the row is excluded from future batches.
 *   - manualRetry      -> back to PENDING with retryCount reset to 0, giving a
 *     failed record a fresh set of attempts (tasks.md: "manualRetry resets to
 *     pending").
 *
 * Observation (Req 2.7): observeSyncState merges the four per-table sync-state
 * flows and emits whichever one is non-null for the id, so the UI reflects a
 * state change within 5s.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.SyncableRecordType
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.SubcoolReadingDao
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellTestDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import java.time.Clock
import java.util.UUID

/** Maximum consecutive failures before a record is parked as FAILED (Req 2.5). */
const val SYNC_MAX_RETRY_COUNT: Int = 5

/**
 * @param wellTestDao DAO for the `wellTest` table.
 * @param wellheadLogDao DAO for the `wellheadLog` table.
 * @param subcoolReadingDao DAO for the `subcoolReading` table.
 * @param escalationDao DAO for the `escalation` table.
 * @param clock source of `lastAttemptAt`; injectable for deterministic tests.
 */
class SyncRepositoryImpl(
    private val wellTestDao: WellTestDao,
    private val wellheadLogDao: WellheadLogDao,
    private val subcoolReadingDao: SubcoolReadingDao,
    private val escalationDao: EscalationDao,
    private val clock: Clock = Clock.systemUTC(),
) : SyncRepository {

    override suspend fun nextPendingBatch(limit: Int): List<SyncableRecord> {
        if (limit <= 0) return emptyList()

        // Each DAO already returns its PENDING rows oldest-first; fetch up to
        // `limit` from each so the global top-`limit` by createdAt is complete
        // even if one type dominates the oldest entries.
        val candidates = buildList {
            wellTestDao.nextPending(limit).forEach {
                add(SyncableRecord(it.id, SyncableRecordType.WELL_TEST, it.sync.toDomain()))
            }
            wellheadLogDao.nextPending(limit).forEach {
                add(SyncableRecord(it.id, SyncableRecordType.WELLHEAD_LOG, it.sync.toDomain()))
            }
            subcoolReadingDao.nextPending(limit).forEach {
                val meta = it.sync.toDomain() ?: return@forEach
                add(SyncableRecord(it.id, SyncableRecordType.SUBCOOL_READING, meta))
            }
            escalationDao.nextPending(limit).forEach {
                add(SyncableRecord(it.id, SyncableRecordType.ESCALATION, it.sync.toDomain()))
            }
        }

        // Global oldest-first ordering across all record types (Req 2.1).
        return candidates
            .sortedBy { it.sync.createdAt }
            .take(limit)
    }

    override suspend fun markSynchronized(recordId: UUID, remoteId: String) {
        mutate(recordId) { meta ->
            meta.copy(syncState = SyncState.SYNCHRONIZED, remoteId = remoteId)
        }
    }

    override suspend fun recordFailure(recordId: UUID) {
        mutate(recordId) { meta ->
            val nextCount = meta.retryCount + 1
            val nextState =
                if (nextCount >= SYNC_MAX_RETRY_COUNT) SyncState.FAILED else SyncState.PENDING
            meta.copy(
                retryCount = nextCount,
                syncState = nextState,
                lastAttemptAt = clock.instant(),
            )
        }
    }

    override suspend fun manualRetry(recordId: UUID) {
        mutate(recordId) { meta ->
            meta.copy(syncState = SyncState.PENDING, retryCount = 0)
        }
    }

    override fun observeSyncState(recordId: UUID): Flow<SyncState> =
        combine(
            wellTestDao.observeSyncState(recordId),
            wellheadLogDao.observeSyncState(recordId),
            subcoolReadingDao.observeSyncState(recordId),
            escalationDao.observeSyncState(recordId),
        ) { wellTest, wellheadLog, subcool, escalation ->
            // Exactly one table owns the id, so at most one flow is non-null.
            wellTest ?: wellheadLog ?: subcool ?: escalation
        }
            .mapNotNull { it }
            .distinctUntilChanged()

    /**
     * Find the record owning [recordId] across the four syncable tables, apply
     * [transform] to its [SyncMetadata], and persist the updated row. A record
     * whose sync metadata is absent (an unqueued subcool reading) or an unknown
     * id is a no-op, matching the iOS mutators which silently ignore a missing
     * record.
     */
    private suspend fun mutate(
        recordId: UUID,
        transform: (SyncMetadata) -> SyncMetadata,
    ) {
        wellTestDao.getById(recordId)?.let { entity ->
            wellTestDao.update(entity.copy(sync = transform(entity.sync.toDomain()).toEntity()))
            return
        }
        wellheadLogDao.getById(recordId)?.let { entity ->
            wellheadLogDao.update(entity.copy(sync = transform(entity.sync.toDomain()).toEntity()))
            return
        }
        escalationDao.getById(recordId)?.let { entity ->
            escalationDao.update(entity.copy(sync = transform(entity.sync.toDomain()).toEntity()))
            return
        }
        subcoolReadingDao.getById(recordId)?.let { entity ->
            val meta = entity.sync.toDomain() ?: return // unqueued reading: nothing to transition
            subcoolReadingDao.update(
                entity.copy(sync = transform(meta).toOptionalEntity()),
            )
            return
        }
    }
}
