//
//  Cloud backend configuration and auth
//
//  Groups the two things every request to the `Cloud_Backend` needs: where it
//  lives (`baseURL`) and how it authenticates (`AuthTokenProvider`). Both are
//  injected so the running app can point at a real environment with a real
//  bearer token, while tests point at an arbitrary base URL with a fixed token
//  (or no token) and assert the exact URL and `Authorization` header the client
//  produced.
//
//  Introduced by task 22.1.

import Foundation

/// Supplies the bearer token used to authenticate requests.
///
/// Modeled as an `async` protocol so a real implementation can refresh an
/// expired token (e.g. from the keychain or an auth service) before each
/// request without blocking; the client awaits it once per request and sets the
/// `Authorization` header. A `nil` return means "no token available" — the
/// request proceeds unauthenticated and the backend will reject it with a 401,
/// which the client classifies as retryable (the token may appear later).
///
/// Authentication details beyond "a bearer token" are out of scope for this
/// design (design "Cloud Backend API Contract").
public protocol AuthTokenProvider: Sendable {
    /// The current bearer token, or `nil` if none is available yet.
    func currentToken() async -> String?
}

/// A trivial `AuthTokenProvider` that always returns the same token. Useful in
/// the running app when a static token is configured, and in tests.
public struct StaticAuthTokenProvider: AuthTokenProvider {
    private let token: String?

    public init(token: String?) {
        self.token = token
    }

    public func currentToken() async -> String? { token }
}

/// Immutable configuration for the REST client: the backend base URL and the
/// auth token provider.
///
/// `baseURL` is the origin + any common prefix (e.g.
/// `https://api.example.com`); the client appends the versioned paths
/// (`/v1/well-tests`, …) to it. Keeping the version in the paths (not the base
/// URL) matches the design's contract table.
public struct CloudBackendConfiguration: Sendable {
    /// The backend base URL. Endpoint paths are resolved against this.
    public var baseURL: URL
    /// Supplies the bearer token for the `Authorization` header.
    public var tokenProvider: AuthTokenProvider

    public init(baseURL: URL, tokenProvider: AuthTokenProvider) {
        self.baseURL = baseURL
        self.tokenProvider = tokenProvider
    }
}
