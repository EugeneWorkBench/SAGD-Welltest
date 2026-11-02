//
//  Cloud backend wire DTOs
//
//  Explicit `Codable` payload types for the push/pull JSON, kept separate from
//  the domain models on purpose. The domain structs embed `SyncMetadata` (a
//  client-only concern — sync state, retry count, local timestamps) which must
//  never cross the wire; and the backend keys (`capturedAt` as an ISO-8601
//  string, `id` as a string) are a wire contract, not a domain representation.
//  Mapping through dedicated DTOs makes that boundary explicit and lets the
//  JSON shape evolve independently of the on-device model.
//
//  Dates are encoded/decoded as ISO-8601 with fractional seconds via
//  `CloudBackendCoding`, matching the design's "UTC, 1-second precision"
//  capture timestamps.
//
//  Introduced by task 22.1.

import Foundation

// MARK: - Acknowledgment

/// The success response every push endpoint returns:
/// `{ "id": "<clientId>", "remoteId": "<serverId>", "status": "accepted" }`
/// (design "Acknowledgment contract"). The client decodes this on a 2xx and
/// hands `remoteId` to the engine.
public struct AcknowledgmentDTO: Codable, Equatable, Sendable {
    public var id: String
    public var remoteId: String
    public var status: String

    public init(id: String, remoteId: String, status: String) {
        self.id = id
        self.remoteId = remoteId
        self.status = status
    }
}

// MARK: - Push bodies

/// JSON body for `POST /v1/well-tests`.
struct WellTestPushDTO: Codable, Equatable {
    var id: String
    var wellId: String
    var oilRatio: Double
    var waterRatio: Double
    var gasRatio: Double
    var capturedAt: Date

    init(_ test: WellTest) {
        self.id = test.id.uuidString
        self.wellId = test.wellId
        self.oilRatio = test.oilRatio
        self.waterRatio = test.waterRatio
        self.gasRatio = test.gasRatio
        self.capturedAt = test.capturedAt
    }
}

/// JSON body for `POST /v1/wellhead-logs`. Discriminated by `logType`; the
/// mutually-exclusive per-type fields are omitted from the JSON when nil.
struct WellheadLogPushDTO: Codable, Equatable {
    var id: String
    var wellId: String
    var logType: String
    var capturedAt: Date

    var emulsionFlowRate: Double?
    var casingGasPressure: Double?
    var espMotorTemperature: Double?
    var waterCutPercent: Double?

    var steamInjectionPressure: Double?
    var steamTemperature: Double?
    var steamFlowRate: Double?

    init(_ log: WellheadLog) {
        self.id = log.id.uuidString
        self.wellId = log.wellId
        self.logType = log.logType.rawValue
        self.capturedAt = log.capturedAt
        self.emulsionFlowRate = log.emulsionFlowRate
        self.casingGasPressure = log.casingGasPressure
        self.espMotorTemperature = log.espMotorTemperature
        self.waterCutPercent = log.waterCutPercent
        self.steamInjectionPressure = log.steamInjectionPressure
        self.steamTemperature = log.steamTemperature
        self.steamFlowRate = log.steamFlowRate
    }
}

/// JSON body for `POST /v1/subcool-readings`.
struct SubcoolReadingPushDTO: Codable, Equatable {
    var id: String
    var wellId: String?
    var casingPressure: Double
    var pumpTemperature: Double
    var saturationTemperature: Double
    var subcoolValue: Double
    var isFlashing: Bool
    var capturedAt: Date

    init(_ reading: SubcoolReading) {
        self.id = reading.id.uuidString
        self.wellId = reading.wellId
        self.casingPressure = reading.casingPressure
        self.pumpTemperature = reading.pumpTemperature
        self.saturationTemperature = reading.saturationTemperature
        self.subcoolValue = reading.subcoolValue
        self.isFlashing = reading.isFlashing
        self.capturedAt = reading.capturedAt
    }
}

/// JSON `metadata` part for the `POST /v1/escalations` multipart body. The
/// photo bytes travel as a separate binary part, so this carries only the
/// escalation's fields.
struct EscalationMetadataDTO: Codable, Equatable {
    var id: String
    var wellId: String
    var priority: String
    var latitude: Double?
    var longitude: Double?
    var locationUnavailable: Bool
    var capturedAt: Date

    init(_ escalation: Escalation) {
        self.id = escalation.id.uuidString
        self.wellId = escalation.wellId
        self.priority = escalation.priority.rawValue
        self.latitude = escalation.latitude
        self.longitude = escalation.longitude
        self.locationUnavailable = escalation.locationUnavailable
        self.capturedAt = escalation.capturedAt
    }
}

// MARK: - Pull bodies

/// JSON for `GET /v1/wells/{wellId}` and the elements of
/// `GET /v1/wells?updatedSince=…`.
struct WellPullDTO: Codable, Equatable {
    var id: String
    var name: String
    var type: String
    var padId: String?
    var updatedAt: Date

    /// The domain `Well`, or `nil` if the stored `type` is unrecognized.
    var domain: Well? {
        guard let type = WellType(rawValue: type) else { return nil }
        return Well(id: id, name: name, type: type, padId: padId, updatedAt: updatedAt)
    }
}

/// JSON element for `GET /v1/wells/{wellId}/well-tests?order=desc`.
struct WellTestPullDTO: Codable, Equatable {
    var id: String
    var wellId: String
    var oilRatio: Double
    var waterRatio: Double
    var gasRatio: Double
    var capturedAt: Date
    var remoteId: String?

    /// The domain `WellTest`. Pulled records are already accepted by the
    /// backend, so they land `synchronized` with their `remoteId` recorded.
    var domain: WellTest? {
        guard let uuid = UUID(uuidString: id) else { return nil }
        return WellTest(
            id: uuid,
            wellId: wellId,
            oilRatio: oilRatio,
            waterRatio: waterRatio,
            gasRatio: gasRatio,
            capturedAt: capturedAt,
            sync: SyncMetadata(
                syncState: .synchronized,
                createdAt: capturedAt,
                remoteId: remoteId ?? id
            )
        )
    }
}

/// JSON element for `GET /v1/wells/{wellId}/maintenance-tasks?status=open`.
struct MaintenanceTaskPullDTO: Codable, Equatable {
    var id: String
    var wellId: String
    var description: String
    var status: String
    var createdAt: Date

    /// The domain `MaintenanceTask`, or `nil` if id/status cannot be parsed.
    var domain: MaintenanceTask? {
        guard let uuid = UUID(uuidString: id),
              let status = MaintenanceTaskStatus(rawValue: status)
        else { return nil }
        return MaintenanceTask(
            id: uuid,
            wellId: wellId,
            description: description,
            status: status,
            createdAt: createdAt
        )
    }
}

// MARK: - Coding

/// Shared JSON encoder/decoder configuration for all backend traffic. ISO-8601
/// with fractional seconds keeps `capturedAt` round-tripping at sub-second
/// precision; keys are used verbatim (the DTO property names are the wire
/// keys).
enum CloudBackendCoding {

    /// ISO-8601 formatter with fractional seconds, pinned to UTC, matching the
    /// design's UTC capture timestamps.
    static let dateFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()

    static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(dateFormatter.string(from: date))
        }
        return encoder
    }

    static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            guard let date = dateFormatter.date(from: string) else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription: "Invalid ISO-8601 date: \(string)"
                )
            }
            return date
        }
        return decoder
    }
}
