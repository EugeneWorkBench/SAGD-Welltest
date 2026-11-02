/*
 * Push payload source.
 *
 * The sync engine hands the push client only a [SyncableRecord] *descriptor*
 * (id + type + sync metadata) — never a payload — so the engine stays decoupled
 * from every record shape (see [SyncPushClient] KDoc). The concrete REST client
 * is therefore responsible for loading the record's payload from the local
 * store before encoding it. This seam is that loader: given a record id it
 * returns the matching pure-domain object to serialize.
 *
 * Keeping it an interface (rather than letting the client reach into Room)
 * preserves the layer boundary — the network layer depends on the domain model
 * only — and lets the client be unit-tested with an in-memory payload source,
 * exactly as the engine is tested with a stub push client. The Room-backed
 * implementation lives in the repository layer ([com.sagd.welltest.repository.
 * RoomPushPayloadSource]) where the entity→domain mappers are visible, mirroring
 * [RoomEscalationBlobSource].
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import java.util.UUID

/**
 * Resolves the payload for a queued record by its client id. Each method
 * returns `null` when no row with that id exists in the owning table, which the
 * client treats as a retryable failure (the row may appear on a later cycle,
 * and it is never acknowledged without a payload).
 */
interface PushPayloadSource {
    /** Load the well test with [id], or null if absent. */
    suspend fun wellTest(id: UUID): WellTest?

    /** Load the wellhead log with [id], or null if absent. */
    suspend fun wellheadLog(id: UUID): WellheadLog?

    /** Load the subcool reading with [id], or null if absent. */
    suspend fun subcoolReading(id: UUID): SubcoolReading?
}
