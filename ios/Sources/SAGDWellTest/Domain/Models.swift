//
//  Domain model types
//
//  Shared, platform-agnostic entity definitions for the SAGD WellTest domain.
//  These mirror the design document's Data Models section exactly: every
//  syncable record embeds `SyncMetadata`, and discriminated records (wellhead
//  logs) carry a `logType` discriminator with mutually exclusive, nullable
//  per-type fields.
//
//  These are plain value types with no I/O and no dependency on the
//  persistence engine, networking, or UI. They are introduced by task 1.2.

import Foundation

// MARK: - Well

/// Discriminates a well as a producer or a steam injector. `WellType` drives
/// which wellhead-log form the UI presents (production vs injection).
public enum WellType: String, Codable, Sendable, CaseIterable, Hashable {
    case production
    case injection
}

/// Reference data for a physical well. Well records are primarily populated by
/// the cloud pull; the `id` is the value encoded in the well's QR code.
public struct Well: Codable, Sendable, Hashable, Identifiable {
    /// Unique well identifier; the value encoded in the QR code.
    public var id: String
    /// Display name.
    public var name: String
    /// Producer or injector; selects the log form the UI presents.
    public var type: WellType
    /// Pad / manifold grouping, if any.
    public var padId: String?
    /// Last update from the cloud pull (UTC).
    public var updatedAt: Date

    public init(
        id: String,
        name: String,
        type: WellType,
        padId: String? = nil,
        updatedAt: Date
    ) {
        self.id = id
        self.name = name
        self.type = type
        self.padId = padId
        self.updatedAt = updatedAt
    }
}

// MARK: - WellTest

/// A captured well test (oil / water / gas ratios). Locally generated and
/// syncable; displayed most-recent-first on QR lookup.
public struct WellTest: Codable, Sendable, Hashable, Identifiable {
    /// Locally generated primary key.
    public var id: UUID
    /// Associated well (FK -> Well.id).
    public var wellId: String
    public var oilRatio: Double
    public var waterRatio: Double
    public var gasRatio: Double
    /// Capture time (UTC, 1-second precision).
    public var capturedAt: Date
    /// Embedded sync-state machine metadata.
    public var sync: SyncMetadata

    public init(
        id: UUID = UUID(),
        wellId: String,
        oilRatio: Double,
        waterRatio: Double,
        gasRatio: Double,
        capturedAt: Date,
        sync: SyncMetadata = SyncMetadata()
    ) {
        self.id = id
        self.wellId = wellId
        self.oilRatio = oilRatio
        self.waterRatio = waterRatio
        self.gasRatio = gasRatio
        self.capturedAt = capturedAt
        self.sync = sync
    }
}

// MARK: - WellheadLog

/// Discriminates a wellhead log as a production or injection log. The
/// per-type fields on `WellheadLog` are mutually exclusive and selected by
/// this value.
public enum WellheadLogType: String, Codable, Sendable, CaseIterable, Hashable {
    case production
    case injection
}

/// A single wellhead log captured in the field, discriminated by `logType`.
/// Production and injection fields are mutually exclusive and nullable; the
/// field set that is present is determined by `logType`. Modeled as one record
/// type so the sync queue can treat all logs uniformly.
public struct WellheadLog: Codable, Sendable, Hashable, Identifiable {
    /// Locally generated primary key.
    public var id: UUID
    /// Associated well (FK -> Well.id).
    public var wellId: String
    /// Discriminator selecting which field group is populated.
    public var logType: WellheadLogType
    /// Capture time (UTC, 1-second precision).
    public var capturedAt: Date

    // Production fields — present when `logType == .production`.
    /// Emulsion flow rate (range 0.00–999,999.99).
    public var emulsionFlowRate: Double?
    /// Casing gas pressure (range 0.00–999,999.99).
    public var casingGasPressure: Double?
    /// ESP motor temperature (range 0.00–999,999.99).
    public var espMotorTemperature: Double?
    /// Water cut percentage (range 0–100).
    public var waterCutPercent: Double?

    // Injection fields — present when `logType == .injection`.
    /// Steam injection pressure in kPa (range 0–25,000).
    public var steamInjectionPressure: Double?
    /// Steam temperature in °C (range 0–400).
    public var steamTemperature: Double?
    /// Steam flow rate in m³/day (range 0–2,000).
    public var steamFlowRate: Double?

    /// Embedded sync-state machine metadata.
    public var sync: SyncMetadata

    public init(
        id: UUID = UUID(),
        wellId: String,
        logType: WellheadLogType,
        capturedAt: Date,
        emulsionFlowRate: Double? = nil,
        casingGasPressure: Double? = nil,
        espMotorTemperature: Double? = nil,
        waterCutPercent: Double? = nil,
        steamInjectionPressure: Double? = nil,
        steamTemperature: Double? = nil,
        steamFlowRate: Double? = nil,
        sync: SyncMetadata = SyncMetadata()
    ) {
        self.id = id
        self.wellId = wellId
        self.logType = logType
        self.capturedAt = capturedAt
        self.emulsionFlowRate = emulsionFlowRate
        self.casingGasPressure = casingGasPressure
        self.espMotorTemperature = espMotorTemperature
        self.waterCutPercent = waterCutPercent
        self.steamInjectionPressure = steamInjectionPressure
        self.steamTemperature = steamTemperature
        self.steamFlowRate = steamFlowRate
        self.sync = sync
    }
}

// MARK: - SubcoolReading

/// A subcool estimate captured against a casing pressure and pump temperature.
/// `subcoolValue == saturationTemperature - pumpTemperature`; `isFlashing` is
/// `subcoolValue <= flashingThreshold`. Sync metadata is present only when the
/// reading is persisted/synced.
public struct SubcoolReading: Codable, Sendable, Hashable, Identifiable {
    /// Locally generated primary key.
    public var id: UUID
    /// Optional associated well (FK -> Well.id).
    public var wellId: String?
    /// Input casing pressure.
    public var casingPressure: Double
    /// Input pump temperature.
    public var pumpTemperature: Double
    /// Saturation temperature derived from the steam table.
    public var saturationTemperature: Double
    /// `saturationTemperature - pumpTemperature`.
    public var subcoolValue: Double
    /// `subcoolValue <= flashingThreshold`.
    public var isFlashing: Bool
    /// Capture time (UTC, 1-second precision).
    public var capturedAt: Date
    /// Embedded sync metadata, present only if the reading is persisted/synced.
    public var sync: SyncMetadata?

    public init(
        id: UUID = UUID(),
        wellId: String? = nil,
        casingPressure: Double,
        pumpTemperature: Double,
        saturationTemperature: Double,
        subcoolValue: Double,
        isFlashing: Bool,
        capturedAt: Date,
        sync: SyncMetadata? = nil
    ) {
        self.id = id
        self.wellId = wellId
        self.casingPressure = casingPressure
        self.pumpTemperature = pumpTemperature
        self.saturationTemperature = saturationTemperature
        self.subcoolValue = subcoolValue
        self.isFlashing = isFlashing
        self.capturedAt = capturedAt
        self.sync = sync
    }
}

// MARK: - MaintenanceTask

/// The lifecycle status of a maintenance task. "Open" tasks are those with
/// `status == .open` and are surfaced on QR lookup.
public enum MaintenanceTaskStatus: String, Codable, Sendable, CaseIterable, Hashable {
    case open
    case closed
}

/// A maintenance task for a well. Primarily populated by the cloud pull and
/// displayed on QR lookup.
public struct MaintenanceTask: Codable, Sendable, Hashable, Identifiable {
    /// Primary key.
    public var id: UUID
    /// Associated well (FK -> Well.id).
    public var wellId: String
    public var description: String
    public var status: MaintenanceTaskStatus
    /// Creation time (UTC).
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        wellId: String,
        description: String,
        status: MaintenanceTaskStatus,
        createdAt: Date
    ) {
        self.id = id
        self.wellId = wellId
        self.description = description
        self.status = status
        self.createdAt = createdAt
    }
}

// MARK: - Escalation

/// Priority of an escalation. Required when saving an escalation.
public enum EscalationPriority: String, Codable, Sendable, CaseIterable, Hashable {
    case high
    case medium
    case low
}

/// A geotagged photo escalation raised in the field. Requires a well and a
/// priority. Latitude/longitude are decimal degrees when a GPS fix is
/// obtained; `locationUnavailable` is set when the fix times out.
public struct Escalation: Codable, Sendable, Hashable, Identifiable {
    /// Locally generated primary key.
    public var id: UUID
    /// Associated well (FK -> Well.id); required.
    public var wellId: String
    /// Escalation priority; required.
    public var priority: EscalationPriority
    /// Path/key to the photo blob in the local file store.
    public var photoRef: String
    /// Latitude in decimal degrees, when available.
    public var latitude: Double?
    /// Longitude in decimal degrees, when available.
    public var longitude: Double?
    /// True when the GPS fix timed out and no coordinates were captured.
    public var locationUnavailable: Bool
    /// Capture time (UTC, 1-second precision).
    public var capturedAt: Date
    /// Embedded sync metadata; includes blob upload state.
    public var sync: SyncMetadata

    public init(
        id: UUID = UUID(),
        wellId: String,
        priority: EscalationPriority,
        photoRef: String,
        latitude: Double? = nil,
        longitude: Double? = nil,
        locationUnavailable: Bool = false,
        capturedAt: Date,
        sync: SyncMetadata = SyncMetadata()
    ) {
        self.id = id
        self.wellId = wellId
        self.priority = priority
        self.photoRef = photoRef
        self.latitude = latitude
        self.longitude = longitude
        self.locationUnavailable = locationUnavailable
        self.capturedAt = capturedAt
        self.sync = sync
    }
}
