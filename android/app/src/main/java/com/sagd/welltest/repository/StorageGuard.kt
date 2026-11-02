/*
 * Pre-write free-space guard.
 *
 * Mirrors the iOS repository's storage-full handling: before a transactional
 * save the repository asks whether the device has at least a small minimum of
 * free space, and if not it returns [com.sagd.welltest.domain.SaveResult.StorageFull]
 * without writing anything, so the UI retains the operator's in-form data
 * (Req 1.5). The iOS side realizes this as a free-space check plus a catch of
 * the platform disk-full error; this type is the Android analogue of the
 * free-space check, and the repositories additionally translate a disk-full
 * [java.io.IOException] caught during a write into StorageFull (design.md
 * "Local store errors").
 *
 * The guard is an interface so tests can inject a deterministic fake (report a
 * full disk on demand) rather than depending on the real device filesystem.
 */
package com.sagd.welltest.repository

import android.os.StatFs
import java.io.File

/**
 * Reports whether the local store has room for another record.
 *
 * Implementations are cheap and side-effect free; a repository calls
 * [hasFreeSpace] once per save before opening the write transaction.
 */
interface StorageGuard {
    /**
     * True if at least [requiredBytes] of usable space remains for the store.
     *
     * @param requiredBytes the minimum free space a save needs. Defaults to
     *   [DEFAULT_MIN_FREE_BYTES]; the escalation path passes the photo size plus
     *   the row headroom so a large photo is rejected up front.
     */
    fun hasFreeSpace(requiredBytes: Long = DEFAULT_MIN_FREE_BYTES): Boolean

    companion object {
        /**
         * Headroom required for a plain row save (log / test). A row is a few
         * hundred bytes; this generous floor leaves room for SQLite's journal
         * and index pages so a save never fails the OS allocator mid-transaction.
         */
        const val DEFAULT_MIN_FREE_BYTES: Long = 1L * 1024 * 1024 // 1 MiB
    }
}

/**
 * [StorageGuard] backed by [StatFs] over the filesystem that hosts the local
 * store. Point [storageDir] at the directory containing the database file (and,
 * for escalations, the blob store), since both live under the app sandbox on
 * the same volume.
 */
class FileSystemStorageGuard(
    private val storageDir: File,
) : StorageGuard {

    override fun hasFreeSpace(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(storageDir.absolutePath)
            stat.availableBytes >= requiredBytes
        } catch (_: IllegalArgumentException) {
            // StatFs throws if the path is invalid/unmounted. Treat an
            // unreadable volume conservatively as "no room" so the save is
            // rejected and the operator's data is retained rather than lost to
            // a failed write.
            false
        }
    }
}
