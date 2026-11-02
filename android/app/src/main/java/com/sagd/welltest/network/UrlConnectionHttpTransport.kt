/*
 * Production HTTP transport over HttpURLConnection.
 *
 * The one component that actually opens a socket. It is deliberately the only
 * network-touching class so everything above it ([RestSyncPushClient],
 * [RestPullClient]) is tested against a stub [HttpTransport]. Built on the
 * platform's `HttpURLConnection` rather than Retrofit/OkHttp because the project
 * declares no HTTP dependency (android/gradle/libs.versions.toml) and this
 * environment cannot build-verify a new one; this mirrors the iOS client's use
 * of the built-in `URLSession`.
 *
 * Behavior the client above relies on:
 *   - Blocking IO is moved off the caller's thread onto [ioDispatcher]
 *     (Dispatchers.IO) so a suspend `send` never blocks the main thread.
 *   - A connect/read timeout or any `IOException` before a status line is
 *     received is rethrown as [HttpTransportException] so the client classifies
 *     it as a *retryable* failure, distinct from a 4xx/5xx status.
 *   - A non-2xx status is still returned as an [HttpResponse] (its body read
 *     from the error stream) so the client can distinguish 409/422 from 5xx.
 *   - Multipart bodies are framed here with a generated boundary; the client
 *     supplies only the parts, keeping it free of transport framing concerns.
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/**
 * @param connectTimeoutMs TCP connect timeout.
 * @param readTimeoutMs socket read timeout; a slow backend surfaces as a
 *   retryable transport failure.
 * @param ioDispatcher dispatcher the blocking request runs on; overridable in
 *   tests.
 */
class UrlConnectionHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpResponse =
        withContext(ioDispatcher) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method.name
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            try {
                writeBody(connection, request.body)

                // getResponseCode() forces the request to be sent; an IO/timeout
                // error here means no status was received -> retryable.
                val status = connection.responseCode
                val body = connection.readBodyText(status)
                HttpResponse(statusCode = status, body = body)
            } catch (e: SocketTimeoutException) {
                throw HttpTransportException("request timed out", e)
            } catch (e: IOException) {
                throw HttpTransportException("transport failure: ${e.message}", e)
            } finally {
                connection.disconnect()
            }
        }

    private fun writeBody(connection: HttpURLConnection, body: HttpBody) {
        when (body) {
            is HttpBody.None -> Unit

            is HttpBody.Json -> {
                connection.doOutput = true
                if (connection.getRequestProperty("Content-Type") == null) {
                    connection.setRequestProperty("Content-Type", "application/json")
                }
                BufferedOutputStream(connection.outputStream).use { out ->
                    out.write(body.text.toByteArray(Charsets.UTF_8))
                }
            }

            is HttpBody.Multipart -> {
                val boundary = "----sagd${UUID.randomUUID().toString().replace("-", "")}"
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary",
                )
                BufferedOutputStream(connection.outputStream).use { out ->
                    for (part in body.parts) {
                        out.write("--$boundary$CRLF".toByteArray(Charsets.UTF_8))
                        val disposition = buildString {
                            append("Content-Disposition: form-data; name=\"${part.name}\"")
                            part.filename?.let { append("; filename=\"$it\"") }
                        }
                        out.write("$disposition$CRLF".toByteArray(Charsets.UTF_8))
                        out.write(
                            "Content-Type: ${part.contentType}$CRLF$CRLF".toByteArray(Charsets.UTF_8),
                        )
                        out.write(part.bytes)
                        out.write(CRLF.toByteArray(Charsets.UTF_8))
                    }
                    out.write("--$boundary--$CRLF".toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    /**
     * Read the response body. On a 2xx the input stream carries it; on an error
     * status the body (if any) is on the error stream. A null stream yields "".
     */
    private fun HttpURLConnection.readBodyText(status: Int): String {
        val stream = if (status in 200..299) inputStream else errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}
