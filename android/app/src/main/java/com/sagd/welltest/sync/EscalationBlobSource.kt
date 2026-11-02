/*
 * Escalation blob source.
 *
 * To push an escalation the engine needs the escalation's fields (for the JSON
 * part) and the on-disk path of its photo blob (for the binary part). This seam
 * resolves both from the local store without the engine depending on Room or
 * the blob store directly, keeping the core cycle testable with a fake source.
 * Mirrors the iOS `EscalationBlobSource` protocol.
 *
 * Introduced by task 16.1. The Room + blob-store backed implementation lives in
 * the repository layer (RoomEscalationBlobSource) where the entity→domain
 * mapper is visible.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import java.util.UUID

/** An escalation resolved together with the filesystem path of its photo blob. */
data class EscalationBlob(
    val escalation: Escalation,
    val blobPath: String,
)

/** Resolves an escalation record and the file path of its photo blob by id. */
interface EscalationBlobSource {
    /**
     * Load the escalation with [id] and the absolute path of its photo blob, or
     * `null` if the escalation is missing or its blob cannot be resolved.
     */
    suspend fun escalationWithBlob(id: UUID): EscalationBlob?
}
