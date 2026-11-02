//
//  Domain use-case protocols
//
//  Pure, platform-agnostic contracts for the domain layer: subcool estimation,
//  field validators, and the well-lookup use case. Concrete implementations are
//  provided by the domain-logic tasks (8, 9, 10). All conforming types are
//  deterministic functions over their inputs with no I/O.
//
//  Introduced by task 1.2.

import Foundation

// MARK: - Subcool estimation

/// Pure subcool estimator. Depends (in its concrete form) on an injected steam
/// table and a configurable flashing threshold.
public protocol SubcoolEstimating: Sendable {
    /// Estimate subcool from a casing pressure and pump temperature.
    ///
    /// Returns `.success(subcool, isFlashing)` where
    /// `subcool == saturationTemperature(casingPressure) - pumpTemperature`
    /// and `isFlashing == subcool <= flashingThreshold`; `.outOfRange` when
    /// the pressure is outside the steam-table domain; `.invalidInput` when an
    /// input is empty or non-numeric.
    func estimate(casingPressure: String, pumpTemperature: String) -> SubcoolResult

    /// Saturation temperature for a pressure, or `nil` when the pressure is
    /// outside the steam-table domain.
    func saturationTemperature(pressure: Double) -> Double?
}

// MARK: - Validators

/// Validates an as-entered production log, normalizing to a `WellheadLog`
/// (`logType == .production`) when valid and returning the exact offending
/// field set when invalid.
public protocol ProductionLogValidating: Sendable {
    func validate(_ input: ProductionLogInput) -> ValidationResult<WellheadLog>
}

/// Validates an as-entered injection log, normalizing to a `WellheadLog`
/// (`logType == .injection`) when valid and returning the exact offending
/// field set when invalid.
public protocol InjectionLogValidating: Sendable {
    func validate(_ input: InjectionLogInput) -> ValidationResult<WellheadLog>
}

/// Validates an escalation. Requires a priority (High/Medium/Low) and a well
/// id; normalizes to a validated `EscalationInput` when valid and reports
/// exactly the absent selections (retaining data) when invalid.
public protocol EscalationValidating: Sendable {
    func validate(_ input: EscalationInput) -> ValidationResult<EscalationInput>
}

// MARK: - Well lookup

/// Looks up a well (by the identifier decoded from a QR code) against the
/// local store. The result is identical regardless of connectivity when the
/// well exists locally; a miss branches on connectivity (`notFound` online,
/// `unavailableOffline` offline).
public protocol WellLookupUseCase: Sendable {
    func lookup(wellId: String) async -> WellLookupResult
}
