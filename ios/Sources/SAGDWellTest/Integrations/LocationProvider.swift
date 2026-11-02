//
//  Location provider
//
//  Supplies the GPS fix that geotags an escalation. Per the design's
//  "Geotagged Photo Escalation Flow": a location request is bounded at 30
//  seconds; when a fix arrives in time the escalation is tagged with
//  decimal-degree latitude/longitude and a UTC one-second-precision capture
//  timestamp (Req 7.1); when the fix times out the escalation records
//  `locationUnavailable` and the operator may proceed (Req 7.5).
//
//  The provider lives behind a protocol seam so the escalation-capture
//  coordinator — and its tests — never touch CoreLocation directly. Production
//  uses a `CLLocationManager`-backed provider (guarded by
//  `#if canImport(CoreLocation)`); tests inject a fake that yields a fix or a
//  timeout on command against a controllable clock.
//
//  Implemented by task 18.2.
//
//  Requirements: 7.1, 7.5.

import Foundation

// MARK: - Location fix

/// A single location fix: decimal-degree latitude/longitude and the UTC
/// instant (truncated to the second) at which it was captured.
///
/// The timestamp is normalized to whole-second UTC so it matches the
/// `capturedAt` precision the escalation record stores (Req 7.1).
public struct LocationFix: Sendable, Equatable, Hashable {
    /// Latitude in decimal degrees.
    public var latitude: Double
    /// Longitude in decimal degrees.
    public var longitude: Double
    /// Capture instant (UTC, one-second precision).
    public var capturedAt: Date

    public init(latitude: Double, longitude: Double, capturedAt: Date) {
        self.latitude = latitude
        self.longitude = longitude
        self.capturedAt = capturedAt
    }
}

// MARK: - Location request outcome

/// The outcome of a single bounded location request.
///
/// The request resolves to exactly one of these within the 30-second bound:
/// `fix` when a reading arrived in time, `timedOut` when the bound elapsed
/// first. A timeout is a normal, non-error outcome — the escalation proceeds
/// with `locationUnavailable` set (Req 7.5).
public enum LocationResult: Sendable, Equatable {
    /// A fix arrived within the bound.
    case fix(LocationFix)
    /// No fix arrived within the 30-second bound; record `locationUnavailable`.
    case timedOut
}

// MARK: - Location provider seam

/// Supplies a single bounded location fix for an escalation.
///
/// `requestFix` starts a location request and resolves within the 30-second
/// bound to either a `fix` or `timedOut`. Implementations must honor the bound
/// regardless of whether the underlying platform ever delivers a reading, so
/// the caller never blocks the operator for longer than the bound.
public protocol LocationProviding: Sendable {
    /// Request a single location fix, bounded at 30 seconds. Resolves to
    /// `.fix` when a reading arrives in time, otherwise `.timedOut`.
    func requestFix() async -> LocationResult
}

// MARK: - Shared constant

/// The maximum time to wait for a GPS fix before recording the location as
/// unavailable (Req 7.5). Shared so the production provider and the fake agree
/// on the bound.
public let locationFixTimeoutSeconds: Double = 30
