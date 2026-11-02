//
//  Subcool estimator
//
//  Pure, deterministic implementation of `SubcoolEstimating` (design "Subcool
//  Estimator"). Given a casing pressure and a pump temperature it computes
//
//      Tsat    = steamTable.saturationTemperature(casingPressure)   // interpolated
//      subcool = Tsat - pumpTemperature                              // °C  (Req 5.1)
//      flashing = subcool <= flashingThreshold                       //      (Req 5.3)
//
//  returning `.success(subcool, isFlashing)`, `.outOfRange(field:)` when the
//  pressure is outside the steam-table domain (Req 5.4), or
//  `.invalidInput(field:)` when an input is empty or non-numeric (Req 5.5).
//
//  The estimator is value logic over an injected `SteamTable` and a
//  configurable `flashingThreshold`; it performs no I/O and is the primary
//  target of the subcool property-based tests (tasks 8.2–8.6).

import Foundation

/// A pure subcool estimator backed by an injected steam table and a
/// configurable flashing threshold.
///
/// String inputs are validated (empty / non-numeric) at the boundary so the UI
/// can surface a per-field `invalidInput` message and retain entered values
/// (Req 5.5); the numeric core then mirrors the design's algorithm exactly.
public struct SubcoolEstimator: SubcoolEstimating {

    /// Field identifiers returned on the `invalidInput` / `outOfRange` cases.
    /// They match the parameter names so the UI can map an error back to the
    /// field that produced it.
    public enum Field {
        public static let casingPressure = "casingPressure"
        public static let pumpTemperature = "pumpTemperature"
    }

    /// The reference saturation dataset used for the pressure -> temperature
    /// lookup.
    public let steamTable: SteamTable

    /// Subcool at or below this value is classified as flashing (Req 5.3).
    /// Injected so the classification boundary is configurable and testable.
    public let flashingThreshold: Double

    /// The default flashing threshold (°C) used when none is injected.
    ///
    /// A subcool margin of 10 °C is a common conservative SAGD operating
    /// guideline; at or below it the fluid is treated as at risk of flashing.
    public static let defaultFlashingThreshold: Double = 10.0

    // MARK: - Construction

    /// Creates an estimator over a steam table and flashing threshold.
    ///
    /// - Parameters:
    ///   - steamTable: The saturation dataset (defaults to ``SteamTable/bundled``).
    ///   - flashingThreshold: The flashing-zone boundary in °C
    ///     (defaults to ``defaultFlashingThreshold``).
    public init(
        steamTable: SteamTable = .bundled,
        flashingThreshold: Double = SubcoolEstimator.defaultFlashingThreshold
    ) {
        self.steamTable = steamTable
        self.flashingThreshold = flashingThreshold
    }

    // MARK: - SubcoolEstimating

    /// Estimates subcool from as-entered casing pressure and pump temperature.
    ///
    /// Validation order mirrors the design pseudocode: casing pressure is
    /// checked for empty/non-numeric first, then pump temperature, then the
    /// pressure is checked against the steam-table domain, and finally the
    /// subcool is computed by interpolation.
    ///
    /// - Parameters:
    ///   - casingPressure: Casing pressure as entered (kPa).
    ///   - pumpTemperature: Pump temperature as entered (°C).
    /// - Returns: `.success(subcool, isFlashing)`, `.outOfRange(field:)`, or
    ///   `.invalidInput(field:)`.
    public func estimate(casingPressure: String, pumpTemperature: String) -> SubcoolResult {
        guard let pressure = Self.parseNumeric(casingPressure) else {
            return .invalidInput(field: Field.casingPressure)
        }
        guard let pumpTemp = Self.parseNumeric(pumpTemperature) else {
            return .invalidInput(field: Field.pumpTemperature)
        }
        return estimate(casingPressure: pressure, pumpTemperature: pumpTemp)
    }

    /// Numeric core of the estimate (design's `estimateSubcool`): both inputs
    /// are already valid numbers, so only the steam-table domain check and the
    /// interpolation remain.
    ///
    /// - Parameters:
    ///   - casingPressure: Casing pressure (kPa).
    ///   - pumpTemperature: Pump temperature (°C).
    /// - Returns: `.success(subcool, isFlashing)` or `.outOfRange(field:)`.
    public func estimate(casingPressure: Double, pumpTemperature: Double) -> SubcoolResult {
        guard let tsat = steamTable.saturationTemperature(pressure: casingPressure) else {
            return .outOfRange(field: Field.casingPressure)
        }
        let subcool = tsat - pumpTemperature
        let isFlashing = subcool <= flashingThreshold
        return .success(subcool: subcool, isFlashing: isFlashing)
    }

    /// Saturation temperature for a pressure, or `nil` outside the steam-table
    /// domain. Thin pass-through to the injected table so callers can preview a
    /// saturation temperature without running a full estimate.
    public func saturationTemperature(pressure: Double) -> Double? {
        steamTable.saturationTemperature(pressure: pressure)
    }

    // MARK: - Numeric parsing

    /// Parses a field as a number using the domain's numeric-format rule:
    /// an optional leading minus, one or more decimal digits, and at most one
    /// decimal point. Returns `nil` for empty or malformed input.
    ///
    /// Surrounding whitespace is trimmed first so a field that is blank or
    /// contains only spaces is treated as empty. Locale-specific grouping
    /// separators and alternate decimal marks are intentionally rejected: the
    /// format is the plain machine representation the validators also enforce.
    ///
    /// - Parameter raw: The as-entered field value.
    /// - Returns: The parsed value, or `nil` if empty or non-numeric.
    static func parseNumeric(_ raw: String) -> Double? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard isNumericFormat(trimmed) else { return nil }
        // `Double(_:)` parses the plain format we just validated; it stays nil
        // only for values that overflow to a non-finite magnitude, which we
        // also reject.
        guard let value = Double(trimmed), value.isFinite else { return nil }
        return value
    }

    /// Whether `candidate` matches the numeric format: optional leading `-`,
    /// at least one digit, and at most one decimal point with at least one
    /// digit somewhere (e.g. "5", "-5", "5.0", ".5", "5." are the cases we must
    /// decide on). We require a digit on at least one side of the point and
    /// reject a bare "-", "." or "-.".
    private static func isNumericFormat(_ candidate: String) -> Bool {
        var sawDigit = false
        var sawDot = false
        for (index, character) in candidate.enumerated() {
            switch character {
            case "-":
                // Minus is only allowed as the very first character.
                if index != 0 { return false }
            case ".":
                // At most one decimal point.
                if sawDot { return false }
                sawDot = true
            case "0"..."9":
                sawDigit = true
            default:
                return false
            }
        }
        return sawDigit
    }
}
