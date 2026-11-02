//
//  HTTP transport seam
//
//  The REST client never touches `URLSession` directly; it depends on this
//  narrow `HTTPTransport` protocol instead. That keeps the client unit-testable
//  without a live network: tests inject a stub transport that returns canned
//  responses (or throws canned transport errors), and the production app wires
//  in `URLSessionHTTPTransport`, a thin adapter over `URLSession`.
//
//  The seam is deliberately minimal — one request type in, one response (or a
//  thrown transport error) out — so that *all* of the REST semantics (endpoint
//  selection, header assembly, JSON/multipart encoding, status-code
//  classification, acknowledgment decoding) live in the client and are
//  exercised by tests through the stub, while the only thing that is *not*
//  unit-tested is the handful of lines that marshal to `URLSession`.
//
//  Introduced by task 22.1.

import Foundation

/// An HTTP request the transport should perform. A value type so it is trivial
/// for a stub to inspect (assert the method, URL, headers, and body a test
/// expects) and for the adapter to translate into a `URLRequest`.
public struct HTTPRequest: Sendable, Equatable {
    public enum Method: String, Sendable, Equatable {
        case get = "GET"
        case post = "POST"
        case put = "PUT"
    }

    /// The fully-resolved absolute URL (base URL + path + query already joined).
    public var url: URL
    /// The HTTP method.
    public var method: Method
    /// Header fields to set on the request (already including auth + content
    /// type). Ordered deterministically by the client so tests can assert.
    public var headers: [String: String]
    /// The request body, if any. For JSON pushes this is the encoded JSON; for
    /// multipart escalation uploads this is the assembled multipart body.
    public var body: Data?

    public init(
        url: URL,
        method: Method,
        headers: [String: String] = [:],
        body: Data? = nil
    ) {
        self.url = url
        self.method = method
        self.headers = headers
        self.body = body
    }
}

/// The transport's view of an HTTP response: just the status code and the body
/// bytes. The client interprets both — mapping the status onto the
/// retryable/non-retryable classification and decoding the body as the
/// acknowledgment contract or a pulled resource.
public struct HTTPResponse: Sendable, Equatable {
    /// The HTTP status code (e.g. 200, 201, 409, 422, 503).
    public var statusCode: Int
    /// The raw response body bytes (possibly empty).
    public var body: Data

    public init(statusCode: Int, body: Data = Data()) {
        self.statusCode = statusCode
        self.body = body
    }
}

/// A transport-level failure: no HTTP response was produced at all (the
/// connection failed, timed out, or was otherwise interrupted). These are
/// always treated as *retryable* by the client — a request that never reached
/// the backend may succeed on a later connectivity event.
///
/// Distinguished from an HTTP error *response* (a 4xx/5xx `HTTPResponse`),
/// which the client classifies by status code.
public struct HTTPTransportError: Error, Sendable, Equatable {
    /// Whether the failure was a timeout specifically (surfaced for diagnostics
    /// / the retryable reason string). Timeouts are retryable like any other
    /// transport error.
    public var isTimeout: Bool
    /// A human-readable diagnostic, carried into the `retryableFailure` reason.
    public var reason: String

    public init(isTimeout: Bool, reason: String) {
        self.isTimeout = isTimeout
        self.reason = reason
    }
}

/// Performs a single HTTP request. The one seam between the REST client and the
/// network stack.
///
/// Implementations MUST either return an `HTTPResponse` (any status code) or
/// throw an `HTTPTransportError` (connection/timeout failure). They MUST NOT
/// throw anything else; the client's classification relies on this contract.
public protocol HTTPTransport: Sendable {
    /// Perform `request` and return the response, or throw a
    /// `HTTPTransportError` if the request could not be completed.
    func perform(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// The production `HTTPTransport`: a thin adapter over `URLSession`.
///
/// This is the only part of the Network layer that is not exercised by unit
/// tests — it contains no REST logic, only the mechanical translation between
/// `HTTPRequest`/`HTTPResponse` and `URLRequest`/`URLResponse`. All decisions
/// (which endpoint, which headers, how to classify a status) live in the REST
/// client and are tested through the stub transport.
public final class URLSessionHTTPTransport: HTTPTransport {

    private let session: URLSession

    /// Creates a transport over a `URLSession`.
    ///
    /// - Parameter session: the session to use. Defaults to a session with the
    ///   default configuration. For blob uploads that must survive suspension,
    ///   callers pass a session created from a background configuration.
    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method.rawValue
        for (field, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: field)
        }
        urlRequest.httpBody = request.body

        do {
            let (data, response) = try await session.data(for: urlRequest)
            guard let http = response as? HTTPURLResponse else {
                // A non-HTTP response is unexpected; treat as a transport error
                // so the record is retried rather than silently lost.
                throw HTTPTransportError(isTimeout: false, reason: "non-HTTP response")
            }
            return HTTPResponse(statusCode: http.statusCode, body: data)
        } catch let error as HTTPTransportError {
            throw error
        } catch let urlError as URLError {
            throw HTTPTransportError(
                isTimeout: urlError.code == .timedOut,
                reason: urlError.localizedDescription
            )
        } catch {
            throw HTTPTransportError(isTimeout: false, reason: String(describing: error))
        }
    }
}
