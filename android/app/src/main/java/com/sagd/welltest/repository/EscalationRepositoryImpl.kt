/*
 * EscalationRepository over Room + the blob store.
 *
 * An escalation owns two artifacts that must stay consistent: the photo blob
 * file and the DB row referencing it. The design fixes a strict order to keep
 * them crash-safe (design.md "Blob (photo) storage", Req 7.3, 7.4):
 *
 *   1. Pre-write free-space check — rejects with StorageFull up front, sized to
 *      the photo plus row headroom, so a large photo on a near-full disk never
 *      starts the two-step write (Req 1.5).
 *   2. Write the blob file FIRST. A crash after this but before the row leaves
 *      at most an orphan blob, which the launch-time garbage collector reclaims
 *      — never a row pointing at a missing file.
 *   3. Insert the row inside a single Room transaction. If the row write fails,
 *      the transaction rolls back AND the just-written blob is deleted, so a
 *      failed save leaves the store exactly as it was (Req 1.4, 7.4). A disk
 *      full raised at either step maps to StorageFull.
 *
 * This mirrors the iOS EscalationRepository, which writes into the blob store
 * then the GRDB row and deletes the blob if the row write fails.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationRepository
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.localstore.AppDatabase
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import java.time.Clock

/**
 * @param transactionRunner the atomic write boundary for the row insert;
 *   commits on success and rolls back (propagating the throw) on failure
 *   (Req 1.4). In production this is [roomTransactionRunner] over the
 *   [AppDatabase].
 * @param escalationDao DAO for the `escalation` table.
 * @param blobStore file store for the escalation photo blob (Req 7.3).
 * @param storageGuard pre-write free-space check (Req 1.5).
 * @param clock source of the commit timestamp; injectable for deterministic tests.
 */
class EscalationRepositoryImpl(
    private val transactionRunner: TransactionRunner,
    private val escalationDao: EscalationDao,
    private val blobStore: BlobStore,
    private val storageGuard: StorageGuard,
    private val clock: Clock = Clock.systemUTC(),
) : EscalationRepository {

    /**
     * Production constructor: runs the row insert inside a real Room
     * transaction over [db]. Preserves the original call site that passed the
     * database directly.
     */
    constructor(
        db: AppDatabase,
        escalationDao: EscalationDao,
        blobStore: BlobStore,
        storageGuard: StorageGuard,
        clock: Clock = Clock.systemUTC(),
    ) : this(roomTransactionRunner(db), escalationDao, blobStore, storageGuard, clock)

    override suspend fun saveEscalation(escalation: Escalation, photoData: ByteArray): SaveResult {
        // (1) Size the free-space check to the photo plus row headroom so a
        // large photo is rejected before we touch the disk (Req 1.5).
        val required = photoData.size.toLong() + StorageGuard.DEFAULT_MIN_FREE_BYTES
        if (!storageGuard.hasFreeSpace(required)) return SaveResult.StorageFull

        // (2) Write the blob FIRST so a crash before the row commits can only
        // orphan a blob, never strand a row (Req 7.3, 7.4).
        val photoRef =
            try {
                blobStore.write(photoData)
            } catch (t: Throwable) {
                return if (isDiskFull(t)) SaveResult.StorageFull else failure(t)
            }

        // (3) Insert the referencing row atomically. On any failure roll back
        // and delete the blob just written, restoring the prior state (Req 1.4).
        val normalized = escalation.copy(sync = escalation.sync.normalizedForSave(clock.instant()))
        return try {
            transactionRunner.withTransaction {
                escalationDao.upsert(normalized.toEntity(photoRef))
            }
            SaveResult.Success
        } catch (t: Throwable) {
            // The row never committed; drop the now-unreferenced blob so the
            // store is left exactly as it was before the save attempt.
            blobStore.delete(photoRef)
            if (isDiskFull(t)) SaveResult.StorageFull else failure(t)
        }
    }

    private fun failure(t: Throwable): SaveResult.Failure =
        SaveResult.Failure(t.message ?: t::class.qualifiedName ?: "save failed")
}
