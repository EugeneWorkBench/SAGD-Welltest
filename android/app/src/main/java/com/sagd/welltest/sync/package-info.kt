/**
 * Sync engine layer.
 *
 * Background reconciliation to the Cloud_Backend: connectivity monitoring
 * (ConnectivityManager.NetworkCallback), oldest-first push queue, per-record
 * retry/backoff state machine, and multipart photo blob upload, realized over
 * WorkManager (CoroutineWorker).
 */
package com.sagd.welltest.sync
