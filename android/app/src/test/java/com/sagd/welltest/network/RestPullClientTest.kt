/*
 * Unit tests for the REST pull client (Android) — task 23.1.
 *
 * Drives [RestPullClient] against a stub [HttpTransport] and a recording
 * [PullCache]. Verifies the pull endpoints are addressed correctly, the bodies
 * decode to domain objects, the decoded data is cached into the local store
 * (so QR lookup works offline — Req 6.2, 6.5), and non-2xx responses classify
 * as NotFound (404) or RetryableFailure (5xx / transport).
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellTest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class RestPullClientTest : StringSpec({

    val baseUrl = "https://api.example.test"
    fun config() = BackendConfig(baseUrl, object : AuthTokenProvider {
        override suspend fun bearerToken(): String? = "tok"
    })

    "pullWell decodes the well and caches it" {
        val body = """
            {"id":"WELL-1","name":"North Pad 1","type":"production",
             "padId":"PAD-A","updatedAt":"2023-11-14T22:13:20Z"}
        """.trimIndent()
        val transport = RecordingTransport(HttpResponse(200, body))
        val cache = RecordingCache()
        val client = RestPullClient(config(), transport, cache)

        val result = client.pullWell("WELL-1")

        result.shouldBeInstanceOf<PullResult.Success<Well>>()
        result.value.name shouldBe "North Pad 1"
        transport.lastRequest!!.url shouldBe "$baseUrl/v1/wells/WELL-1"
        cache.cachedWells.map { it.id } shouldBe listOf("WELL-1")
    }

    "pullWellTests requests order=desc and caches the tests" {
        val body = """
            [
              {"id":"11111111-1111-1111-1111-111111111111","wellId":"WELL-1",
               "oilRatio":0.5,"waterRatio":0.3,"gasRatio":0.2,
               "capturedAt":"2023-11-14T22:13:20Z","remoteId":"r-1"}
            ]
        """.trimIndent()
        val transport = RecordingTransport(HttpResponse(200, body))
        val cache = RecordingCache()
        val client = RestPullClient(config(), transport, cache)

        val result = client.pullWellTests("WELL-1")

        result.shouldBeInstanceOf<PullResult.Success<List<WellTest>>>()
        transport.lastRequest!!.url shouldBe "$baseUrl/v1/wells/WELL-1/well-tests?order=desc"
        cache.cachedTests["WELL-1"]!!.size shouldBe 1
    }

    "pullOpenMaintenanceTasks requests status=open and caches the tasks" {
        val body = """
            [
              {"id":"22222222-2222-2222-2222-222222222222","wellId":"WELL-1",
               "description":"Replace seal","status":"open",
               "createdAt":"2023-11-14T22:13:20Z"}
            ]
        """.trimIndent()
        val transport = RecordingTransport(HttpResponse(200, body))
        val cache = RecordingCache()
        val client = RestPullClient(config(), transport, cache)

        val result = client.pullOpenMaintenanceTasks("WELL-1")

        result.shouldBeInstanceOf<PullResult.Success<List<MaintenanceTask>>>()
        transport.lastRequest!!.url shouldBe
            "$baseUrl/v1/wells/WELL-1/maintenance-tasks?status=open"
        cache.cachedTasks["WELL-1"]!!.single().status shouldBe MaintenanceStatus.OPEN
    }

    "pullWellsUpdatedSince encodes the timestamp and caches the wells" {
        val body = """
            [{"id":"WELL-1","name":"A","type":"injection",
              "padId":null,"updatedAt":"2023-11-14T22:13:20Z"}]
        """.trimIndent()
        val transport = RecordingTransport(HttpResponse(200, body))
        val cache = RecordingCache()
        val client = RestPullClient(config(), transport, cache)

        val result = client.pullWellsUpdatedSince(Instant.ofEpochSecond(1_700_000_000L))

        result.shouldBeInstanceOf<PullResult.Success<List<Well>>>()
        // The ISO instant is URL-encoded (':' -> %3A) in the query.
        transport.lastRequest!!.url.startsWith("$baseUrl/v1/wells?updatedSince=") shouldBe true
        cache.cachedWellBatches.single().single().id shouldBe "WELL-1"
    }

    "a 404 on a well pull is NotFound and caches nothing" {
        val transport = RecordingTransport(HttpResponse(404, ""))
        val cache = RecordingCache()
        val client = RestPullClient(config(), transport, cache)

        client.pullWell("missing") shouldBe PullResult.NotFound
        cache.cachedWells.isEmpty() shouldBe true
    }

    "a 500 on a pull is a retryable failure" {
        val transport = RecordingTransport(HttpResponse(500, "oops"))
        val client = RestPullClient(config(), transport, RecordingCache())

        client.pullWell("WELL-1").shouldBeInstanceOf<PullResult.RetryableFailure>()
    }

    "a transport failure on a pull is a retryable failure" {
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse =
                throw HttpTransportException("down")
        }
        val client = RestPullClient(config(), transport, RecordingCache())

        client.pullWell("WELL-1").shouldBeInstanceOf<PullResult.RetryableFailure>()
    }

    "a malformed 2xx body is a retryable failure and caches nothing" {
        val transport = RecordingTransport(HttpResponse(200, "}{ not json"))
        val cache = RecordingCache()
        val client = RestPullClient(config(), transport, cache)

        client.pullWell("WELL-1").shouldBeInstanceOf<PullResult.RetryableFailure>()
        cache.cachedWells.isEmpty() shouldBe true
    }
})

// --- test doubles ---

private class RecordingTransport(private val response: HttpResponse) : HttpTransport {
    var lastRequest: HttpRequest? = null
    override suspend fun send(request: HttpRequest): HttpResponse {
        lastRequest = request
        return response
    }
}

private class RecordingCache : PullCache {
    val cachedWells = mutableListOf<Well>()
    val cachedWellBatches = mutableListOf<List<Well>>()
    val cachedTests = mutableMapOf<String, List<WellTest>>()
    val cachedTasks = mutableMapOf<String, List<MaintenanceTask>>()

    override suspend fun cacheWell(well: Well) { cachedWells += well }
    override suspend fun cacheWells(wells: List<Well>) { cachedWellBatches += wells }
    override suspend fun cacheWellTests(wellId: String, tests: List<WellTest>) {
        cachedTests[wellId] = tests
    }
    override suspend fun cacheMaintenanceTasks(wellId: String, tasks: List<MaintenanceTask>) {
        cachedTasks[wellId] = tasks
    }
}
