/*
 * Cloud_Backend connection configuration and authentication.
 *
 * Holds the base URL every endpoint is resolved against and the bearer-token
 * provider that supplies the `Authorization` header on each request. The design
 * states authentication is via a bearer token with the details out of scope
 * (design.md "Cloud Backend API Contract"), so the token is sourced through a
 * small [AuthTokenProvider] seam: the app wires in whatever concrete token store
 * it uses (e.g. an encrypted-prefs-backed provider) during graph composition
 * (task 23.2), and tests inject a fixed-token stub.
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

/**
 * Supplies the current bearer token for the signed-in field operator.
 *
 * Kept as a suspend function because a real implementation may refresh an
 * expired token (a network/IO call) before returning it. A `null` return means
 * no token is available; the client then sends the request without an
 * `Authorization` header and the backend's `401` is surfaced as a retryable
 * failure (a later cycle can succeed once a token is present).
 */
interface AuthTokenProvider {
    /** The bearer token to send, or null if none is currently available. */
    suspend fun bearerToken(): String?
}

/**
 * A trivial [AuthTokenProvider] that always returns the same token (or `null`).
 *
 * Useful when the running app is configured with a static bearer token and in
 * tests. Mirrors the iOS `StaticAuthTokenProvider`. A real deployment can swap
 * this for an implementation that reads an encrypted token store and refreshes
 * an expired token before returning it — the composition root is the single
 * place that wires which provider is used.
 */
class StaticAuthTokenProvider(private val token: String?) : AuthTokenProvider {
    override suspend fun bearerToken(): String? = token
}

/**
 * Static base URL + token configuration for the REST client.
 *
 * @property baseUrl the Cloud_Backend origin, without a trailing slash
 *   (e.g. `https://api.sagd.example.com`). Endpoint paths such as
 *   `/v1/well-tests` are appended to this.
 * @property tokenProvider source of the bearer token for the `Authorization`
 *   header.
 */
class BackendConfig(
    baseUrl: String,
    val tokenProvider: AuthTokenProvider,
) {
    /** The base URL with any trailing slash trimmed, so path joins are clean. */
    val baseUrl: String = baseUrl.trimEnd('/')

    /** Compose the absolute URL for [path] (which must begin with `/`). */
    fun url(path: String): String = baseUrl + path
}
