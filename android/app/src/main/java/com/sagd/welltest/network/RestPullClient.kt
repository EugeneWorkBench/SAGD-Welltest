/*
 * REST pull client — reference-data sync into the Local_Store.
 *
 * Fetches the read-only reference data the QR lookup depends on — a well, its
 * historical tests (newest-first), its open maintenance tasks, and incremental
 * well updates — and caches the result into the local store so QR lookup works
 * offline (design.md "Pull endpoints"; Req 6.2, 6.5). The sync engine pushes
 * captured data up; this client pulls reference data down.
 *
 * It depends on the same injected [HttpTransport] and [JsonCodec] as the push
 * client, and writes through the [PullCache] seam rather than Room directly, so
 * it stays in the network layer and is unit-testable with a stub transport +
 * recording cache. The Room-backed cache lives in the repository layer
 * ([com.sagd.welltest.repository.RoomPullCache]).
 *
 * Classification mirrors the push client: a transport failure or 5xx surfaces
 * as [PullResult.RetryableFailure]; a 404 for a well is [PullResult.NotFound];
 * a 2xx caches and returns [PullResult.Success].
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellTest
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Outcome of a pull request. */
sealed interface PullResult<out T> {
    /** 2xx: the payload, already cached into the local store. */
    data class Success<T>(val value: T) : PullResult<T>

    /** 404: the requested well does not exist on the backend. */
    data object NotFound : PullResult<Nothing>

    /** Transport failure, timeout, or 5xx: safe to retry later. */
    data class RetryableFailure(val reason: String) : PullResult<Nothing>
}

/**
 * Writes pulled reference data into the Local_Store so it is available offline.
 * Each method upserts; the Room-backed implementation uses REPLACE semantics so
 * a re-pull refreshes in place without duplicates.
 */
interface PullCache {
    suspend fun cacheWell(well: Well)

    suspend fun cacheWells(wells: List<Well>)

    suspend fun cacheWellTests(wellId: String, tests: List<WellTest>)

    suspend fun cacheMaintenanceTasks(wellId: String, tasks: List<MaintenanceTask>)
}

/**
 * @param config base URL + bearer-token provider.
 * @param transport injectable HTTP transport.
 * @param cache writes pulled data into the local store.
 */
class RestPullClient(
    private val config: BackendConfig,
    private val transport: HttpTransport,
    private val cache: PullCache,
) {

    /** `GET /v1/wells/{wellId}` → cache and return the [Well]. */
    suspend fun pullWell(wellId: String): PullResult<Well> =
        get("/v1/wells/${encode(wellId)}") { body ->
            JsonCodec.decodeWell(body).also { cache.cacheWell(it) }
        }

    /** `GET /v1/wells/{wellId}/well-tests?order=desc` → cache newest-first tests. */
    suspend fun pullWellTests(wellId: String): PullResult<List<WellTest>> =
        get("/v1/wells/${encode(wellId)}/well-tests?order=desc") { body ->
            JsonCodec.decodeWellTests(body).also { cache.cacheWellTests(wellId, it) }
        }

    /** `GET /v1/wells/{wellId}/maintenance-tasks?status=open` → cache open tasks. */
    suspend fun pullOpenMaintenanceTasks(wellId: String): PullResult<List<MaintenanceTask>> =
        get("/v1/wells/${encode(wellId)}/maintenance-tasks?status=open") { body ->
            JsonCodec.decodeMaintenanceTasks(body)
                .also { cache.cacheMaintenanceTasks(wellId, it) }
        }

    /** `GET /v1/wells?updatedSince={ts}` → cache the incremental well set. */
    suspend fun pullWellsUpdatedSince(since: Instant): PullResult<List<Well>> {
        val ts = DateTimeFormatter.ISO_INSTANT.format(since)
        return get("/v1/wells?updatedSince=${encode(ts)}") { body ->
            JsonCodec.decodeWells(body).also { cache.cacheWells(it) }
        }
    }

    /**
     * Execute a GET for [path], decoding+caching the body via [decode] on 2xx.
     * Classifies 404 as [PullResult.NotFound], transport/5xx as retryable.
     */
    private suspend fun <T> get(
        path: String,
        decode: suspend (String) -> T,
    ): PullResult<T> {
        val request = HttpRequest(
            method = HttpMethod.GET,
            url = config.url(path),
            headers = authHeaders(),
            body = HttpBody.None,
        )
        val response = try {
            transport.send(request)
        } catch (e: HttpTransportException) {
            return PullResult.RetryableFailure(e.message ?: "transport failure")
        }

        val code = response.statusCode
        return when {
            code in 200..299 ->
                runCatching { decode(response.body) }
                    .fold(
                        onSuccess = { PullResult.Success(it) },
                        onFailure = { PullResult.RetryableFailure("malformed body: ${it.message}") },
                    )

            code == 404 -> PullResult.NotFound

            else -> PullResult.RetryableFailure("pull failed: HTTP $code")
        }
    }

    private suspend fun authHeaders(): Map<String, String> =
        buildMap {
            config.tokenProvider.bearerToken()?.let { put("Authorization", "Bearer $it") }
        }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
