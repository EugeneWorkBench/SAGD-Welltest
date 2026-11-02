//
//  Production and injection wellhead-log validators
//
//  Pure, platform-agnostic field validators for the two wellhead-log forms.
//  Each validator runs the same two-stage check on every numeric field —
//  a numeric-format check (digits, at most one decimal point, optional leading
//  minus) followed by a per-field range check — and then either normalizes the
//  input into a `WellheadLog` (associating the selected well identifier and a
//  UTC, one-second-precision capture timestamp) or reports exactly the set of
//  offending fields while the UI retains the entered values.
//
//  Deterministic functions over their inputs with no I/O. Implemented by task
//  9.1.
//
//  Requirements: 3.2, 3.3, 3.4, 3.5, 3.6, 4.2, 4.3, 4.4, 4.5.
//  Correctness properties: 10 (numeric-format), 11 (per-field range),
//  12 (save blocked exactly on invalid fields), 13 (association + timestamp).

import Foundation

// MARK: - Numeric field parsing

/// Shared, pure numeric-field parsing used by both log validators.
///
/// A field is parsed in two stages so that a field reports the *first* reason
/// it is unacceptable, matching the per-field validation messages the UI
/// shows:
///
/// 1. **Presence** — an empty (or whitespace-only) string is `.empty`.
/// 2. **Numeric format** — the trimmed string must consist solely of digits
///    with at most one decimal point and an optional leading minus sign;
///    otherwise it is `.notNumeric` (Req 3.3, 4.2; Property 10).
/// 3. **Range** — the parsed value must lie within the field's inclusive
///    `[min, max]` range; otherwise it is `.outOfRange(min:max:)`
///    (Req 3.2, 3.4, 4.3; Property 11).
enum NumericFieldParser {

    /// The outcome of parsing a single as-entered numeric field.
    enum Outcome: Equatable {
        /// The field parsed and lies within range; carries the value.
        case value(Double)
        /// The field failed; carries the specific reason.
        case failure(FieldError.Kind)
    }

    /// Parse a raw field string against an inclusive `[min, max]` range.
    ///
    /// The numeric-format grammar accepted here is deliberately strict and
    /// independent of locale: an optional single leading `-`, one or more
    /// digits, and at most one `.` decimal point. This is the exact grammar
    /// the requirements describe ("digits, a single decimal point, or a
    /// leading minus sign") rather than the looser set `Double(_:)` or a
    /// `NumberFormatter` would accept (which permit exponents, `+`, grouping
    /// separators, leading/trailing dots, etc.). Leading/trailing whitespace
    /// is trimmed before the grammar check so surrounding spaces are tolerated
    /// but interior non-numeric characters are not.
    static func parse(_ raw: String, min: Double, max: Double) -> Outcome {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)

        // 1. Presence.
        if trimmed.isEmpty {
            return .failure(.empty)
        }

        // 2. Numeric format.
        guard isWellFormedNumber(trimmed), let value = Double(trimmed) else {
            return .failure(.notNumeric)
        }

        // A value that parses but is not finite (should not occur under the
        // grammar, which admits no infinities or NaN literals) is treated as
        // non-numeric defensively.
        guard value.isFinite else {
            return .failure(.notNumeric)
        }

        // 3. Range (inclusive bounds).
        if value < min || value > max {
            return .failure(.outOfRange(min: min, max: max))
        }

        return .value(value)
    }

    /// Whether `s` matches the accepted numeric grammar: an optional single
    /// leading minus, one or more digits, and at most one decimal point. The
    /// decimal point may appear between digits or after them, and at least one
    /// digit must be present on the whole (e.g. `"-"`, `"."`, `"--1"`, `"1.2.3"`,
    /// and `"1e3"` are all rejected).
    private static func isWellFormedNumber(_ s: String) -> Bool {
        var chars = Substring(s)

        // Optional single leading minus.
        if chars.first == "-" {
            chars = chars.dropFirst()
        }

        // Nothing after an optional sign is not a number.
        if chars.isEmpty {
            return false
        }

        var sawDigit = false
        var sawDot = false
        for ch in chars {
            if ch == "." {
                if sawDot {
                    return false   // more than one decimal point
                }
                sawDot = true
            } else if ch.isASCIIDigit {
                sawDigit = true
            } else {
                return false       // any other character (sign, exponent, etc.)
            }
        }

        // At least one digit is required (rejects "-", ".", "-.").
        return sawDigit
    }
}

private extension Character {
    /// Whether the character is an ASCII decimal digit (`0`...`9`). Restricting
    /// to ASCII avoids accepting other Unicode decimal digits that `Double(_:)`
    /// would not parse anyway.
    var isASCIIDigit: Bool {
        guard let ascii = asciiValue else { return false }
        return ascii >= UInt8(ascii: "0") && ascii <= UInt8(ascii: "9")
    }
}

// MARK: - Field ranges

/// The inclusive numeric ranges for each wellhead-log field, in one place so
/// the validators and their tests share a single source of truth.
///
/// Requirements: water cut [0, 100] (Req 3.2); production emulsion flow rate,
/// casing gas pressure, and ESP motor temperature [0.00, 999,999.99] (Req 3.4);
/// steam injection pressure [0, 25,000], steam temperature [0, 400], steam
/// flow rate [0, 2,000] (Req 4.3).
enum WellheadLogFieldRange {
    /// Production fields (emulsion flow rate, casing gas pressure, ESP motor
    /// temperature): [0.00, 999,999.99].
    static let productionMin = 0.0
    static let productionMax = 999_999.99

    /// Water cut percentage: [0, 100].
    static let waterCutMin = 0.0
    static let waterCutMax = 100.0

    /// Steam injection pressure (kPa): [0, 25,000].
    static let steamPressureMin = 0.0
    static let steamPressureMax = 25_000.0

    /// Steam temperature (°C): [0, 400].
    static let steamTemperatureMin = 0.0
    static let steamTemperatureMax = 400.0

    /// Steam flow rate (m³/day): [0, 2,000].
    static let steamFlowMin = 0.0
    static let steamFlowMax = 2_000.0
}

// MARK: - Field identifiers

/// Stable field identifiers used in `FieldError.field` so the UI can map a
/// validation failure back to the specific input, and so the offending-field
/// set is deterministic and testable.
enum WellheadLogField {
    static let emulsionFlowRate = "emulsionFlowRate"
    static let casingGasPressure = "casingGasPressure"
    static let espMotorTemperature = "espMotorTemperature"
    static let waterCutPercent = "waterCutPercent"

    static let steamInjectionPressure = "steamInjectionPressure"
    static let steamTemperature = "steamTemperature"
    static let steamFlowRate = "steamFlowRate"
}

// MARK: - Timestamp normalization

/// Truncates a `Date` to one-second precision in UTC.
///
/// Both log timestamps are "recorded to the second in UTC" (Req 3.6, 4.5).
/// `Date` is an absolute instant (it carries no time zone), so "UTC" here
/// governs how the instant is *rendered*; the value stored is the same instant
/// truncated to a whole second. Truncating toward the earliest whole second
/// (floor) keeps the capture time at or before the moment of capture.
func utcSecondTruncated(_ date: Date) -> Date {
    let seconds = date.timeIntervalSince1970
    return Date(timeIntervalSince1970: seconds.rounded(.down))
}

// MARK: - Production log validator

/// Validates an as-entered production wellhead log.
///
/// Every field (emulsion flow rate, casing gas pressure, ESP motor temperature,
/// water cut percentage) is required and must be numeric and in range. On
/// success the input is normalized into a `WellheadLog` with
/// `logType == .production`, the selected well identifier, and a UTC
/// one-second-precision `capturedAt`. On failure the result carries exactly the
/// offending fields (one `FieldError` per field) so the UI blocks the save and
/// retains the entered values.
public struct ProductionLogValidator: ProductionLogValidating {

    /// Clock used to stamp the normalized record's `capturedAt`. Injectable so
    /// tests can assert the association-and-timestamp property deterministically.
    private let now: @Sendable () -> Date

    /// - Parameter now: Supplies the capture instant; defaults to `Date()`.
    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
    }

    public func validate(_ input: ProductionLogInput) -> ValidationResult<WellheadLog> {
        var errors: [FieldError] = []

        let emulsion = field(
            WellheadLogField.emulsionFlowRate, input.emulsionFlowRate,
            min: WellheadLogFieldRange.productionMin,
            max: WellheadLogFieldRange.productionMax,
            into: &errors
        )
        let casing = field(
            WellheadLogField.casingGasPressure, input.casingGasPressure,
            min: WellheadLogFieldRange.productionMin,
            max: WellheadLogFieldRange.productionMax,
            into: &errors
        )
        let esp = field(
            WellheadLogField.espMotorTemperature, input.espMotorTemperature,
            min: WellheadLogFieldRange.productionMin,
            max: WellheadLogFieldRange.productionMax,
            into: &errors
        )
        let waterCut = field(
            WellheadLogField.waterCutPercent, input.waterCutPercent,
            min: WellheadLogFieldRange.waterCutMin,
            max: WellheadLogFieldRange.waterCutMax,
            into: &errors
        )

        guard errors.isEmpty,
              let emulsion, let casing, let esp, let waterCut else {
            return .invalid(errors)
        }

        let log = WellheadLog(
            wellId: input.wellId,
            logType: .production,
            capturedAt: utcSecondTruncated(now()),
            emulsionFlowRate: emulsion,
            casingGasPressure: casing,
            espMotorTemperature: esp,
            waterCutPercent: waterCut
        )
        return .valid(log)
    }

    /// Parses one field, appending a `FieldError` to `errors` on failure and
    /// returning the parsed value on success.
    private func field(
        _ name: String,
        _ raw: String,
        min: Double,
        max: Double,
        into errors: inout [FieldError]
    ) -> Double? {
        switch NumericFieldParser.parse(raw, min: min, max: max) {
        case .value(let v):
            return v
        case .failure(let kind):
            errors.append(FieldError(field: name, kind: kind))
            return nil
        }
    }
}

// MARK: - Injection log validator

/// Validates an as-entered injection wellhead log.
///
/// Every field (steam injection pressure, steam temperature, steam flow rate)
/// is required and must be numeric and in range. On success the input is
/// normalized into a `WellheadLog` with `logType == .injection`, the selected
/// well identifier, and a UTC one-second-precision `capturedAt`. On failure the
/// result carries exactly the offending fields so the UI blocks the save and
/// retains the entered values.
public struct InjectionLogValidator: InjectionLogValidating {

    /// Clock used to stamp the normalized record's `capturedAt`. Injectable so
    /// tests can assert the association-and-timestamp property deterministically.
    private let now: @Sendable () -> Date

    /// - Parameter now: Supplies the capture instant; defaults to `Date()`.
    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
    }

    public func validate(_ input: InjectionLogInput) -> ValidationResult<WellheadLog> {
        var errors: [FieldError] = []

        let pressure = field(
            WellheadLogField.steamInjectionPressure, input.steamInjectionPressure,
            min: WellheadLogFieldRange.steamPressureMin,
            max: WellheadLogFieldRange.steamPressureMax,
            into: &errors
        )
        let temperature = field(
            WellheadLogField.steamTemperature, input.steamTemperature,
            min: WellheadLogFieldRange.steamTemperatureMin,
            max: WellheadLogFieldRange.steamTemperatureMax,
            into: &errors
        )
        let flow = field(
            WellheadLogField.steamFlowRate, input.steamFlowRate,
            min: WellheadLogFieldRange.steamFlowMin,
            max: WellheadLogFieldRange.steamFlowMax,
            into: &errors
        )

        guard errors.isEmpty,
              let pressure, let temperature, let flow else {
            return .invalid(errors)
        }

        let log = WellheadLog(
            wellId: input.wellId,
            logType: .injection,
            capturedAt: utcSecondTruncated(now()),
            steamInjectionPressure: pressure,
            steamTemperature: temperature,
            steamFlowRate: flow
        )
        return .valid(log)
    }

    /// Parses one field, appending a `FieldError` to `errors` on failure and
    /// returning the parsed value on success.
    private func field(
        _ name: String,
        _ raw: String,
        min: Double,
        max: Double,
        into errors: inout [FieldError]
    ) -> Double? {
        switch NumericFieldParser.parse(raw, min: min, max: max) {
        case .value(let v):
            return v
        case .failure(let kind):
            errors.append(FieldError(field: name, kind: kind))
            return nil
        }
    }
}
