//
//  NumericFormatValidationPropertyTests.swift
//
//  Property 10: Numeric-format validation.
//
//  For any input string for a numeric field (production emulsion flow rate,
//  casing gas pressure, ESP motor temperature; injection pressure, temperature,
//  flow rate), the validator accepts the entry iff the string consists solely
//  of digits with at most one decimal point and an optional leading minus sign;
//  otherwise it rejects the entry with a numeric-format error.
//
//  Expressed concretely against the validators: a *well-formed, in-range* value
//  placed in a field (with every other field valid) produces NO `notNumeric`
//  error for that field, while a *malformed* value placed in that field
//  produces EXACTLY one `FieldError` for that field with kind `.notNumeric`
//  (and no other field errors, since the other fields are valid).
//
//  Validates: Requirements 3.3, 4.2.
//
//  Implements task 9.2. Runs a minimum of 100 iterations via `pbtArguments()`,
//  tagged `Feature: sagd-welltest-mobile, Property 10: ...`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class NumericFormatValidationPropertyTests: XCTestCase {

    // MARK: - Field descriptor

    /// A numeric field under test: how to inject a raw string into the relevant
    /// validator, run it, and the inclusive range the field accepts (so the
    /// well-formed generator can stay in range and isolate the numeric-format
    /// rule from the range rule).
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
    // that the only field that can ever fail is the one under test. These are
    // plainly well-formed and inside every range used below.
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

    /// All six numeric fields across the production and injection validators,
    /// each paired with its accepted range.
    private static func fieldsUnderTest() -> [FieldUnderTest] {
        let production = ProductionLogValidator()
        let injection = InjectionLogValidator()

        return [
            FieldUnderTest(
                id: "emulsionFlowRate",
                rangeMin: 0.0, rangeMax: 999_999.99
            ) { raw in
                var input = validProduction
                input.emulsionFlowRate = raw
                return errors(production.validate(input))
            },
            FieldUnderTest(
                id: "casingGasPressure",
                rangeMin: 0.0, rangeMax: 999_999.99
            ) { raw in
                var input = validProduction
                input.casingGasPressure = raw
                return errors(production.validate(input))
            },
            FieldUnderTest(
                id: "espMotorTemperature",
                rangeMin: 0.0, rangeMax: 999_999.99
            ) { raw in
                var input = validProduction
                input.espMotorTemperature = raw
                return errors(production.validate(input))
            },
            FieldUnderTest(
                id: "steamInjectionPressure",
                rangeMin: 0.0, rangeMax: 25_000.0
            ) { raw in
                var input = validInjection
                input.steamInjectionPressure = raw
                return errors(injection.validate(input))
            },
            FieldUnderTest(
                id: "steamTemperature",
                rangeMin: 0.0, rangeMax: 400.0
            ) { raw in
                var input = validInjection
                input.steamTemperature = raw
                return errors(injection.validate(input))
            },
            FieldUnderTest(
                id: "steamFlowRate",
                rangeMin: 0.0, rangeMax: 2_000.0
            ) { raw in
                var input = validInjection
                input.steamFlowRate = raw
                return errors(injection.validate(input))
            },
        ]
    }

    // MARK: - Generators

    /// Generates a well-formed numeric string per the accepted grammar
    /// (optional single leading minus, digits, at most one decimal point),
    /// constrained so its value lands inside the given `[min, max]` range. This
    /// isolates the numeric-format rule: a well-formed in-range value must not
    /// produce a `notNumeric` error.
    private static func wellFormedInRange(min: Double, max: Double) -> Gen<String> {
        // Choose an integer part and an optional fractional part, keeping the
        // overall magnitude comfortably inside the range. All ranges under test
        // have min == 0, so staying non-negative and below max keeps us in range.
        let upperInt = Int(max.rounded(.down))
        let intPart = Gen<Int>.choose((0, Swift.max(0, upperInt - 1)))

        let withFraction: Gen<String> = Gen<(Int, Int)>
            .zip(intPart, Gen<Int>.choose((0, 99)))
            .map { whole, frac in
                // Two-decimal fraction keeps the value safely below `max` for
                // every range here (fraction < 1).
                "\(whole).\(frac)"
            }

        let withoutFraction: Gen<String> = intPart.map { "\($0)" }

        return Gen<String>.one(of: [withFraction, withoutFraction])
    }

    /// Generates a malformed string that violates the accepted numeric grammar
    /// in at least one way: letters, a `+` sign, an exponent, multiple decimal
    /// points, interior signs, stray symbols, or sign/dot-only strings. Every
    /// value produced here must be rejected with `.notNumeric`.
    private static let malformed: Gen<String> = {
        // A pool of concrete malformed literals covering the grammar violations
        // called out in the requirements.
        let fixed: [String] = [
            "abc",        // letters
            "12a",        // trailing letter
            "a12",        // leading letter
            "1 2",        // interior space
            "+1",         // leading plus
            "1+1",        // interior plus
            "1e3",        // exponent
            "1E3",        // exponent (upper)
            "1.2e3",      // decimal + exponent
            "1.2.3",      // multiple decimal points
            "..5",        // multiple decimal points (leading)
            "5..",        // multiple decimal points (trailing)
            "--1",        // double minus
            "1-2",        // interior minus
            "1,000",      // grouping separator
            "$5",         // currency symbol
            "0x1F",       // hex literal
            "NaN",        // not-a-number literal
            "inf",        // infinity literal
            "-",          // sign only (no digits)
            ".",          // dot only (no digits)
            "-.",         // sign + dot, no digits
            "1_000",      // underscore separator
            "१२३",        // non-ASCII digits
        ]

        // Also synthesize malformed strings by inserting a disallowed character
        // into an otherwise-numeric string, broadening coverage beyond the pool.
        let disallowed = Gen<Character>.fromElements(of: Array("+eE,x$% \t/:;?*abXYZ"))
        let synthesized: Gen<String> = Gen<(Int, Character)>
            .zip(Gen<Int>.choose((0, 999)), disallowed)
            .map { digits, bad in "\(digits)\(bad)" }

        let fromPool: Gen<String> = Gen<String>.fromElements(of: fixed)
        return Gen<String>.one(of: [fromPool, synthesized])
    }()

    // MARK: - Property 10

    func testNumericFormatValidation() {
        let fields = Self.fieldsUnderTest()

        // Pick a field uniformly for each case so all six fields across both
        // validators are exercised over the 100+ iterations.
        let fieldIndex = Gen<Int>.choose((0, fields.count - 1))

        property(
            propertyName(10, "numeric-format validation"),
            arguments: pbtArguments()
        ) <- forAll(fieldIndex) { index in
            let field = fields[index]

            // Build the two generators lazily for the chosen field's range.
            let wellFormed = Self.wellFormedInRange(min: field.rangeMin, max: field.rangeMax)

            // --- Well-formed, in-range branch: NO notNumeric error. ---
            let acceptsWellFormed = forAll(wellFormed) { raw in
                let errs = field.validate(raw)
                let numericErrs = errs.filter {
                    $0.field == field.id && $0.kind == .notNumeric
                }
                return numericErrs.isEmpty
                    <?> "well-formed in-range '\(raw)' must not yield a notNumeric error for \(field.id); got \(errs)"
            }

            // --- Malformed branch: EXACTLY one notNumeric error for the field,
            //     and no other field errors (the other fields are valid). ---
            let rejectsMalformed = forAll(Self.malformed) { raw in
                let errs = field.validate(raw)
                let expected = [FieldError(field: field.id, kind: .notNumeric)]
                return (errs == expected)
                    <?> "malformed '\(raw)' must yield exactly one notNumeric error for \(field.id); got \(errs)"
            }

            return acceptsWellFormed ^&&^ rejectsMalformed
        }
    }
}
