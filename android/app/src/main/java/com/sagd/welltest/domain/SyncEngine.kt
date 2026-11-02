/*
 * Sync engine contract.
 *
 * Platform-neutral interface for the background component that monitors
 * connectivity, selects unsynchronized records oldest-first, pushes them
 * (including photo blobs), and advances each record's sync-state machine. The
 * Android implementation runs inside a CoroutineWorker driven by WorkManager
 * (sync layer, task 16). Mirrors the iOS SyncEngine protocol. See design.md
 * "Sync engine component".
 */
package com.sagd.welltest.domain

interface SyncEngine {
    /** Triggered by the connectivity monitor; schedules a sync within 60s (Req 2.2). */
    suspend fun onConnectivityAvailable()

    /** Pulls a batch oldest-first, pushes each, and advances the state machine (Req 2.1-2.5). */
    suspend fun runSyncCycle()

    /** Multipart upload of an escalation's JSON plus its photo blob (Req 7.6). */
    suspend fun uploadPhotoBlob(escalation: Escalation)
}
