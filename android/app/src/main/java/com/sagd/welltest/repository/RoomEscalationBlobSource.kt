/*
 * Room + blob-store backed escalation blob source.
 *
 * The production [EscalationBlobSource] used by the sync engine to assemble an
 * escalation's multipart push (Req 7.6): it loads the escalation row from Room
 * and resolves its photo blob to an on-disk path via the [BlobStore]. Lives in
 * the repository layer because the EscalationEntity -> domain mapper is
 * package-internal here. Mirrors the iOS `GRDBEscalationBlobSource`.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.sync.EscalationBlob
import com.sagd.welltest.sync.EscalationBlobSource
import java.util.UUID

/**
 * @param escalationDao DAO for the `escalation` table.
 * @param blobStore the photo blob file store resolving a `photoRef` to a file.
 */
class RoomEscalationBlobSource(
    private val escalationDao: EscalationDao,
    private val blobStore: BlobStore,
) : EscalationBlobSource {

    override suspend fun escalationWithBlob(id: UUID): EscalationBlob? {
        val escalation = escalationDao.getById(id)?.toDomain() ?: return null
        val blobFile = blobStore.file(escalation.photoRef) ?: return null
        return EscalationBlob(escalation = escalation, blobPath = blobFile.absolutePath)
    }
}
