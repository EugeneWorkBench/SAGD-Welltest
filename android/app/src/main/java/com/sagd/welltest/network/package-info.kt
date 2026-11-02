/**
 * Network layer.
 *
 * Cloud_Backend REST API client: push endpoints (well tests, wellhead logs,
 * subcool readings, multipart escalations) and pull endpoints (well reference
 * data, historical tests, open maintenance tasks). Used only by the sync engine.
 *
 * Layout (task 23.1):
 *   - SyncPushClient / BlobUploadClient  the engine-facing contract (task 16.1).
 *   - RestSyncPushClient                 concrete REST push + multipart upload.
 *   - RestPullClient / PullCache         reference-data pull + local caching.
 *   - HttpTransport / UrlConnection...   injectable transport seam + impl.
 *   - JsonCodec                          org.json wire encode/decode.
 *   - BackendConfig / AuthTokenProvider  base URL + bearer auth.
 *   - PushPayloadSource                  loads a queued record's payload.
 * The Room-backed PushPayloadSource and PullCache live in the repository layer.
 */
package com.sagd.welltest.network
