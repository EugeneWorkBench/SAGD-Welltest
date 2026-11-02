//
//  RangeValidationPropertyTests.swift
//
//  Property 11: Range validation per field.
//
//  For any numeric value entered into a bounded field, the validator rejects
//  the entry if and only if the value falls outside that field's accepted
//  range — water cut percentage outside [0, 100]; production fields
//  (emulsion flow rate, casing gas pressure, ESP motor temperature) outside
//  [0.00, 999,999.99]; steam injection pressure outside [0, 25,000]; steam
//  temperature outside [0, 400]; steam flow rate outside [0, 2,000] — and
//  otherwise accepts it.
//
//  Expressed concretely against the validators, with every other field held
//  valid and in range so the field under test is the only one that can fail:
//
//    * an in-range value placed in a field produces NO `.outOfRange` error for
//      that field (it is accepted); and
//    * an out-of-range value (strictly below the field's minimum or strictly
//      above its maximum) produces EXACTLY one `FieldError` for that field with
//      kind `.outOfRange(min:max:)` and no other field errors.
//
//  Out-of-range values are generated as *well-formed* numeric strings (the
//  grammar permits an optional leading minus, so below-minimum negatives are
//  numerically well-formed) so the range rule is isolated from the
//  numeric-format rule: a well-formed out-of-range value must be rejected for
//  range, not for format.
//
//  Validates: Requirements 3.2, 3.4, 4.3.
//
//  Implements task 9.3. Runs a minimum of 100 iterations via `pbtArguments()`,
//  tagged `Feature: sagd-welltest-mobile, Property 11: ...`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class RangeValidationPropertyTests: XCTestCase {

    // MARK: - Field descriptor

    /// A bounded numeric field under test: how to inject a raw string into the
    /// relevant validator and run it, plus the inclusive range the field
    /// accepts.
    private struct FieldUnderTest {
        let id: String
        let rangeMin: Double
        let rangeMax: Double
        /// Validate with `raw` substituted into this field and all other fields
        /// set to fixed, valid, in-range values; returns the error list
        /// (empty when `.valid`).
        let validate: (_ raw: String) -> [FieldError]
    }

    // Fixed, known-valid, in-range placeholder values for the "other" fields so
    // that the only field that can ever fail is the one under test. "10" is
    // well-formed and inside every range used below.
    private static let validProduction = ProductionLogInput(
        wellId: "WELL-1",
        emulsionFlowRate: "10",
        casingGasPressure: "10",
        espMotorTemperature: "10",
        waterCutPercent: "10"
    )
    private static let validInjection = InjectionLogInput(
        wellId: "WELL-1",
        steamInjectionPressure: "10",
        steamTemperature: "10",
        steamFlowRate: "10"
    )

    private static func errors<T>(_ result: ValidationResult<T>) -> [FieldError] {
        switch result {
        case .valid: return []
        case .invalid(let e): return e
        }
    }

    /// All six bounded numeric fields across the production and injection
    /// validators, each paired with its accepted inclusive range (matching the
    /// ranges in `WellheadLogFieldRange`).
    private static func fieldsUnderTest() -> [FieldUnderTest] {
        let production = ProductionLogValidator()
        let injection = InjectionLogValidator()

        return [
            // --- Production fields: [0.00, 999,999.99] (Req 3.4) ---
            FieldUnderTest(
                id: WellheadLogField.emulsionFlowRate,
                rangeMin: WellheadLogFieldRange.productionMin,
                rangeMax: WellheadLogFieldRange.productionMax
            ) { raw in
                var input = validProduction
                input.emulsionFlowRate = raw
                return errors(production.validate(input))
            },
            FieldUnderTest(
                id: WellheadLogField.casingGasPressure,
                rangeMin: WellheadLogFieldRange.productionMin,
                rangeMax: WellheadLogFieldRange.productionMax
            ) { raw in
                var input = validProduction
                input.casingGasPressure = raw
                return errors(production.validate(input))
            },
            FieldUnderTest(
                id: WellheadLogField.espMotorTemperature,
                rangeMin: WellheadLogFieldRange.productionMin,
                rangeMax: WellheadLogFieldRange.productionMax
            ) { raw in
                var input = validProduction
                input.espMotorTemperature = raw
                return errors(production.validate(input))
            },
            // --- Water cut percentage: [0, 100] (Req 3.2) ---
            FieldUnderTest(
                id: WellheadLogField.waterCutPercent,
                rangeMin: WellheadLogFieldRange.waterCutMin,
                rangeMax: WellheadLogFieldRange.waterCutMax
            ) { raw in
                var input = validProduction
                input.waterCutPercent = raw
                return errors(production.validate(input))
            },
            // --- Steam injection pressure: [0, 25,000] (Req 4.3) ---
            FieldUnderTest(
                id: WellheadLogField.steamInjectionPressure,
                rangeMin: WellheadLogFieldRange.steamPressureMin,
                rangeMax: WellheadLogFieldRange.steamPressureMax
            ) { raw in
                var input = validInjection
                input.steamInjectionPressure = raw
                return errors(injection.validate(input))
            },
            // --- Steam temperature: [0, 400] (Req 4.3) ---
            FieldUnderTest(
                id: WellheadLogField.steamTemperature,
                rangeMin: WellheadLogFieldRange.steamTemperatureMin,
                rangeMax: WellheadLogFieldRange.steamTemperatureMax
            ) { raw in
                var input = validInjection
                input.steamTemperature = raw
                return errors(injection.validate(input))
            },
            // --- Steam flow rate: [0, 2,000] (Req 4.3) ---
            FieldUnderTest(
                id: WellheadLogField.steamFlowRate,
                rangeMin: WellheadLogFieldRange.steamFlowMin,
                rangeMax: WellheadLogFieldRange.steamFlowMax
            ) { raw in
                var input = validInjection
                input.steamFlowRate = raw
                return errors(injection.validate(input))
            },
        ]
    }

    // MARK: - Generators

    /// Formats a non-negative `Double` as a well-formed numeric string under the
    /// accepted grammar (digits with at most one decimal point, no exponent, no
    /// grouping separators), preserving up to two fractional digits. The value
    /// parsed back from the result equals the input rounded to 1/100, which the
    /// callers account for when deciding in/out of range.
    private static func numericString(_ value: Double) -> String {
        // Round to two decimals, the finest precision any field range uses
        // (999,999.99). Rendering with %.2f yields plain `-?digits(.digits)?`
        // with no exponent or separators, matching the validator's grammar.
        String(format: "%.2f", value)
    }

    /// Generates an in-range value for `[min, max]` as a well-formed numeric
    /// string, spanning the interior and both inclusive boundaries. After the
    /// two-decimal rounding in `numericString`, the parsed value still lies in
    /// `[min, max]` because both bounds are themselves multiples of 1/100.
    private static func inRange(min: Double, max: Double) -> Gen<String> {
        // Interior values uniformly across the range (in hundredths).
        let minH = Int((min * 100).rounded())
        let maxH = Int((max * 100).rounded())
        let interior: Gen<String> = Gen<Int>.choose((minH, maxH)).map {
            numericString(Double($0) / 100.0)
        }
        // Exact inclusive boundaries, which must be accepted.
        let boundaries: Gen<String> = Gen<String>.fromElements(of: [
            numericString(min),
            numericString(max),
        ])
        return Gen<String>.one(of: [interior, boundaries])
    }

    /// Generates an out-of-range value for `[min, max]` as a well-formed numeric
    /// string: either strictly below `min` or strictly above `max`. The offset
    /// is at least 1/100 so, after two-decimal rounding, the value stays
    /// strictly outside the range. All ranges here have `min == 0`, so the
    /// below-min branch produces negatives (well-formed via the leading minus).
    private static func outOfRange(min: Double, max: Double) -> Gen<String> {
        // Below the minimum: min - delta, delta in [0.01, 1000.00].
        let belowDelta = Gen<Int>.choose((1, 100_000)).map { Double($0) / 100.0 }
        let below: Gen<String> = belowDelta.map { numericString(min - $0) }

        // Above the maximum: max + delta, delta in [0.01, 1000.00].
        let aboveDelta = Gen<Int>.choose((1, 100_000)).map { Double($0) / 100.0 }
        let above: Gen<String> = aboveDelta.map { numericString(max + $0) }

        return Gen<String>.one(of: [below, above])
    }

    // MARK: - Property 11

    func testRangeValidationPerField() {
        let fields = Self.fieldsUnderTest()

        // Pick a field uniformly for each case so all six bounded fields across
        // both validators are exercised over the 100+ iterations.
        let fieldIndex = Gen<Int>.choose((0, fields.count - 1))

        property(
            propertyName(11, "range validation per field"),
            arguments: pbtArguments()
        ) <- forAll(fieldIndex) { index in
            let field = fields[index]

            let inRange = Self.inRange(min: field.rangeMin, max: field.rangeMax)
            let outOfRange = Self.outOfRange(min: field.rangeMin, max: field.rangeMax)

            let expectedOutOfRange = FieldError(
                field: field.id,
                kind: .outOfRange(min: field.rangeMin, max: field.rangeMax)
            )

            // --- In-range branch: NO outOfRange error for the field. ---
            // (The value is well-formed and inside the range, so the field — and
            // every other field, which is fixed-valid — must be accepted.)
            let acceptsInRange = forAll(inRange) { raw in
                let errs = field.validate(raw)
                let rangeErrs = errs.filter {
                    $0.field == field.id && $0.kind == expectedOutOfRange.kind
                }
                return rangeErrs.isEmpty
                    <?> "in-range '\(raw)' must not yield an outOfRange error for \(field.id); got \(errs)"
            }

            // --- Out-of-range branch: EXACTLY one outOfRange error for the
            //     field, and no other field errors (other fields are valid). ---
            let rejectsOutOfRange = forAll(outOfRange) { raw in
                let errs = field.validate(raw)
                return (errs == [expectedOutOfRange])
                    <?> "out-of-range '\(raw)' must yield exactly one outOfRange error for \(field.id); got \(errs)"
            }

            return acceptsInRange ^&&^ rejectsOutOfRange
        }
    }
}
