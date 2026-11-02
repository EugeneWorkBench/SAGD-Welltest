/*
 * HTTP transport seam.
 *
 * The REST client talks to the Cloud_Backend only through this narrow
 * interface, never a concrete HTTP library. Keeping the transport injectable is
 * the same interface-seam discipline the sync engine uses for [SyncPushClient]:
 * the core request-building and response-classification logic in
 * [RestSyncPushClient] / [RestPullClient] is driven in unit tests by a stub
 * transport that returns canned [HttpResponse]s, so no socket is ever opened in
 * a test. The production transport ([UrlConnectionHttpTransport]) is the only
 * place that touches the network.
 *
 * This mirrors the iOS client, which abstracts `URLSession` behind a protocol
 * for the same reason (task 22.1 "keep the transport injectable so tests can
 * stub responses"). The project ships no Retrofit/OkHttp dependency; rather than
 * add one that cannot be build-verified in this environment, the concrete
 * transport is built on the JDK/Android `HttpURLConnection` already on the
 * platform, exactly as iOS uses the built-in `URLSession`.
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

/** The HTTP verbs the Cloud_Backend contract uses (design.md "Cloud Backend API Contract"). */
enum class HttpMethod {
    GET,
    POST,
}

/**
 * One part of a `multipart/form-data` body. Used only by the escalation push,
 * which sends a JSON `metadata` part plus a binary `photo` part (Req 7.6).
 *
 * @property name the form field name (`metadata` or `photo`).
 * @property filename optional filename for a binary part; null for a plain field.
 * @property contentType the part's `Content-Type` (e.g. `application/json` or
 *   `image/jpeg`).
 * @property bytes the raw part payload.
 */
data class MultipartPart(
    val name: String,
    val filename: String?,
    val contentType: String,
    val bytes: ByteArray,
) {
    // Value-based equality on the byte payload (data classes compare arrays by
    // reference by default), so recorded requests can be asserted in tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MultipartPart) return false
        return name == other.name &&
            filename == other.filename &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * The body of an outbound request. Either a single JSON document or a set of
 * multipart parts; a GET carries [None].
 */
sealed interface HttpBody {
    /** No body (GET requests). */
    data object None : HttpBody

    /** A JSON request body sent with `Content-Type: application/json`. */
    data class Json(val text: String) : HttpBody

    /** A `multipart/form-data` body (escalation push: JSON metadata + photo). */
    data class Multipart(val parts: List<MultipartPart>) : HttpBody
}

/**
 * A fully-addressed request. The URL is already composed (base URL + path +
 * query); [headers] already include the bearer `Authorization` header supplied
 * by the [AuthTokenProvider]. The transport is deliberately dumb: it does not
 * know endpoints, auth, or JSON — it only sends bytes and returns bytes.
 */
data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody = HttpBody.None,
)

/**
 * A raw HTTP response. [statusCode] is the integer status the client maps onto
 * the three [SyncPushResult] cases; [body] is the (possibly empty) response
 * text the client parses for the acknowledgment contract or pulled JSON.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * Sends a single HTTP request and returns its response.
 *
 * Implementations MUST translate a connection/transport failure (no route,
 * reset connection, or a read/connect timeout) into a thrown
 * [HttpTransportException] rather than a status code, so the client can
 * classify it as a *retryable* failure distinctly from a `4xx`/`5xx` status.
 */
interface HttpTransport {
    /**
     * Execute [request] and return the server's [HttpResponse].
     *
     * @throws HttpTransportException on a transport-level failure (timeout,
     *   connection reset, DNS/route failure) — i.e. no HTTP status was received.
     */
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * A transport-level failure where no HTTP status was received (timeout,
 * connection reset, unreachable host). The client treats this as a retryable
 * failure (design.md: "`5x`/timeouts are retryable").
 */
class HttpTransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
