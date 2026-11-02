//
//  EscalationPhotoIncludedInPushPropertyTests.swift
//
//  Property 24: Escalation photo included in push (iOS) — task 22.3.
//
//  Validates: Requirements 7.6
//
//  Requirement 7.6 says that WHILE the device has network connectivity and
//  unsynchronized Escalations exist, THE Sync_Service SHALL push each
//  unsynchronized Escalation and its attached photo to the Cloud_Backend.
//
//  The "photo" half of that obligation is the risky one: the bytes the backend
//  receives must be exactly the bytes the operator captured — not truncated,
//  not re-encoded, not corrupted by the multipart framing, and not confused
//  with the metadata part. This property pins that down.
//
//  For an arbitrary photo blob (including the empty blob and large blobs), the
//  test:
//
//    1. writes the blob through the real `BlobStore` (temp directory), exactly
//       as the escalation capture path does, and keeps the original bytes;
//    2. runs the escalation through the real `CloudBackendClient.upload(
//       escalation:blobURL:)` over a recording `HTTPTransport` stub (no
//       network), so the body it builds is captured verbatim;
//    3. parses the captured `multipart/form-data` body, splitting on the
//       boundary from the request's `Content-Type`, and extracts the bytes of
//       the part named `photo`; and
//    4. asserts those extracted bytes equal the original blob bytes exactly
//       (count and every byte), and that the metadata part is present and
//       decodes to the escalation's id/well/priority.
//
//  Because the body is also used to carry a JSON metadata part, a naive
//  parser could mistake metadata bytes for photo bytes or mis-handle a blob
//  that itself contains CRLF/boundary-looking byte sequences; the generator
//  deliberately includes blobs with embedded CRLFs and boundary-like runs so
//  the extraction is forced to rely on the declared part structure rather than
//  on scanning for delimiters inside the payload.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged through
//  `propertyName(_:_:)`.
//
//  NOTE: authored on a Windows host with no Swift/Xcode toolchain available, so
//  this test is written-and-reviewed but has not been compiled or executed
//  here. It follows the established iOS PBT conventions
//  (`EscalationGeotagAttachmentPropertyTests`, `CloudBackendClientTests`) and
//  the `SwiftCheck` bridge pattern used across the suite.

import XCTest
import Foundation
import SwiftCheck
@testable import SAGDWellTest

final class EscalationPhotoIncludedInPushPropertyTests: XCTestCase {

    // MARK: - Property 24

    func testEscalationPhotoIncludedInPush() {
        property(
            propertyName(24, "Escalation photo included in push"),
            arguments: pbtArguments()
        ) <- forAll(Self.pushCaseGenerator()) { (testCase: PushCase) in
            guard let outcome = Self.run(testCase) else {
                return false <?> "push harness failed to build or capture a request"
            }

            // The upload must be acknowledged — the escalation is only
            // considered synced (photo included) once the backend accepts it.
            let acknowledged = (outcome.result == .acknowledged(remoteId: outcome.expectedRemoteId))
                <?> "expected .acknowledged(\(outcome.expectedRemoteId)), got \(outcome.result)"

            // The request must be the multipart escalation upload.
            let isMultipart = outcome.contentType.hasPrefix("multipart/form-data; boundary=")
                <?> "Content-Type is not multipart/form-data: \(outcome.contentType)"

            // The parsed `photo` part must carry the original blob bytes
            // exactly — same length and every byte (Req 7.6).
            guard let photoPart = outcome.photoPart else {
                return acknowledged ^&&^ isMultipart
                    ^&&^ (false <?> "no `photo` part found in the multipart body")
            }
            let sameLength = (photoPart.count == testCase.photoBytes.count)
                <?> "photo part length \(photoPart.count) != original \(testCase.photoBytes.count)"
            let sameBytes = (photoPart == testCase.photoBytes)
                <?> "photo part bytes differ from the original blob bytes"

            // The metadata part must also be present and identify this
            // escalation, so the photo is never sent detached from its record.
            guard let metadata = outcome.metadataPart else {
                return acknowledged ^&&^ isMultipart ^&&^ sameLength ^&&^ sameBytes
                    ^&&^ (false <?> "no `metadata` part found in the multipart body")
            }
            let metadataIdentifiesEscalation =
                (metadata.id == testCase.escalation.id.uuidString
                    && metadata.wellId == testCase.escalation.wellId
                    && metadata.priority == testCase.escalation.priority.rawValue)
                <?> "metadata part does not identify the escalation: \(metadata)"

            return acknowledged ^&&^ isMultipart ^&&^ sameLength ^&&^ sameBytes
                ^&&^ metadataIdentifiesEscalation
        }
    }

    // MARK: - Harness

    /// One push run's observable outcome: the client result, the request's
    /// multipart `Content-Type`, and the bytes/metadata extracted from the
    /// parsed body.
    private struct PushOutcome {
        var result: SyncPushResult
        var contentType: String
        var photoPart: Data?
        var metadataPart: EscalationMetadataDTO?
        var expectedRemoteId: String
    }

    /// Writes the case's photo bytes through a real `BlobStore`, uploads the
    /// escalation through the real `CloudBackendClient` over a recording
    /// transport stub, then parses the captured multipart body. Returns `nil`
    /// if blob setup, the upload, or capturing the request fails.
    private static func run(_ testCase: PushCase) -> PushOutcome? {
        // 1. Store the photo bytes through the real blob store, exactly as the
        //    capture path does, and resolve its on-disk URL for the uploader.
        let blobStore: BlobStore
        let blobURL: URL
        let blobDirectory = makeTempBlobDirectory()
        do {
            blobStore = try BlobStore(directory: blobDirectory)
            let ref = try blobStore.write(testCase.photoBytes)
            blobURL = try blobStore.url(forPhotoRef: ref)
        } catch {
            return nil
        }
        defer { try? FileManager.default.removeItem(at: blobDirectory) }

        // 2. Upload through the real client over a recording transport stub.
        let remoteId = "esc-remote-\(testCase.escalation.id.uuidString)"
        let transport = RecordingTransport(
            response: .acknowledged(remoteId: remoteId, clientId: testCase.escalation.id.uuidString)
        )
        let client = CloudBackendClient(
            configuration: CloudBackendConfiguration(
                baseURL: URL(string: "https://api.example.test")!,
                tokenProvider: StaticAuthTokenProvider(token: "test-token")
            ),
            transport: transport,
            payloadSource: EmptyPayloadSource(),
            cache: NoopCache()
        )

        guard let result = bridge({ await client.upload(escalation: testCase.escalation, blobURL: blobURL) }),
              let request = transport.lastRequest,
              let contentType = request.headers["Content-Type"],
              let body = request.body
        else {
            return nil
        }

        // 3. Parse the multipart body using the declared boundary.
        guard let boundary = Self.boundary(fromContentType: contentType) else {
            return nil
        }
        let parts = MultipartParser.parse(body: body, boundary: boundary)
        let photo = parts.first(where: { $0.name == "photo" })?.body
        let metadata = parts
            .first(where: { $0.name == "metadata" })?
            .body
            .flatMap { try? CloudBackendCoding.makeDecoder().decode(EscalationMetadataDTO.self, from: $0) }

        return PushOutcome(
            result: result,
            contentType: contentType,
            photoPart: photo,
            metadataPart: metadata,
            expectedRemoteId: remoteId
        )
    }

    /// Extracts the boundary token from a `multipart/form-data; boundary=…`
    /// `Content-Type` header value.
    private static func boundary(fromContentType contentType: String) -> String? {
        guard let range = contentType.range(of: "boundary=") else { return nil }
        let token = contentType[range.upperBound...]
        // The boundary runs to the end of the header value (no other params are
        // appended), trimming any accidental surrounding quotes/whitespace.
        return token
            .trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
            .isEmpty ? nil : String(token.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")))
    }

    /// A throwaway blob directory under the system temp dir, unique per run so
    /// parallel cases never collide.
    private static func makeTempBlobDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("sagd-prop24-\(UUID().uuidString)", isDirectory: true)
    }

    // MARK: - Async bridge for SwiftCheck

    /// Runs an async body to completion from SwiftCheck's synchronous `forAll`.
    /// Returns `nil` if the body throws.
    private static func bridge<T>(_ body: @escaping () async -> T) -> T? {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            box.value = await body()
            semaphore.signal()
        }
        semaphore.wait()
        return box.value
    }

    private final class ResultBox<T> {
        var value: T?
    }

    // MARK: - Generated inputs

    /// One generated push case: an escalation to upload plus the exact photo
    /// bytes its blob holds.
    private struct PushCase {
        var escalation: Escalation
        var photoBytes: Data
    }

    /// Generates a valid escalation paired with an arbitrary photo blob. The
    /// blob length spans the empty blob and large blobs; the byte content
    /// deliberately includes runs that look like CRLFs and multipart
    /// delimiters so the parser cannot rely on scanning the payload for
    /// delimiters and must respect the declared part structure instead.
    private static func pushCaseGenerator() -> Gen<PushCase> {
        let wellIdGen = Gen<Int>.choose((1, 100_000)).map { "WELL-\($0)" }
        let priorityGen = Gen<EscalationPriority>.fromElements(of: EscalationPriority.allCases)
        // Whole-second UTC capture instant, matching the LocationFix contract.
        let capturedAtGen = Gen<Int>.choose((1_600_000_000, 1_800_000_000))
            .map { Date(timeIntervalSince1970: Double($0)) }
        let latitudeGen = Gen<Double>.choose((-90.0, 90.0))
        let longitudeGen = Gen<Double>.choose((-180.0, 180.0))

        return Gen<PushCase>.compose { c in
            let escalation = Escalation(
                id: UUID(),
                wellId: c.generate(using: wellIdGen),
                priority: c.generate(using: priorityGen),
                // The upload reads the blob by URL; `photoRef` is only used for
                // the part's filename, so any non-empty ref is fine.
                photoRef: "\(UUID().uuidString).blob",
                latitude: c.generate(using: latitudeGen),
                longitude: c.generate(using: longitudeGen),
                locationUnavailable: false,
                capturedAt: c.generate(using: capturedAtGen)
            )
            return PushCase(
                escalation: escalation,
                photoBytes: Self.photoBytes(c)
            )
        }
    }

    /// Generates a photo blob across the meaningful size spectrum — including
    /// the empty blob and large blobs — and, on some cases, seeds it with
    /// embedded CRLF and boundary-like byte runs so byte-identity is tested
    /// against adversarial payloads rather than only random noise.
    ///
    /// Built imperatively inside the composer (the established pattern in this
    /// target, e.g. `LocationUnavailableFallbackPropertyTests.photoData`) so it
    /// stays within the generator API the suite already relies on.
    private static func photoBytes(_ c: GenComposer) -> Data {
        // Shape selector: weight toward plain random blobs, but regularly
        // exercise the empty blob and the adversarial (framing-like) blob.
        //   0,1,2 -> random (incl. possibly empty)   3,4 -> adversarial   5 -> empty
        let shape: Int = c.generate(using: Gen<Int>.choose((0, 5)))

        if shape == 5 {
            return Data() // the empty blob, explicitly and often
        }

        // 0 lower bound keeps the empty blob reachable from the random shape too.
        let length: Int = c.generate(using: Gen<Int>.choose((0, 4_096)))
        let noise = Data((0..<length).map { _ in
            UInt8(c.generate(using: Gen<Int>.choose((0, 255))))
        })

        guard shape >= 3 else {
            return noise
        }

        // Adversarial blob: random noise wrapped in byte runs that mimic
        // multipart framing (CRLF, a dash-dash opener, a *different* boundary
        // token). A naive "scan for the next boundary" parser would split here;
        // a structural parser keyed on the real boundary must not.
        var data = Data()
        data.append(Data("\r\n--".utf8))
        data.append(noise)
        data.append(Data("\r\n--SAGDWellTest-fake\r\n".utf8))
        data.append(noise)
        return data
    }
}

// MARK: - Multipart parsing (test-side, structural)

/// A minimal structural parser for the `multipart/form-data` bodies the client
/// builds. It splits the body on the boundary delimiter and, for each part,
/// separates the headers from the body at the first blank line, reading the
/// `name` from the `Content-Disposition` header. It operates on raw `Data`
/// (never decoding the body as text) so binary photo bytes survive extraction
/// unchanged.
///
/// This mirrors — but does not reuse — the client's `multipartBody` assembly,
/// so the property checks the two agree on the framing rather than trivially
/// round-tripping one implementation.
private enum MultipartParser {

    struct Part {
        var name: String?
        var body: Data
    }

    static func parse(body: Data, boundary: String) -> [Part] {
        let crlf = Data("\r\n".utf8)
        let dashes = Data("--".utf8)
        let delimiter = dashes + Data(boundary.utf8) // "--<boundary>"

        // Split the body into segments separated by the delimiter. Each
        // interior segment is one part (framed by a leading and trailing CRLF);
        // the preamble (before the first delimiter) and the closing "--" epilogue
        // are discarded.
        let segments = split(body, on: delimiter)

        var parts: [Part] = []
        for segment in segments {
            // Skip the closing marker ("--" possibly followed by CRLF) and any
            // empty preamble/epilogue segments.
            if segment.isEmpty { continue }
            if segment.starts(with: Array(dashes)) { continue } // closing "--...": epilogue

            // A part segment is: CRLF <headers> CRLF CRLF <body> CRLF
            var part = segment
            // Strip a single leading CRLF that follows the delimiter.
            if part.starts(with: Array(crlf)) {
                part = part.subdata(in: crlf.count..<part.count)
            }
            // Strip a single trailing CRLF that precedes the next delimiter.
            if part.count >= crlf.count,
               part.suffix(crlf.count) == crlf {
                part = part.subdata(in: 0..<(part.count - crlf.count))
            }

            // Separate headers from body at the first blank line (CRLF CRLF).
            let headerSeparator = crlf + crlf
            guard let sepRange = part.range(of: headerSeparator) else {
                continue
            }
            let headerData = part.subdata(in: 0..<sepRange.lowerBound)
            let bodyData = part.subdata(in: sepRange.upperBound..<part.count)

            let name = fieldName(fromHeaders: headerData)
            parts.append(Part(name: name, body: bodyData))
        }
        return parts
    }

    /// Reads the `name="…"` value from a `Content-Disposition` header. Headers
    /// are ASCII, so decoding them as UTF-8 is safe (only the body is binary).
    private static func fieldName(fromHeaders headers: Data) -> String? {
        let text = String(decoding: headers, as: UTF8.self)
        for line in text.components(separatedBy: "\r\n") {
            guard line.lowercased().hasPrefix("content-disposition:") else { continue }
            guard let nameRange = line.range(of: "name=\"") else { continue }
            let rest = line[nameRange.upperBound...]
            guard let endQuote = rest.firstIndex(of: "\"") else { continue }
            return String(rest[..<endQuote])
        }
        return nil
    }

    /// Splits `data` on every (non-overlapping) occurrence of `separator`,
    /// returning the segments between occurrences. Operates on raw bytes so
    /// binary content is preserved.
    private static func split(_ data: Data, on separator: Data) -> [Data] {
        guard !separator.isEmpty else { return [data] }
        var segments: [Data] = []
        var searchStart = data.startIndex
        var segmentStart = data.startIndex

        while let match = data.range(of: separator, in: searchStart..<data.endIndex) {
            segments.append(data.subdata(in: segmentStart..<match.lowerBound))
            segmentStart = match.upperBound
            searchStart = match.upperBound
        }
        segments.append(data.subdata(in: segmentStart..<data.endIndex))
        return segments
    }
}

// MARK: - Test doubles

/// A recording `HTTPTransport` that returns a canned acknowledgment and
/// captures the last request it was given, so the property can inspect the
/// exact multipart body the client built. No network is involved.
private final class RecordingTransport: HTTPTransport, @unchecked Sendable {

    enum Canned {
        case acknowledged(remoteId: String, clientId: String)
    }

    private let canned: Canned
    private(set) var lastRequest: HTTPRequest?

    init(response: Canned) {
        self.canned = response
    }

    func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        lastRequest = request
        switch canned {
        case let .acknowledged(remoteId, clientId):
            let ack = AcknowledgmentDTO(id: clientId, remoteId: remoteId, status: "accepted")
            let body = try CloudBackendCoding.makeEncoder().encode(ack)
            return HTTPResponse(statusCode: 200, body: body)
        }
    }
}

/// A `RecordPayloadSource` with nothing seeded — escalations upload via the
/// blob URL, not through the payload source, so this is never consulted.
private struct EmptyPayloadSource: RecordPayloadSource {
    func wellTest(id: UUID) async -> WellTest? { nil }
    func wellheadLog(id: UUID) async -> WellheadLog? { nil }
    func subcoolReading(id: UUID) async -> SubcoolReading? { nil }
}

/// A `PulledDataCache` that ignores everything; the upload path never pulls.
private final class NoopCache: PulledDataCache, @unchecked Sendable {
    func cacheWell(_ well: Well) async {}
    func cacheWells(_ wells: [Well]) async {}
    func cacheWellTests(_ tests: [WellTest]) async {}
    func cacheMaintenanceTasks(_ tasks: [MaintenanceTask]) async {}
}
