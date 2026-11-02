//
//  EndToEndSyncIntegrationTests.swift
//  Integration test — end-to-end sync against a stub backend (iOS) — task 22.4.
//
//  _Requirements: 2.1, 2.3, 7.6_
//
//  Design "Integration testing" (end-to-end wiring, stub Cloud_Backend): this
//  test exercises the *real* vertical slice of the sync path with only the
//  single outermost seam faked — the `HTTPTransport`. Everything between a
//  repository `save` and the `HTTPTransport.perform(_:)` call is production
//  code:
//
//    repository (GRDBLogRepository / GRDBEscalationRepository, real transactional
//        saves that assign `pending` sync metadata and stamp `createdAt`)
//      -> GRDBSyncRepository (real oldest-first batching + state transitions)
//      -> DefaultSyncEngine (the real actor cycle, routing, and state machine)
//      -> CloudBackendClient (the real REST client: endpoint selection, JSON /
//         multipart encoding, status classification, acknowledgment decoding)
//      -> HTTPTransport (the ONLY fake — a programmable stub Cloud_Backend)
//
//  The store is a temp-directory GRDB `AppDatabase` (a real on-disk SQLite file
//  with foreign keys enforced and the full migrated schema, torn down at the end
//  of each test), and the escalation blob lives in a real temp-directory
//  `BlobStore`. Connectivity is pinned `.satisfied` and the clock is fixed, so a
//  `runSyncCycle()` call drives one full cycle deterministically without real
//  delays or a real `NWPathMonitor`.
//
//  The `StubBackend` is a programmable `HTTPTransport`: it records every request
//  it receives (so the test can assert push *ordering* and multipart photo
//  integrity) and answers each with a scripted `HTTPResponse` — a success
//  acknowledgment by default, or a scripted first-attempt `503` followed by a
//  success to exercise the retryable-then-recover path.
//
//  Cases:
//    * End-to-end push of a well test + a wellhead log: assert the stub received
//      both pushes in oldest-first (`createdAt` ascending) order, each returned
//      an acknowledgment, and both records transitioned to `synchronized` with
//      the backend's `remoteId` persisted (Req 2.1, 2.3).
//    * End-to-end escalation with a photo blob: assert the multipart upload hit
//      `/v1/escalations`, carried the exact photo bytes, and the escalation
//      became `synchronized` (Req 7.6).
//    * Retryable `5xx` then success: a record whose first push returns `503`
//      stays `pending` (with an incremented retry count) after the first cycle,
//      then transitions to `synchronized` on the next cycle once the stub
//      returns a success (Req 2.3, backoff keyed on `lastAttemptAt`).

import XCTest
import GRDB
@testable import SAGDWellTest

final class EndToEndSyncIntegrationTests: XCTestCase {

    // MARK: - Per-test temp directory / store lifetime

    private var tempDirectory: URL!
    private var appDatabase: AppDatabase!
    private var blobStore: BlobStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        // A fresh temp directory per test holds both the on-disk SQLite file and
        // the blob store, so each test is fully isolated and leaves nothing
        // behind after `tearDown`.
        tempDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("SAGDE2E-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)

        // A real on-disk GRDB database (foreign keys on), migrated to the current
        // schema — exactly what `AppDatabase.open` produces, but rooted in the
        // test's temp directory rather than Application Support.
        let dbURL = tempDirectory.appendingPathComponent("sagd-welltest.sqlite", isDirectory: false)
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        let dbQueue = try DatabaseQueue(path: dbURL.path, configuration: configuration)
        appDatabase = try AppDatabase(dbQueue)

        // A real file-based blob store in the same temp directory.
        blobStore = try BlobStore(directory: tempDirectory.appendingPathComponent("Photos", isDirectory: true))
    }

    override func tearDownWithError() throws {
        appDatabase = nil
        blobStore = nil
        if let tempDirectory {
            try? FileManager.default.removeItem(at: tempDirectory)
        }
        tempDirectory = nil
        try super.tearDownWithError()
    }

    // MARK: - Req 2.1 / 2.3 — push ordering + synchronized transition

    /// Save a well test and a wellhead log through the real repositories, run one
    /// sync cycle, and assert:
    ///   * the stub backend received exactly the two pushes, in oldest-first
    ///     (`createdAt` ascending) order (Req 2.1),
    ///   * each push was acknowledged and the records transitioned to
    ///     `synchronized` with the backend's `remoteId` persisted (Req 2.3).
    func testSavesPushOldestFirstAndBecomeSynchronized() async throws {
        try await seedWell(id: "W-E2E-1", type: .production)

        // Fixed clock so `createdAt` is assigned deterministically; the earlier
        // save (the well test) gets the earlier timestamp, so oldest-first
        // ordering is observable.
        let clock = StepSyncClock(start: Date(timeIntervalSince1970: 1_700_000_000), step: 10)

        let logRepository = GRDBLogRepository(
            appDatabase: appDatabase,
            storageProbe: AmpleStorageProbe(),
            syncSignal: NoopSyncWorkSignal(),
            now: clock.nowAndAdvance
        )

        let wellTest = WellTest(
            wellId: "W-E2E-1",
            oilRatio: 0.5,
            waterRatio: 0.3,
            gasRatio: 0.2,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let log = WellheadLog(
            wellId: "W-E2E-1",
            logType: .production,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_050),
            emulsionFlowRate: 12.5,
            casingGasPressure: 220,
            espMotorTemperature: 85,
            waterCutPercent: 40
        )

        // Save the well test first (older `createdAt`), then the log (newer),
        // through the real transactional save path.
        XCTAssertEqual(await logRepository.saveWellTest(wellTest), .success)
        XCTAssertEqual(await logRepository.saveWellheadLog(log), .success)

        let backend = StubBackend()
        let engine = makeEngine(backend: backend, clockNow: Date(timeIntervalSince1970: 1_700_001_000))

        await engine.runSyncCycle()

        // The stub must have received exactly the two pushes, oldest-first: the
        // well test (saved first, earlier `createdAt`) before the wellhead log.
        let received = await backend.receivedPushes()
        XCTAssertEqual(received.count, 2, "exactly two records should have been pushed")
        XCTAssertEqual(received.map(\.kind), [.wellTest, .wellheadLog],
                       "records must push oldest-first by createdAt (Req 2.1)")
        XCTAssertEqual(received.map(\.clientId), [wellTest.id.uuidString, log.id.uuidString])

        // Both records must now be `synchronized` with the backend's remote id.
        let testSync = try loadSyncMetadata(id: wellTest.id, kind: .wellTest)
        XCTAssertEqual(testSync?.syncState, .synchronized)
        XCTAssertEqual(testSync?.remoteId, StubBackend.remoteId(for: wellTest.id.uuidString))

        let logSync = try loadSyncMetadata(id: log.id, kind: .wellheadLog)
        XCTAssertEqual(logSync?.syncState, .synchronized)
        XCTAssertEqual(logSync?.remoteId, StubBackend.remoteId(for: log.id.uuidString))
    }

    // MARK: - Req 7.6 — escalation multipart upload with photo

    /// Save an escalation (blob first, then row) through the real escalation
    /// repository, run a sync cycle, and assert the multipart upload hit the
    /// escalations endpoint carrying the exact photo bytes, and the escalation
    /// became `synchronized` (Req 7.6).
    func testEscalationUploadsPhotoAndBecomesSynchronized() async throws {
        try await seedWell(id: "W-E2E-2", type: .production)

        let clock = StepSyncClock(start: Date(timeIntervalSince1970: 1_700_000_000), step: 10)
        let escalationRepository = GRDBEscalationRepository(
            appDatabase: appDatabase,
            blobStore: blobStore,
            storageProbe: AmpleStorageProbe(),
            syncSignal: NoopSyncWorkSignal(),
            now: clock.nowAndAdvance
        )

        let photoBytes = Data([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11, 0x22, 0x33, 0xFF])
        let escalation = Escalation(
            wellId: "W-E2E-2",
            priority: .high,
            photoRef: "",  // assigned by the repository when the blob is written
            latitude: 56.7,
            longitude: -111.4,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )

        XCTAssertEqual(
            await escalationRepository.saveEscalation(escalation, photoData: photoBytes),
            .success
        )

        let backend = StubBackend()
        let engine = makeEngine(backend: backend, clockNow: Date(timeIntervalSince1970: 1_700_001_000))

        await engine.runSyncCycle()

        // Exactly one escalation push, to the escalations endpoint, as multipart.
        let received = await backend.receivedPushes()
        XCTAssertEqual(received.count, 1)
        let push = try XCTUnwrap(received.first)
        XCTAssertEqual(push.kind, .escalation)
        XCTAssertEqual(push.path, "/v1/escalations")
        let contentType = try XCTUnwrap(push.contentType)
        XCTAssertTrue(contentType.hasPrefix("multipart/form-data; boundary="),
                      "escalation push must be multipart (Req 7.6)")

        // The multipart body must embed the exact photo bytes that were stored.
        let body = try XCTUnwrap(push.body)
        XCTAssertNotNil(body.range(of: photoBytes),
                        "the multipart body must carry the exact photo bytes (Req 7.6)")

        // The escalation must now be synchronized with the backend's remote id.
        let escSync = try loadSyncMetadata(id: escalation.id, kind: .escalation)
        XCTAssertEqual(escSync?.syncState, .synchronized)
        XCTAssertEqual(escSync?.remoteId, StubBackend.remoteId(for: escalation.id.uuidString))
    }

    // MARK: - Req 2.3 — retryable 5xx then success on the next cycle

    /// A record whose first push returns a retryable `503` must stay `pending`
    /// (retry count incremented) after the first cycle, then transition to
    /// `synchronized` on the next cycle once the backend returns a success. This
    /// exercises the real classification (`5xx` -> retryable), the real
    /// `recordFailure` transition, and the backoff window keyed on
    /// `lastAttemptAt` (advanced by the clock between cycles so the record is
    /// eligible again).
    func testRetryable5xxStaysPendingThenSynchronizesNextCycle() async throws {
        try await seedWell(id: "W-E2E-3", type: .production)

        let clock = StepSyncClock(start: Date(timeIntervalSince1970: 1_700_000_000), step: 10)
        let logRepository = GRDBLogRepository(
            appDatabase: appDatabase,
            storageProbe: AmpleStorageProbe(),
            syncSignal: NoopSyncWorkSignal(),
            now: clock.nowAndAdvance
        )

        let wellTest = WellTest(
            wellId: "W-E2E-3",
            oilRatio: 0.4,
            waterRatio: 0.4,
            gasRatio: 0.2,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        XCTAssertEqual(await logRepository.saveWellTest(wellTest), .success)

        // Script the backend: the first push of this record fails with 503, the
        // second (next cycle) succeeds.
        let backend = StubBackend()
        await backend.scriptStatuses([503], thenAcknowledgeFor: wellTest.id.uuidString)

        // The engine shares one controllable clock across both cycles so the
        // repository stamps `lastAttemptAt` and the backoff policy can clear it.
        let engineClock = AdvanceableSyncClock(now: Date(timeIntervalSince1970: 1_700_001_000))
        let repositoryForEngine = GRDBSyncRepository(appDatabase: appDatabase, now: engineClock.now)
        let engine = DefaultSyncEngine(
            repository: repositoryForEngine,
            pushClient: backendClient(backend),
            blobUploadClient: backendClient(backend),
            escalationBlobSource: GRDBEscalationBlobSource(appDatabase: appDatabase, blobStore: blobStore),
            backoff: ExponentialBackoffPolicy(),
            clock: engineClock,
            connectivity: FixedConnectivityProvider(status: .satisfied),
            offlineIndicator: OfflineIndicator(),
            backgroundScheduler: NoopBackgroundSyncScheduler()
        )

        // Cycle 1: the push fails with 503. The record stays `pending`, its
        // retry count is now 1, and `lastAttemptAt` is stamped.
        await engine.runSyncCycle()

        let afterFirst = try loadSyncMetadata(id: wellTest.id, kind: .wellTest)
        XCTAssertEqual(afterFirst?.syncState, .pending,
                       "a retryable 5xx must keep the record pending (Req 2.4)")
        XCTAssertEqual(afterFirst?.retryCount, 1,
                       "a retryable 5xx must increment the retry count")
        XCTAssertNotNil(afterFirst?.lastAttemptAt,
                        "a failed attempt must stamp lastAttemptAt for backoff")

        // Advance the clock past the first backoff window (base 1 s after the
        // first failure) so the record is eligible for the next cycle.
        engineClock.advance(by: 5)

        // Cycle 2: the scripted 503 is spent, so the backend now acknowledges.
        await engine.runSyncCycle()

        let afterSecond = try loadSyncMetadata(id: wellTest.id, kind: .wellTest)
        XCTAssertEqual(afterSecond?.syncState, .synchronized,
                       "the record must synchronize once the next cycle's push is acknowledged (Req 2.3)")
        XCTAssertEqual(afterSecond?.remoteId, StubBackend.remoteId(for: wellTest.id.uuidString))

        // The stub saw two pushes of the same record: the failed one and the
        // successful retry.
        let received = await backend.receivedPushes()
        XCTAssertEqual(received.count, 2)
        XCTAssertEqual(Set(received.map(\.clientId)), [wellTest.id.uuidString])
    }

    // MARK: - Engine / client assembly

    /// Build the real `CloudBackendClient` over the stub backend transport, with
    /// the real GRDB payload source and pulled-data cache.
    private func backendClient(_ backend: StubBackend) -> CloudBackendClient {
        CloudBackendClient(
            configuration: CloudBackendConfiguration(
                baseURL: URL(string: "https://api.example.test")!,
                tokenProvider: StaticAuthTokenProvider(token: "test-token")
            ),
            transport: backend,
            payloadSource: GRDBRecordPayloadSource(appDatabase: appDatabase),
            cache: GRDBPulledDataCache(appDatabase: appDatabase)
        )
    }

    /// Build the real `DefaultSyncEngine` wired to the real sync repository and
    /// the real REST client (filling both push seams), with connectivity pinned
    /// satisfied and a fixed clock so one `runSyncCycle()` drives a full cycle.
    private func makeEngine(backend: StubBackend, clockNow: Date) -> DefaultSyncEngine {
        let client = backendClient(backend)
        return DefaultSyncEngine(
            repository: GRDBSyncRepository(appDatabase: appDatabase, now: { clockNow }),
            pushClient: client,
            blobUploadClient: client,
            escalationBlobSource: GRDBEscalationBlobSource(appDatabase: appDatabase, blobStore: blobStore),
            backoff: ExponentialBackoffPolicy(),
            clock: FixedSyncClock(now: clockNow),
            connectivity: FixedConnectivityProvider(status: .satisfied),
            offlineIndicator: OfflineIndicator(),
            backgroundScheduler: NoopBackgroundSyncScheduler()
        )
    }

    // MARK: - Seeding / read-back helpers

    /// Insert a parent well so foreign-key-constrained child records can be saved.
    private func seedWell(id: String, type: WellType) async throws {
        try appDatabase.dbWriter.write { db in
            try WellRecord(
                Well(id: id, name: "Well \(id)", type: type, padId: nil,
                     updatedAt: Date(timeIntervalSince1970: 1_600_000_000))
            ).insert(db)
        }
    }

    /// Load the persisted `SyncMetadata` for a record by scanning the table its
    /// `kind` maps to.
    private func loadSyncMetadata(id: UUID, kind: SyncableRecordKind) throws -> SyncMetadata? {
        try appDatabase.dbWriter.read { db -> SyncMetadata? in
            switch kind {
            case .wellTest:
                return try WellTestRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            case .wellheadLog:
                return try WellheadLogRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            case .subcoolReading:
                return try SubcoolReadingRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            case .escalation:
                return try EscalationRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            }
        }
    }
}

// MARK: - Programmable stub Cloud_Backend

/// A programmable stub Cloud_Backend implemented as an `HTTPTransport`. It is
/// the single faked seam in this end-to-end test: it records every request it
/// receives (so the test can assert push ordering and multipart photo
/// integrity) and answers each with a scripted response.
///
/// By default every push is acknowledged with a deterministic `remoteId` derived
/// from the request's client `id`. A test can pre-script a sequence of status
/// codes for a specific client id (via `scriptStatuses(_:thenAcknowledgeFor:)`)
/// to exercise the retryable-5xx-then-success path; once the scripted statuses
/// are exhausted the backend acknowledges.
///
/// Actor-isolated so the engine's actor and the test can touch it concurrently.
private actor StubBackend: HTTPTransport {

    /// One recorded push the backend received, decoded enough for the test's
    /// assertions (which kind, which client id, and the multipart body/photo).
    struct ReceivedPush: Sendable {
        var kind: SyncableRecordKind
        var path: String
        var clientId: String
        var contentType: String?
        var body: Data?
    }

    private var received: [ReceivedPush] = []
    /// Scripted status codes to return before acknowledging, keyed by client id.
    private var scriptedStatuses: [String: [Int]] = [:]

    /// The deterministic remote id the backend assigns to a given client id.
    nonisolated static func remoteId(for clientId: String) -> String {
        "remote-\(clientId)"
    }

    /// Pre-script `statuses` to be returned (in order) for the next pushes of
    /// `clientId`; once they are exhausted, the backend acknowledges. Used to
    /// drive the retryable-then-success case (e.g. `[503]` fails once, then the
    /// next push succeeds).
    func scriptStatuses(_ statuses: [Int], thenAcknowledgeFor clientId: String) {
        scriptedStatuses[clientId] = statuses
    }

    /// The pushes the backend received, in the order it received them.
    func receivedPushes() -> [ReceivedPush] { received }

    // MARK: HTTPTransport

    func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        // Only push endpoints (POST) are exercised here; a GET would be a pull,
        // which this test does not drive.
        guard request.method == .post else {
            return HTTPResponse(statusCode: 200, body: Data())
        }

        let path = request.url.path
        let kind = Self.kind(forPath: path)
        let clientId = Self.clientId(for: kind, request: request)

        received.append(
            ReceivedPush(
                kind: kind,
                path: path,
                clientId: clientId,
                contentType: request.headers["Content-Type"],
                body: request.body
            )
        )

        // Honor any scripted non-2xx status first (consuming it), otherwise
        // acknowledge with a deterministic remote id for this client id.
        if var scripted = scriptedStatuses[clientId], !scripted.isEmpty {
            let status = scripted.removeFirst()
            scriptedStatuses[clientId] = scripted
            if !(200...299).contains(status) {
                return HTTPResponse(statusCode: status, body: Data())
            }
        }

        let ack = AcknowledgmentDTO(
            id: clientId,
            remoteId: Self.remoteId(for: clientId),
            status: "accepted"
        )
        let body = try CloudBackendCoding.makeEncoder().encode(ack)
        return HTTPResponse(statusCode: 201, body: body)
    }

    // MARK: Request parsing

    /// Map a push endpoint path to the record kind it carries.
    private static func kind(forPath path: String) -> SyncableRecordKind {
        if path.hasSuffix("/well-tests") { return .wellTest }
        if path.hasSuffix("/wellhead-logs") { return .wellheadLog }
        if path.hasSuffix("/subcool-readings") { return .subcoolReading }
        if path.hasSuffix("/escalations") { return .escalation }
        // Default to well test; the ordering assertions key off the recorded
        // `kind`, and unknown paths are not produced by the client's push path.
        return .wellTest
    }

    /// Extract the client `id` the push carries. JSON pushes put it at the
    /// top-level `id` key; the escalation multipart body embeds a JSON
    /// `metadata` part whose `id` key is the client id. Falls back to an empty
    /// string (never expected) so parsing never throws here.
    private static func clientId(for kind: SyncableRecordKind, request: HTTPRequest) -> String {
        guard let body = request.body else { return "" }
        switch kind {
        case .escalation:
            return multipartMetadataId(in: body) ?? ""
        default:
            if let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
               let id = json["id"] as? String {
                return id
            }
            return ""
        }
    }

    /// Pull the `id` field out of the JSON `metadata` part of a multipart body.
    /// The part is delimited by the standard multipart markers the client writes
    /// (`CloudBackendClient.multipartBody`): a `name="metadata"` disposition, a
    /// blank line, then the JSON up to the next boundary.
    private static func multipartMetadataId(in body: Data) -> String? {
        let text = String(decoding: body, as: UTF8.self)
        guard let dispositionRange = text.range(of: "name=\"metadata\"") else { return nil }
        // JSON starts after the blank line that follows the part headers.
        let afterHeaders = text[dispositionRange.upperBound...]
        guard let blankLine = afterHeaders.range(of: "\r\n\r\n") else { return nil }
        let afterBlank = afterHeaders[blankLine.upperBound...]
        // The JSON part ends at the CRLF preceding the next boundary marker.
        guard let boundary = afterBlank.range(of: "\r\n--") else { return nil }
        let jsonSlice = afterBlank[..<boundary.lowerBound]
        guard let data = String(jsonSlice).data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["id"] as? String
        else { return nil }
        return id
    }
}

// MARK: - Test doubles (clocks, connectivity, storage)

/// A `StorageProbe` that always reports ample free space, so the real
/// repository save path is never rejected with `storageFull` on a CI volume
/// that happens to be low. The *save transaction* is still the real one.
private struct AmpleStorageProbe: StorageProbe {
    func availableCapacityBytes() -> Int64? { .max }
}

/// A fixed-status connectivity provider pinned `.satisfied` so `runSyncCycle`
/// actually runs (a cycle no-ops while offline). Mirrors the seam used by the
/// sync property/integration tests.
private struct FixedConnectivityProvider: ConnectivityProvider {
    let status: ConnectivityStatus
    var currentStatus: ConnectivityStatus { status }
    func statusStream() -> AsyncStream<ConnectivityStatus> {
        AsyncStream { continuation in
            continuation.yield(status)
            continuation.finish()
        }
    }
}

/// A clock pinned at a fixed instant; `sleep` returns immediately.
private struct FixedSyncClock: SyncClock {
    let instant: Date
    init(now: Date) { self.instant = now }
    func now() -> Date { instant }
    func sleep(seconds: Double) async {}
}

/// A controllable clock whose `now()` advances only on command, so the
/// retryable-then-success case can step past a record's backoff window between
/// cycles. Thread-safe for use from the engine's actor and the test.
private final class AdvanceableSyncClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(now: Date) { self.current = now }

    func now() -> Date {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    func advance(by seconds: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        current = current.addingTimeInterval(seconds)
    }

    func sleep(seconds: Double) async {}
}

/// A deterministic "now" source for the repository save path that returns a
/// start instant and advances by a fixed step on each call, so sequential saves
/// receive strictly increasing `createdAt` values (making oldest-first push
/// ordering observable). Thread-safe.
private final class StepSyncClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date
    private let step: TimeInterval

    init(start: Date, step: TimeInterval) {
        self.current = start
        self.step = step
    }

    /// Returns the current instant, then advances by `step` for the next call.
    /// Shaped as a `@Sendable () -> Date` for the repository's `now` seam.
    var nowAndAdvance: @Sendable () -> Date {
        { [self] in
            lock.lock(); defer { lock.unlock() }
            let value = current
            current = current.addingTimeInterval(step)
            return value
        }
    }
}
