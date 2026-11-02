//
//  Validator input types
//
//  Raw, as-entered field sets handed to the pure validators. Fields are kept
//  as strings because validation includes a numeric-format check (digits, at
//  most one decimal point, optional leading minus) that must run before the
//  value can be interpreted as a number. Validators normalize valid inputs and
//  report the exact offending field set for invalid ones.
//
//  Introduced by task 1.2; consumed by the validators implemented in task 9.

import Foundation

/// As-entered fields for a production wellhead log.
public struct ProductionLogInput: Sendable, Equatable, Hashable {
    /// Associated well identifier.
    public var wellId: String
    /// Emulsion flow rate, as entered (range 0.00–999,999.99 once parsed).
    public var emulsionFlowRate: String
    /// Casing gas pressure, as entered (range 0.00–999,999.99 once parsed).
    public var casingGasPressure: String
    /// ESP motor temperature, as entered (range 0.00–999,999.99 once parsed).
    public var espMotorTemperature: String
    /// Water cut percentage, as entered (range 0–100 once parsed).
    public var waterCutPercent: String

    public init(
        wellId: String,
        emulsionFlowRate: String,
        casingGasPressure: String,
        espMotorTemperature: String,
        waterCutPercent: String
    ) {
        self.wellId = wellId
        self.emulsionFlowRate = emulsionFlowRate
        self.casingGasPressure = casingGasPressure
        self.espMotorTemperature = espMotorTemperature
        self.waterCutPercent = waterCutPercent
    }
}

/// As-entered fields for an injection wellhead log.
public struct InjectionLogInput: Sendable, Equatable, Hashable {
    /// Associated well identifier.
    public var wellId: String
    /// Steam injection pressure in kPa, as entered (range 0–25,000 once parsed).
    public var steamInjectionPressure: String
    /// Steam temperature in °C, as entered (range 0–400 once parsed).
    public var steamTemperature: String
    /// Steam flow rate in m³/day, as entered (range 0–2,000 once parsed).
    public var steamFlowRate: String

    public init(
        wellId: String,
        steamInjectionPressure: String,
        steamTemperature: String,
        steamFlowRate: String
    ) {
        self.wellId = wellId
        self.steamInjectionPressure = steamInjectionPressure
        self.steamTemperature = steamTemperature
        self.steamFlowRate = steamFlowRate
    }
}

/// As-selected inputs for an escalation. Save is blocked unless both a priority
/// and a well are present.
public struct EscalationInput: Sendable, Equatable, Hashable {
    /// Selected well identifier, if any.
    public var wellId: String?
    /// Selected priority, if any.
    public var priority: EscalationPriority?

    public init(wellId: String? = nil, priority: EscalationPriority? = nil) {
        self.wellId = wellId
        self.priority = priority
    }
}
