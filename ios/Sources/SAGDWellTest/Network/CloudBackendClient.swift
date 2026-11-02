//
//  Cloud backend REST client
//
//  The concrete `URLSession`-backed REST client for the `Cloud_Backend`. It is
//  the production implementation of the two abstract contracts the sync engine
//  depends on:
//
//    * `SyncPushClient`   — pushes a well test / wellhead log / subcool reading
//                           as JSON to its endpoint and resolves the backend's
//                           acknowledgment into a `SyncPushResult`.
//    * `BlobUploadClient` — pushes an escalation as a `multipart/form-data`
//                           request (JSON `metadata` part + binary `photo`
//                           part) to `/v1/escalations`.
//
//  and additionally exposes the pull endpoints used to hydrate the local store
//  for offline QR lookup.
//
//  The engine never sees any of the REST mechanics — it only ever observes the
//  three `SyncPushResult` cases (design "Push ordering and acknowledgment"). To
//  keep every one of those mechanics unit-testable without a live backend, all
//  network I/O goes through the injected `HTTPTransport` seam; tests supply a
//  recording stub and assert the exact request built and the result produced.
//
//  Status-code classification (design "Cloud Backend API Contract"):
//    * 2xx                      -> `.acknowledged(remoteId:)` from the body
//    * 409 / 422                -> `.nonRetryableFailure` (retrying can't help)
//    * everything else (incl.   -> `.retryableFailure` (5xx, 401, timeouts,
//      transport errors)           transport errors): the record stays pending
//                                   and is retried with backoff
//
//  Idempotency is inherent: every request carries the client-generated record
//  `id`, so a retried push of an already-accepted record re-acknowledges with
//  the same `remoteId` and never duplicates (design "Acknowledgment contract").
//
//  Implemented by task 22.1. Wiring into the engine's dependency graph is
//  task 22.2.

import Foundation

/// The production REST client for the `Cloud_Backend`.
///
/// Construct it with a `CloudBackendConfiguration` (base URL + auth), an
/// `HTTPTransport` (real `URLSessionHTTPTransport` in the app; a stub in
/// tests), a `RecordPayloadSource` (loads a record's payload by id for push),
/// and a `PulledDataCache` (writes pulled data into the store).
///
/// For escalations the engine resolves the photo blob URL itself (via its
/// `EscalationBlobSource`) and calls `upload(escalation:blobURL:)`, so the
/// client does not depend on the blob source directly.
public final class CloudBackendClient: SyncPushClient, BlobUploadClient, @unchecked Sendable {

    private let configuration: CloudBackendConfiguration
    private let transport: HTTPTransport
    private let payloadSource: RecordPayloadSource
    private let cache: PulledDataCache

    // `JSONEncoder`/`JSONDecoder` are not `Sendable`, but they are created once
    // in `init`, never reconfigured, and only ever used from within this
    // instance's own methods — so the class is effectively immutable shared
    // state. `@unchecked Sendable` (on the type) records that reasoning, matching
    // the `BlobStore` precedent in the Local Store layer.
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(
        configuration: CloudBackendConfiguration,
        transport: HTTPTransport,
        payloadSource: RecordPayloadSource,
        cache: PulledDataCache
    ) {
        self.configuration = configuration
        self.transport = transport
        self.payloadSource = payloadSource
        self.cache = cache
        self.encoder = CloudBackendCoding.makeEncoder()
        self.decoder = CloudBackendCoding.makeDecoder()
    }

    // MARK: - SyncPushClient

    /// Push a well test / wellhead log / subcool reading as JSON. Escalations
    /// are *not* routed here — the engine sends those through
    /// `BlobUploadClient.upload(escalation:blobURL:)` because they are a
    /// multipart upload; if an escalation descriptor reaches this method it is
    /// a programmer error, so it resolves to a non-retryable failure rather
    /// than silently looping.
    public func push(_ record: SyncableRecord) async -> SyncPushResult {
        do {
            let request: HTTPRequest
            switch record.kind {
            case .wellTest:
                guard let test = await payloadSource.wellTest(id: record.id) else {
                    return .nonRetryableFailure(reason: "well test \(record.id) not found locally")
                }
                request = try await makeJSONPush(endpoint: .wellTests, body: WellTestPushDTO(test))
            case .wellheadLog:
                guard let log = await payloadSource.wellheadLog(id: record.id) else {
                    return .nonRetryableFailure(reason: "wellhead log \(record.id) not found locally")
                }
                request = try await makeJSONPush(endpoint: .wellheadLogs, body: WellheadLogPushDTO(log))
            case .subcoolReading:
                guard let reading = await payloadSource.subcoolReading(id: record.id) else {
                    return .nonRetryableFailure(reason: "subcool reading \(record.id) not found locally")
                }
                request = try await makeJSONPush(endpoint: .subcoolReadings, body: SubcoolReadingPushDTO(reading))
            case .escalation:
                return .nonRetryableFailure(reason: "escalations must be pushed via BlobUploadClient")
            }
            return await performPush(request, clientId: record.id.uuidString)
        } catch {
            // Encoding failures are definitive — the same payload will fail to
            // encode again — so they are non-retryable.
            return .nonRetryableFailure(reason: "failed to encode push: \(error)")
        }
    }

    // MARK: - BlobUploadClient

    /// Upload an escalation as `multipart/form-data`: a JSON `metadata` part
    /// plus a binary `photo` part read from `blobURL`. The engine marks the
    /// escalation `synchronized` only once this (photo included) is
    /// acknowledged (Req 7.6).
    public func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult {
        let photoData: Data
        do {
            photoData = try Data(contentsOf: blobURL)
        } catch {
            // The blob file is missing/unreadable. The row references a blob
            // the invariant says should exist; retry later rather than giving
            // up (the file may be restored / re-resolved).
            return .retryableFailure(reason: "escalation blob unreadable: \(error)")
        }

        do {
            let metadata = try encoder.encode(EscalationMetadataDTO(escalation))
            let boundary = "SAGDWellTest-\(UUID().uuidString)"
            let body = Self.multipartBody(
                boundary: boundary,
                metadataJSON: metadata,
                photoData: photoData,
                photoFileName: escalation.photoRef
            )
            var headers = try await authHeaders()
            headers["Content-Type"] = "multipart/form-data; boundary=\(boundary)"
            let request = HTTPRequest(
                url: endpointURL(.escalations),
                method: .post,
                headers: headers,
                body: body
            )
            return await performPush(request, clientId: escalation.id.uuidString)
        } catch {
            return .nonRetryableFailure(reason: "failed to encode escalation: \(error)")
        }
    }

    // MARK: - Pull endpoints

    /// `GET /v1/wells/{wellId}` — fetch and cache a well's reference data.
    /// Returns the decoded `Well` on success, or `nil` if the request failed or
    /// the well was not found; a failure leaves any previously cached copy
    /// intact so offline lookup still works.
    @discardableResult
    public func pullWell(id wellId: String) async -> Well? {
        guard let response = try? await perform(get: .well(wellId: wellId)),
              (200...299).contains(response.statusCode),
              let dto = try? decoder.decode(WellPullDTO.self, from: response.body),
              let well = dto.domain
        else { return nil }
        await cache.cacheWell(well)
        return well
    }

    /// `GET /v1/wells/{wellId}/well-tests?order=desc` — historical tests,
    /// newest-first, cached for offline QR lookup (Req 6.2, 6.5).
    @discardableResult
    public func pullWellTests(wellId: String) async -> [WellTest] {
        guard let response = try? await perform(get: .wellTests(wellId: wellId)),
              (200...299).contains(response.statusCode),
              let dtos = try? decoder.decode([WellTestPullDTO].self, from: response.body)
        else { return [] }
        let tests = dtos.compactMap(\.domain)
        await cache.cacheWellTests(tests)
        return tests
    }

    /// `GET /v1/wells/{wellId}/maintenance-tasks?status=open` — open tasks,
    /// cached for offline QR lookup (Req 6.2, 6.5).
    @discardableResult
    public func pullOpenMaintenanceTasks(wellId: String) async -> [MaintenanceTask] {
        guard let response = try? await perform(get: .maintenanceTasks(wellId: wellId)),
              (200...299).contains(response.statusCode),
              let dtos = try? decoder.decode([MaintenanceTaskPullDTO].self, from: response.body)
        else { return [] }
        let tasks = dtos.compactMap(\.domain)
        await cache.cacheMaintenanceTasks(tasks)
        return tasks
    }

    /// `GET /v1/wells?updatedSince={ts}` — incremental reference-data sync,
    /// cached into the store.
    @discardableResult
    public func pullWellsUpdated(since timestamp: Date) async -> [Well] {
        guard let response = try? await perform(get: .wellsUpdatedSince(timestamp)),
              (200...299).contains(response.statusCode),
              let dtos = try? decoder.decode([WellPullDTO].self, from: response.body)
        else { return [] }
        let wells = dtos.compactMap(\.domain)
        await cache.cacheWells(wells)
        return wells
    }

    // MARK: - Request building

    /// Build a JSON `POST` request for a push body, with auth + content-type
    /// headers applied.
    private func makeJSONPush<Body: Encodable>(endpoint: Endpoint, body: Body) async throws -> HTTPRequest {
        let data = try encoder.encode(body)
        var headers = try await authHeaders()
        headers["Content-Type"] = "application/json"
        return HTTPRequest(
            url: endpointURL(endpoint),
            method: .post,
            headers: headers,
            body: data
        )
    }

    /// Assemble the headers common to every request: the bearer token (when
    /// available) and `Accept: application/json`. A missing token is allowed
    /// through (the backend answers 401, which is retryable) so a transient
    /// token gap does not strand a record as a permanent failure.
    private func authHeaders() async throws -> [String: String] {
        var headers = ["Accept": "application/json"]
        if let token = await configuration.tokenProvider.currentToken() {
            headers["Authorization"] = "Bearer \(token)"
        }
        return headers
    }

    private func endpointURL(_ endpoint: Endpoint) -> URL {
        // Build the URL with `URLComponents` so each dynamic path/query value is
        // encoded exactly once, by the standard library, with the correct
        // allowed-character set for its position (path segment vs query value).
        guard var components = URLComponents(
            url: configuration.baseURL,
            resolvingAgainstBaseURL: false
        ) else {
            return configuration.baseURL
        }
        components.path = Self.joinPaths(components.path, endpoint.path)
        components.queryItems = endpoint.queryItems.isEmpty ? nil : endpoint.queryItems
        return components.url ?? configuration.baseURL
    }

    private static func joinPaths(_ base: String, _ suffix: String) -> String {
        let trimmedBase = base.hasSuffix("/") ? String(base.dropLast()) : base
        let normalizedSuffix = suffix.hasPrefix("/") ? suffix : "/" + suffix
        return trimmedBase + normalizedSuffix
    }

    private func perform(get endpoint: Endpoint) async throws -> HTTPResponse {
        let headers = try await authHeaders()
        let request = HTTPRequest(url: endpointURL(endpoint), method: .get, headers: headers)
        return try await transport.perform(request)
    }

    // MARK: - Push execution + classification

    /// Perform a push request and classify the outcome into a
    /// `SyncPushResult`. A transport error (no response) is retryable; an HTTP
    /// response is classified by status code.
    private func performPush(_ request: HTTPRequest, clientId: String) async -> SyncPushResult {
        let response: HTTPResponse
        do {
            response = try await transport.perform(request)
        } catch let error as HTTPTransportError {
            return .retryableFailure(reason: error.reason)
        } catch {
            return .retryableFailure(reason: String(describing: error))
        }
        return classify(response, clientId: clientId)
    }

    /// Map an HTTP status code + body onto the engine's three-case result.
    private func classify(_ response: HTTPResponse, clientId: String) -> SyncPushResult {
        switch response.statusCode {
        case 200...299:
            // Decode the acknowledgment contract. The returned `remoteId` is
            // what the engine stores. If the body cannot be decoded the ack is
            // malformed; fall back to the client id so the record is not
            // stranded (idempotency means a later re-push re-acks cleanly).
            if let ack = try? decoder.decode(AcknowledgmentDTO.self, from: response.body) {
                return .acknowledged(remoteId: ack.remoteId)
            }
            return .acknowledged(remoteId: clientId)
        case 409, 422:
            // Definitive client-side rejection: a conflicting duplicate or a
            // validation failure. Retrying the identical payload cannot
            // succeed (design "Conflict handling").
            return .nonRetryableFailure(reason: "backend rejected with \(response.statusCode)")
        default:
            // 5xx, 401, 408, and anything else transient: keep the record and
            // retry with backoff.
            return .retryableFailure(reason: "backend responded \(response.statusCode)")
        }
    }

    // MARK: - Multipart assembly

    /// Build a `multipart/form-data` body with a JSON `metadata` part and a
    /// binary `photo` part. Kept `static` and pure so it is directly
    /// unit-testable and so the photo bytes are copied into the body exactly as
    /// stored (Property 24).
    static func multipartBody(
        boundary: String,
        metadataJSON: Data,
        photoData: Data,
        photoFileName: String
    ) -> Data {
        var body = Data()
        let crlf = "\r\n"
        func append(_ string: String) { body.append(Data(string.utf8)) }

        // metadata part (JSON)
        append("--\(boundary)\(crlf)")
        append("Content-Disposition: form-data; name=\"metadata\"\(crlf)")
        append("Content-Type: application/json\(crlf)\(crlf)")
        body.append(metadataJSON)
        append(crlf)

        // photo part (binary)
        append("--\(boundary)\(crlf)")
        append("Content-Disposition: form-data; name=\"photo\"; filename=\"\(photoFileName)\"\(crlf)")
        append("Content-Type: application/octet-stream\(crlf)\(crlf)")
        body.append(photoData)
        append(crlf)

        // closing boundary
        append("--\(boundary)--\(crlf)")
        return body
    }
}

// MARK: - Endpoints

/// A resolved endpoint from the design's contract table: a path (segments
/// already joined, with dynamic parts inserted verbatim — `URLComponents`
/// encodes them once when the URL is built) plus any query items. Centralizing
/// these means the client and its tests agree on exactly one spelling.
struct Endpoint {
    /// The path, beginning with `/v1/…`. Dynamic components are inserted raw;
    /// `URLComponents.path` percent-encodes the whole path once.
    var path: String
    /// Query items, encoded once by `URLComponents.queryItems`.
    var queryItems: [URLQueryItem]

    private init(path: String, queryItems: [URLQueryItem] = []) {
        self.path = path
        self.queryItems = queryItems
    }

    static let wellTests = Endpoint(path: "/v1/well-tests")
    static let wellheadLogs = Endpoint(path: "/v1/wellhead-logs")
    static let subcoolReadings = Endpoint(path: "/v1/subcool-readings")
    static let escalations = Endpoint(path: "/v1/escalations")

    static func well(wellId: String) -> Endpoint {
        Endpoint(path: "/v1/wells/\(wellId)")
    }

    static func wellTests(wellId: String) -> Endpoint {
        Endpoint(
            path: "/v1/wells/\(wellId)/well-tests",
            queryItems: [URLQueryItem(name: "order", value: "desc")]
        )
    }

    static func maintenanceTasks(wellId: String) -> Endpoint {
        Endpoint(
            path: "/v1/wells/\(wellId)/maintenance-tasks",
            queryItems: [URLQueryItem(name: "status", value: "open")]
        )
    }

    static func wellsUpdatedSince(_ timestamp: Date) -> Endpoint {
        let ts = CloudBackendCoding.dateFormatter.string(from: timestamp)
        return Endpoint(
            path: "/v1/wells",
            queryItems: [URLQueryItem(name: "updatedSince", value: ts)]
        )
    }
}
