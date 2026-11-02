/*
 * Shared save-path support for the transactional repositories.
 *
 * Centralizes the two concerns every save shares so [LogRepositoryImpl] and
 * [EscalationRepositoryImpl] stay thin and behave identically to iOS:
 *
 *   1. Sync metadata normalization. A freshly captured record is always
 *      committed as PENDING with retryCount = 0 and createdAt = now, regardless
 *      of what the caller put in the in-memory record (design.md "Schema and
 *      transactions"). This keeps the push queue's ordering key authoritative
 *      and prevents a caller from smuggling in a non-pending state.
 *   2. Disk-full classification. A write that fails because the volume is full
 *      must surface as StorageFull, not a generic Failure, so the UI shows the
 *      storage-full message (Req 1.5). SQLite/Room raise an SQLiteFullException
 *      for a full database and the blob store raises an IOException whose cause
 *      chain mentions ENOSPC; [isDiskFull] recognizes both.
 */
package com.sagd.welltest.repository

import android.database.sqlite.SQLiteFullException
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import java.time.Instant

/**
 * Returns a copy of [this] normalized for a fresh commit: PENDING state,
 * retryCount 0, createdAt stamped to [now], and no prior attempt/remote id.
 *
 * `createdAt` is the single oldest-first ordering key (Req 2.1), so it is set
 * at save time rather than trusted from the caller.
 */
internal fun SyncMetadata.normalizedForSave(now: Instant): SyncMetadata =
    copy(
        syncState = SyncState.PENDING,
        retryCount = 0,
        createdAt = now,
        lastAttemptAt = null,
        remoteId = null,
    )

/**
 * True if [throwable] (or anything in its cause chain) indicates the device ran
 * out of storage while writing — either SQLite reporting a full database or a
 * filesystem `ENOSPC` surfaced as an [java.io.IOException].
 *
 * The repositories catch write failures, ask this, and translate a disk-full
 * failure into StorageFull while letting every other failure become Failure.
 */
internal fun isDiskFull(throwable: Throwable): Boolean {
    var cause: Throwable? = throwable
    while (cause != null) {
        if (cause is SQLiteFullException) return true
        val message = cause.message?.uppercase().orEmpty()
        if (message.contains("ENOSPC") ||
            message.contains("NO SPACE LEFT") ||
            message.contains("DISK IS FULL") ||
            message.contains("DATABASE OR DISK IS FULL")
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}
