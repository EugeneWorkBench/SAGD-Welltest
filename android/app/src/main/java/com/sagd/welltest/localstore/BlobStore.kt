/*
 * Photo blob file store (Android).
 *
 * Escalation photos are NOT stored as BLOB columns. They are written as files
 * in app-internal storage and referenced from the escalation row by a short
 * opaque [photoRef] string (design.md "Blob (photo) storage"). Keeping photos
 * out of SQLite keeps the database small and lets the background uploader
 * stream the file directly as multipart without loading it into memory.
 *
 * Ordering and crash-safety (Req 7.3, Req 7.4):
 *   - The blob file is written BEFORE the referencing DB row. The escalation
 *     save path calls [write] first to obtain a [photoRef], then inserts the
 *     row. A crash between the two leaves at most an orphan blob file, never a
 *     row pointing at a missing file.
 *   - Each write is atomic: bytes land in a temp file that is fsync'd and then
 *     atomically renamed into place, so a crash mid-write never leaves a
 *     half-written file that a later row could reference.
 *   - Orphan blobs (files with no referencing row, e.g. from a crash before the
 *     row was committed) are reclaimed by [garbageCollect] on launch.
 *
 * This mirrors the iOS BlobStore, which writes into Application Support and runs
 * the same launch-time orphan sweep.
 */
package com.sagd.welltest.localstore

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * File-based store for escalation photo blobs, rooted at a single directory
 * inside app-internal storage.
 *
 * Construct with the app's private files directory, e.g. from an Android
 * `Context`:
 *
 * ```
 * val blobStore = BlobStore(context.filesDir)
 * ```
 *
 * `filesDir` is app-private (not world-readable, excluded from most backups)
 * and lives under the app sandbox, satisfying the "app-internal storage"
 * requirement. The store confines all files to a `blobs/` subdirectory so the
 * orphan sweep can reason about the full set of blobs it owns.
 *
 * All methods are safe to call from a background thread. Instances are
 * stateless beyond the immutable [directory]; callers may share one instance.
 *
 * @param baseDir the app-internal base directory (typically `context.filesDir`).
 */
class BlobStore(baseDir: File) {

    /** The directory that holds all photo blobs owned by this store. */
    val directory: File = File(baseDir, BLOB_SUBDIR)

    init {
        // Ensure the blob directory exists up front so the first write and the
        // launch-time garbage collection both have a real directory to work in.
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create blob directory: ${directory.absolutePath}")
        }
    }

    /**
     * Atomically write [bytes] as a new blob and return its [photoRef].
     *
     * The escalation save path MUST call this before inserting the escalation
     * row, and store the returned ref in `Escalation.photoRef` (Req 7.3, 7.4).
     *
     * Each call creates a fresh, unique ref — the store never overwrites an
     * existing blob — so a retry that fails partway cannot corrupt a previously
     * written photo. The bytes are flushed and fsync'd to a temp file, then
     * atomically renamed to the final name; a crash before the rename leaves
     * only the temp file, which [garbageCollect] reclaims.
     *
     * @param bytes the encoded photo (e.g. JPEG) to persist.
     * @return the opaque reference to store in the escalation row.
     * @throws IOException if the bytes cannot be durably written.
     */
    @Throws(IOException::class)
    fun write(bytes: ByteArray): String {
        val ref = UUID.randomUUID().toString()
        val target = fileForRef(ref)
        // Temp file lives in the same directory so the rename is a cheap,
        // atomic metadata operation on the same filesystem.
        val temp = File(directory, "$TEMP_PREFIX$ref")

        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                // Force bytes + metadata to disk before we publish the file via
                // rename, so a power loss can't leave a referenced-but-empty blob.
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                // Fall back to a copy if an atomic rename isn't possible; still
                // never leaves a partially named final file on failure.
                temp.copyTo(target, overwrite = false)
                temp.delete()
            }
        } catch (e: IOException) {
            // Best-effort cleanup of the temp file; the original throw wins.
            temp.delete()
            throw e
        }
        return ref
    }

    /**
     * Read the blob bytes for [photoRef].
     *
     * @return the stored bytes, or `null` if no blob exists for the ref (for
     *   example, it was already garbage-collected or never written).
     * @throws IOException if the blob exists but cannot be read.
     */
    @Throws(IOException::class)
    fun read(photoRef: String): ByteArray? {
        val file = fileForRef(photoRef)
        return if (file.isFile) file.readBytes() else null
    }

    /**
     * Resolve [photoRef] to its backing [File] for streaming uploads.
     *
     * The sync engine's multipart uploader uses this to stream the blob
     * directly off disk rather than buffering it in memory (Req 7.6).
     *
     * @return the file for the ref, or `null` if it does not exist.
     */
    fun file(photoRef: String): File? = fileForRef(photoRef).takeIf { it.isFile }

    /** True if a blob is currently stored for [photoRef]. */
    fun exists(photoRef: String): Boolean = fileForRef(photoRef).isFile

    /**
     * Delete the blob for [photoRef] if present.
     *
     * @return `true` if a file was deleted, `false` if there was nothing to
     *   delete (already absent). Deleting a missing blob is not an error.
     */
    fun delete(photoRef: String): Boolean {
        val file = fileForRef(photoRef)
        return file.isFile && file.delete()
    }

    /**
     * Reclaim orphan blob files on launch (Req 7.3, 7.4).
     *
     * Deletes every blob in the store whose ref is not present in
     * [referencedRefs] — the set of `photoRef` values still referenced by rows
     * in the database. This removes photos left behind by a crash that occurred
     * after [write] but before the escalation row was committed, as well as any
     * leftover temp files from an interrupted write.
     *
     * Callers MUST pass the complete set of referenced refs (e.g. from a
     * `SELECT photoRef FROM escalation`). Passing an incomplete set would delete
     * live photos, so this is invoked from a single launch-time sweep after the
     * database is opened, never mid-session.
     *
     * @param referencedRefs every `photoRef` still referenced by a persisted row.
     * @return the number of orphan files deleted.
     */
    fun garbageCollect(referencedRefs: Set<String>): Int {
        val entries = directory.listFiles() ?: return 0
        var deleted = 0
        for (entry in entries) {
            if (!entry.isFile) continue
            val name = entry.name
            // Any leftover temp file is by definition an interrupted write with
            // no committed row; always reclaim it.
            val isTemp = name.startsWith(TEMP_PREFIX)
            val isOrphan = name !in referencedRefs
            if ((isTemp || isOrphan) && entry.delete()) {
                deleted++
            }
        }
        return deleted
    }

    private fun fileForRef(photoRef: String): File = File(directory, photoRef)

    private companion object {
        /** Subdirectory under the app-internal base dir that holds all blobs. */
        const val BLOB_SUBDIR = "blobs"

        /** Prefix marking in-progress writes; such files are never referenced. */
        const val TEMP_PREFIX = "tmp-"
    }
}
