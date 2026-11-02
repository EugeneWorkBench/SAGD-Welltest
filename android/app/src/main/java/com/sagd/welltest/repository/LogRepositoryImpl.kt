/*
 * LogRepository over Room.
 *
 * Transactional, storage-aware persistence of wellhead logs and well tests,
 * with the same success / rollback / storage-full semantics as the iOS
 * repository (design.md "Schema and transactions", Req 1.1, 1.4, 1.5):
 *
 *   - Success: the record is committed inside a single Room transaction with
 *     syncState = PENDING, createdAt = now, retryCount = 0, and the caller gets
 *     SaveResult.Success so the UI can confirm the save within 2s (Req 1.1).
 *   - Insufficient storage: a pre-write free-space check returns
 *     SaveResult.StorageFull without writing anything; a disk-full error raised
 *     mid-write is also translated to StorageFull (Req 1.5).
 *   - I/O failure: the transaction rolls back, leaving the prior persisted
 *     state untouched, and the caller gets SaveResult.Failure (Req 1.4).
 *
 * A single Room @Transaction (via `withTransaction`) wrapping the upsert gives
 * the all-or-nothing guarantee: Room rolls the transaction back if the body
 * throws, so a failed write can never leave a partially modified store.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.AppDatabase
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellTestDao
import java.time.Clock

/**
 * @param transactionRunner the atomic write boundary; commits on success and
 *   rolls back (propagating the throw) on failure (Req 1.4). In production this
 *   is [roomTransactionRunner] over the [AppDatabase].
 * @param wellheadLogDao DAO for the `wellheadLog` table.
 * @param wellTestDao DAO for the `wellTest` table.
 * @param storageGuard pre-write free-space check (Req 1.5).
 * @param clock source of the commit timestamp; injectable for deterministic tests.
 */
class LogRepositoryImpl(
    private val transactionRunner: TransactionRunner,
    private val wellheadLogDao: WellheadLogDao,
    private val wellTestDao: WellTestDao,
    private val storageGuard: StorageGuard,
    private val clock: Clock = Clock.systemUTC(),
) : LogRepository {

    /**
     * Production constructor: runs saves inside a real Room transaction over
     * [db]. Preserves the original call site that passed the database directly.
     */
    constructor(
        db: AppDatabase,
        wellheadLogDao: WellheadLogDao,
        wellTestDao: WellTestDao,
        storageGuard: StorageGuard,
        clock: Clock = Clock.systemUTC(),
    ) : this(roomTransactionRunner(db), wellheadLogDao, wellTestDao, storageGuard, clock)

    override suspend fun saveWellheadLog(log: WellheadLog): SaveResult {
        if (!storageGuard.hasFreeSpace()) return SaveResult.StorageFull
        val normalized = log.copy(sync = log.sync.normalizedForSave(clock.instant()))
        return runTransactionalSave {
            wellheadLogDao.upsert(normalized.toEntity())
        }
    }

    override suspend fun saveWellTest(test: WellTest): SaveResult {
        if (!storageGuard.hasFreeSpace()) return SaveResult.StorageFull
        val normalized = test.copy(sync = test.sync.normalizedForSave(clock.instant()))
        return runTransactionalSave {
            wellTestDao.upsert(normalized.toEntity())
        }
    }

    /**
     * Run [write] inside a single Room transaction and classify the outcome.
     * A thrown disk-full error maps to [SaveResult.StorageFull]; any other
     * thrown error rolls the transaction back and maps to [SaveResult.Failure],
     * leaving the prior persisted state untouched (Req 1.4).
     */
    private suspend fun runTransactionalSave(write: suspend () -> Unit): SaveResult =
        try {
            transactionRunner.withTransaction { write() }
            SaveResult.Success
        } catch (t: Throwable) {
            if (isDiskFull(t)) {
                SaveResult.StorageFull
            } else {
                SaveResult.Failure(t.message ?: t::class.qualifiedName ?: "save failed")
            }
        }
}
