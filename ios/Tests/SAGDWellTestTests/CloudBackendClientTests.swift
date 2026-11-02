//
//  CloudBackendClientTests.swift
//  Unit tests for the REST API client (task 22.1).
//
//  Exercises the client's behavior through the `HTTPTransport` seam with a
//  recording stub, so no network is required:
//    * request building — endpoint URL, method, auth + content-type headers,
//      and JSON body for each push kind
//    * acknowledgment decoding — `remoteId` taken from the body on 2xx
//    * status classification — 2xx -> acknowledged; 409/422 -> non-retryable;
//      5xx/401 -> retryable; transport error/timeout -> retryable
//    * idempotency surface — the client id travels on every request
//    * multipart assembly — the escalation body carries metadata + the exact
//      photo bytes
//    * pull + cache — pulled wells/tests/tasks are written to the cache
//
//  Each test uses in-memory doubles for the payload source and cache; the
//  transport stub returns canned responses or throws canned transport errors.
//

import XCTest
@testable import SAGDWellTest

final class CloudBackendClientTests: XCTestCase {

    private let baseURL = URL(string: "https://api.example.test")!

    // MARK: - Request building

    func testWellTestPushBuildsAuthorizedJSONPostToEndpoint() async throws {
        let test = WellTest(
            id: UUID(),
            wellId: "W-1",
            oilRatio: 0.5,
            waterRatio: 0.3,
            gasRatio: 0.2,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let transport = StubTransport(response: .acknowledged(remoteId: "r-1", clientId: test.id.uuidString))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellTests: [test.id: test]))

        _ = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

        let request = try XCTUnwrap(transport.lastRequest)
        XCTAssertEqual(request.method, .post)
        XCTAssertEqual(request.url.absoluteString, "https://api.example.test/v1/well-tests")
        XCTAssertEqual(request.headers["Authorization"], "Bearer test-token")
        XCTAssertEqual(request.headers["Content-Type"], "application/json")

        // The JSON body carries the captured fields and the client id, and no
        // sync metadata.
        let body = try XCTUnwrap(request.body)
        let json = try JSONSerialization.jsonObject(with: body) as? [String: Any]
        XCTAssertEqual(json?["id"] as? String, test.id.uuidString)
        XCTAssertEqual(json?["wellId"] as? String, "W-1")
        XCTAssertEqual(json?["oilRatio"] as? Double, 0.5)
        XCTAssertNil(json?["syncState"])
        XCTAssertNil(json?["retryCount"])
    }

    func testWellheadLogPushTargetsLogsEndpoint() async throws {
        let log = WellheadLog(
            wellId: "W-2",
            logType: .production,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_100),
            emulsionFlowRate: 12.5,
            waterCutPercent: 40
        )
        let transport = StubTransport(response: .acknowledged(remoteId: "r-2", clientId: log.id.uuidString))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellheadLogs: [log.id: log]))

        _ = await client.push(SyncableRecord(id: log.id, kind: .wellheadLog, sync: log.sync))

        XCTAssertEqual(transport.lastRequest?.url.absoluteString, "https://api.example.test/v1/wellhead-logs")
    }

    func testSubcoolReadingPushTargetsReadingsEndpoint() async throws {
        let reading = SubcoolReading(
            casingPressure: 1_000,
            pumpTemperature: 150,
            saturationTemperature: 180,
            subcoolValue: 30,
            isFlashing: false,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_200),
            sync: SyncMetadata()
        )
        let transport = StubTransport(response: .acknowledged(remoteId: "r-3", clientId: reading.id.uuidString))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(subcoolReadings: [reading.id: reading]))

        _ = await client.push(SyncableRecord(id: reading.id, kind: .subcoolReading, sync: reading.sync ?? SyncMetadata()))

        XCTAssertEqual(transport.lastRequest?.url.absoluteString, "https://api.example.test/v1/subcool-readings")
    }

    func testMissingTokenOmitsAuthorizationHeader() async throws {
        let test = sampleWellTest()
        let transport = StubTransport(response: .acknowledged(remoteId: "r", clientId: test.id.uuidString))
        let client = makeClient(
            transport: transport,
            payload: InMemoryPayloadSource(wellTests: [test.id: test]),
            token: nil
        )

        _ = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

        XCTAssertNil(transport.lastRequest?.headers["Authorization"])
    }

    // MARK: - Acknowledgment / classification

    func testAcknowledgedResultCarriesBackendRemoteId() async {
        let test = sampleWellTest()
        let transport = StubTransport(response: .acknowledged(remoteId: "server-42", clientId: test.id.uuidString))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellTests: [test.id: test]))

        let result = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

        XCTAssertEqual(result, .acknowledged(remoteId: "server-42"))
    }

    func testConflictAndUnprocessableAreNonRetryable() async {
        for status in [409, 422] {
            let test = sampleWellTest()
            let transport = StubTransport(response: .status(status))
            let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellTests: [test.id: test]))

            let result = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

            switch result {
            case .nonRetryableFailure:
                break
            default:
                XCTFail("status \(status) should classify as non-retryable, got \(result)")
            }
        }
    }

    func testServerErrorAndUnauthorizedAreRetryable() async {
        for status in [500, 503, 401, 408] {
            let test = sampleWellTest()
            let transport = StubTransport(response: .status(status))
            let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellTests: [test.id: test]))

            let result = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

            switch result {
            case .retryableFailure:
                break
            default:
                XCTFail("status \(status) should classify as retryable, got \(result)")
            }
        }
    }

    func testTransportErrorIsRetryable() async {
        let test = sampleWellTest()
        let transport = StubTransport(error: HTTPTransportError(isTimeout: true, reason: "timed out"))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellTests: [test.id: test]))

        let result = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

        XCTAssertEqual(result, .retryableFailure(reason: "timed out"))
    }

    func testAcknowledgedFallsBackToClientIdWhenBodyMalformed() async {
        let test = sampleWellTest()
        let transport = StubTransport(response: .rawBody(statusCode: 200, body: Data("not json".utf8)))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(wellTests: [test.id: test]))

        let result = await client.push(SyncableRecord(id: test.id, kind: .wellTest, sync: test.sync))

        XCTAssertEqual(result, .acknowledged(remoteId: test.id.uuidString))
    }

    func testMissingPayloadIsNonRetryable() async {
        let id = UUID()
        let transport = StubTransport(response: .status(200))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource())

        let result = await client.push(SyncableRecord(id: id, kind: .wellTest, sync: SyncMetadata()))

        switch result {
        case .nonRetryableFailure:
            break
        default:
            XCTFail("a missing local payload should be non-retryable, got \(result)")
        }
        XCTAssertNil(transport.lastRequest, "no request should be made when there is no payload")
    }

    func testEscalationDescriptorThroughPushIsNonRetryable() async {
        // Escalations must go through `upload(escalation:blobURL:)`, not `push`.
        let transport = StubTransport(response: .status(200))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource())

        let result = await client.push(SyncableRecord(id: UUID(), kind: .escalation, sync: SyncMetadata()))

        switch result {
        case .nonRetryableFailure:
            break
        default:
            XCTFail("escalation via push should be non-retryable, got \(result)")
        }
    }

    // MARK: - Escalation multipart upload

    func testEscalationUploadBuildsMultipartWithPhotoBytes() async throws {
        let photo = Data([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11, 0x22])
        let blobURL = try writeTempBlob(photo)
        defer { try? FileManager.default.removeItem(at: blobURL) }

        let escalation = Escalation(
            wellId: "W-9",
            priority: .high,
            photoRef: "photo.blob",
            latitude: 56.7,
            longitude: -111.4,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_300)
        )
        let transport = StubTransport(response: .acknowledged(remoteId: "esc-1", clientId: escalation.id.uuidString))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource())

        let result = await client.upload(escalation: escalation, blobURL: blobURL)

        XCTAssertEqual(result, .acknowledged(remoteId: "esc-1"))
        let request = try XCTUnwrap(transport.lastRequest)
        XCTAssertEqual(request.url.absoluteString, "https://api.example.test/v1/escalations")
        let contentType = try XCTUnwrap(request.headers["Content-Type"])
        XCTAssertTrue(contentType.hasPrefix("multipart/form-data; boundary="))

        // The assembled body must contain the metadata part markers and the
        // exact photo bytes (Property 24 is covered separately by task 22.3;
        // here we assert byte inclusion for the request builder).
        let body = try XCTUnwrap(request.body)
        XCTAssertTrue(body.range(of: Data("name=\"metadata\"".utf8)) != nil)
        XCTAssertTrue(body.range(of: Data("name=\"photo\"".utf8)) != nil)
        XCTAssertNotNil(body.range(of: photo), "the body must embed the exact photo bytes")
    }

    func testMultipartBodyStructureIsWellFormed() throws {
        let boundary = "BOUNDARY123"
        let metadata = Data(#"{"id":"abc"}"#.utf8)
        let photo = Data([0x01, 0x02, 0x03])

        let body = CloudBackendClient.multipartBody(
            boundary: boundary,
            metadataJSON: metadata,
            photoData: photo,
            photoFileName: "p.blob"
        )
        let text = String(decoding: body, as: UTF8.self)

        XCTAssertTrue(text.contains("--BOUNDARY123\r\n"))
        XCTAssertTrue(text.contains("Content-Disposition: form-data; name=\"metadata\""))
        XCTAssertTrue(text.contains("Content-Disposition: form-data; name=\"photo\"; filename=\"p.blob\""))
        XCTAssertTrue(text.hasSuffix("--BOUNDARY123--\r\n"))
        XCTAssertNotNil(body.range(of: photo))
    }

    func testEscalationUploadWithUnreadableBlobIsRetryable() async {
        let escalation = Escalation(
            wellId: "W-9",
            priority: .low,
            photoRef: "missing.blob",
            capturedAt: Date()
        )
        let transport = StubTransport(response: .status(200))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource())
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).blob")

        let result = await client.upload(escalation: escalation, blobURL: missing)

        switch result {
        case .retryableFailure:
            break
        default:
            XCTFail("an unreadable blob should be retryable, got \(result)")
        }
    }

    // MARK: - Pull + cache

    func testPullWellCachesDecodedWell() async throws {
        let well = Well(id: "W-5", name: "Well Five", type: .injection, updatedAt: Date(timeIntervalSince1970: 1_700_000_400))
        let dto = WellPullDTO(id: well.id, name: well.name, type: well.type.rawValue, padId: nil, updatedAt: well.updatedAt)
        let body = try CloudBackendCoding.makeEncoder().encode(dto)
        let transport = StubTransport(response: .rawBody(statusCode: 200, body: body))
        let cache = RecordingCache()
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(), cache: cache)

        let pulled = await client.pullWell(id: "W-5")

        XCTAssertEqual(pulled?.id, "W-5")
        XCTAssertEqual(transport.lastRequest?.url.absoluteString, "https://api.example.test/v1/wells/W-5")
        XCTAssertEqual(cache.cachedWells.map(\.id), ["W-5"])
    }

    func testPullWellTestsRequestsDescOrderAndCaches() async throws {
        let dto = WellTestPullDTO(
            id: UUID().uuidString,
            wellId: "W-6",
            oilRatio: 1, waterRatio: 2, gasRatio: 3,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_500),
            remoteId: "rid-1"
        )
        let body = try CloudBackendCoding.makeEncoder().encode([dto])
        let transport = StubTransport(response: .rawBody(statusCode: 200, body: body))
        let cache = RecordingCache()
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(), cache: cache)

        let tests = await client.pullWellTests(wellId: "W-6")

        XCTAssertEqual(tests.count, 1)
        XCTAssertEqual(tests.first?.sync.syncState, .synchronized)
        XCTAssertEqual(tests.first?.sync.remoteId, "rid-1")
        let url = try XCTUnwrap(transport.lastRequest?.url)
        XCTAssertEqual(url.path, "/v1/wells/W-6/well-tests")
        XCTAssertEqual(url.query, "order=desc")
        XCTAssertEqual(cache.cachedWellTests.count, 1)
    }

    func testPullOpenMaintenanceTasksRequestsOpenStatusAndCaches() async throws {
        let dto = MaintenanceTaskPullDTO(
            id: UUID().uuidString,
            wellId: "W-7",
            description: "Inspect ESP",
            status: MaintenanceTaskStatus.open.rawValue,
            createdAt: Date(timeIntervalSince1970: 1_700_000_600)
        )
        let body = try CloudBackendCoding.makeEncoder().encode([dto])
        let transport = StubTransport(response: .rawBody(statusCode: 200, body: body))
        let cache = RecordingCache()
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(), cache: cache)

        let tasks = await client.pullOpenMaintenanceTasks(wellId: "W-7")

        XCTAssertEqual(tasks.count, 1)
        XCTAssertEqual(transport.lastRequest?.url.query, "status=open")
        XCTAssertEqual(cache.cachedTasks.count, 1)
    }

    func testPullFailureDoesNotCacheAndReturnsEmpty() async {
        let transport = StubTransport(response: .status(503))
        let cache = RecordingCache()
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource(), cache: cache)

        let tests = await client.pullWellTests(wellId: "W-8")

        XCTAssertTrue(tests.isEmpty)
        XCTAssertTrue(cache.cachedWellTests.isEmpty)
    }

    func testPullWellsUpdatedSinceEncodesTimestampQuery() async throws {
        let body = try CloudBackendCoding.makeEncoder().encode([WellPullDTO]())
        let transport = StubTransport(response: .rawBody(statusCode: 200, body: body))
        let client = makeClient(transport: transport, payload: InMemoryPayloadSource())
        let since = Date(timeIntervalSince1970: 1_700_000_000)

        _ = await client.pullWellsUpdated(since: since)

        let url = try XCTUnwrap(transport.lastRequest?.url)
        XCTAssertEqual(url.path, "/v1/wells")
        // The query value is the ISO-8601 timestamp, percent-encoded once.
        let expected = CloudBackendCoding.dateFormatter.string(from: since)
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        XCTAssertEqual(components?.queryItems?.first(where: { $0.name == "updatedSince" })?.value, expected)
    }

    // MARK: - Helpers

    private func makeClient(
        transport: HTTPTransport,
        payload: InMemoryPayloadSource,
        cache: PulledDataCache = RecordingCache(),
        token: String? = "test-token"
    ) -> CloudBackendClient {
        CloudBackendClient(
            configuration: CloudBackendConfiguration(
                baseURL: baseURL,
                tokenProvider: StaticAuthTokenProvider(token: token)
            ),
            transport: transport,
            payloadSource: payload,
            cache: cache
        )
    }

    private func sampleWellTest() -> WellTest {
        WellTest(
            wellId: "W-1",
            oilRatio: 0.4,
            waterRatio: 0.4,
            gasRatio: 0.2,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    private func writeTempBlob(_ data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).blob")
        try data.write(to: url)
        return url
    }
}

// MARK: - Test doubles

/// A recording `HTTPTransport` that returns a canned response (or throws a
/// canned transport error) and captures the last request it was given.
private final class StubTransport: HTTPTransport, @unchecked Sendable {

    enum Canned {
        case acknowledged(remoteId: String, clientId: String)
        case status(Int)
        case rawBody(statusCode: Int, body: Data)
    }

    private let canned: Canned?
    private let error: HTTPTransportError?
    private(set) var lastRequest: HTTPRequest?

    init(response: Canned) {
        self.canned = response
        self.error = nil
    }

    init(error: HTTPTransportError) {
        self.canned = nil
        self.error = error
    }

    func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        lastRequest = request
        if let error { throw error }
        switch canned! {
        case let .acknowledged(remoteId, clientId):
            let ack = AcknowledgmentDTO(id: clientId, remoteId: remoteId, status: "accepted")
            let body = try CloudBackendCoding.makeEncoder().encode(ack)
            return HTTPResponse(statusCode: 200, body: body)
        case let .status(code):
            return HTTPResponse(statusCode: code, body: Data())
        case let .rawBody(code, body):
            return HTTPResponse(statusCode: code, body: body)
        }
    }
}

/// An in-memory `RecordPayloadSource` seeded with records by id.
private struct InMemoryPayloadSource: RecordPayloadSource {
    var wellTests: [UUID: WellTest] = [:]
    var wellheadLogs: [UUID: WellheadLog] = [:]
    var subcoolReadings: [UUID: SubcoolReading] = [:]

    func wellTest(id: UUID) async -> WellTest? { wellTests[id] }
    func wellheadLog(id: UUID) async -> WellheadLog? { wellheadLogs[id] }
    func subcoolReading(id: UUID) async -> SubcoolReading? { subcoolReadings[id] }
}

/// A `PulledDataCache` that records what it was asked to cache.
private final class RecordingCache: PulledDataCache, @unchecked Sendable {
    private(set) var cachedWells: [Well] = []
    private(set) var cachedWellTests: [WellTest] = []
    private(set) var cachedTasks: [MaintenanceTask] = []

    func cacheWell(_ well: Well) async { cachedWells.append(well) }
    func cacheWells(_ wells: [Well]) async { cachedWells.append(contentsOf: wells) }
    func cacheWellTests(_ tests: [WellTest]) async { cachedWellTests.append(contentsOf: tests) }
    func cacheMaintenanceTasks(_ tasks: [MaintenanceTask]) async { cachedTasks.append(contentsOf: tasks) }
}
