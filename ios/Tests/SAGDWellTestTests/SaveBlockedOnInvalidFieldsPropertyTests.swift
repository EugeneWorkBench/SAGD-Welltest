//
//  SaveBlockedOnInvalidFieldsPropertyTests.swift
//
//  Property 12: Save blocked exactly on invalid fields.
//
//  For a wellhead-log form, validation returns `.invalid` if and only if at
//  least one field is empty or otherwise invalid; and when `.invalid`, the
//  reported error set is *exactly* the set of offending fields — no more, no
//  fewer — each named once. Equivalently, validation returns `.valid` iff every
//  field is a well-formed, in-range value. In every case the entered values are
//  retained unchanged (the pure validator never mutates its input, so the UI
//  can keep the operator's data on screen while blocking the save).
//
//  Expressed concretely against `ProductionLogValidator` and
//  `InjectionLogValidator`: each field is generated independently as either a
//  valid-in-range value or an invalid one (empty / non-numeric / out-of-range).
//  The test computes the expected offending-field set from those choices and
//  asserts:
//    * `.valid` exactly when the offending set is empty, and
//    * otherwise `.invalid` carrying exactly the offending fields, each with the
//      kind the validator reports under its precedence
//      (empty → notNumeric → outOfRange), and in the validator's field order.
//
//  Validates: Requirements 3.5, 4.4.
//
//  Implements task 9.4. Runs a minimum of 100 iterations via `pbtArguments()`,
//  tagged `Feature: sagd-welltest-mobile, Property 12: ...`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class SaveBlockedOnInvalidFieldsPropertyTests: XCTestCase {

    // MARK: - Per-field choice

    /// How a single field is generated for a case: either a well-formed,
    /// in-range value (accepted), or one of the three ways a field can offend.
    /// The `kind` of the resulting `FieldError` is fully determined by the
    /// choice, matching the validator's precedence (presence, then numeric
    /// format, then range).
    private enum FieldChoice: Equatable {
        /// A well-formed numeric string inside the field's range. No error.
        case valid(String)
        /// An empty or whitespace-only string. Yields `.empty`.
        case empty(String)
        /// A well-formed-looking-but-malformed string. Yields `.notNumeric`.
        case notNumeric(String)
        /// A well-formed numeric string strictly outside the range. Yields
        /// `.outOfRange`.
        case outOfRange(String)

        /// The raw string to place in the field.
        var raw: String {
            switch self {
            case .valid(let s), .empty(let s), .notNumeric(let s), .outOfRange(let s):
                return s
            }
        }

        /// The `FieldError.Kind` the validator reports for this choice, given
        /// the field's `[min, max]` range. `valid` has no kind.
        func errorKind(min: Double, max: Double) -> FieldError.Kind? {
            switch self {
            case .valid: return nil
            case .empty: return .empty
            case .notNumeric: return .notNumeric
            case .outOfRange: return .outOfRange(min: min, max: max)
            }
        }
    }

    // MARK: - Field specs (id + range), in validator order

    /// A bounded numeric field: its stable id and inclusive accepted range.
    /// The order of these arrays mirrors the order in which each validator
    /// appends errors, so the expected error list can be built positionally.
    private struct FieldSpec {
        let id: String
        let min: Double
        let max: Double
    }

    private static let productionSpecs: [FieldSpec] = [
        FieldSpec(id: WellheadLogField.emulsionFlowRate,
                  min: WellheadLogFieldRange.productionMin,
                  max: WellheadLogFieldRange.productionMax),
        FieldSpec(id: WellheadLogField.casingGasPressure,
                  min: WellheadLogFieldRange.productionMin,
                  max: WellheadLogFieldRange.productionMax),
        FieldSpec(id: WellheadLogField.espMotorTemperature,
                  min: WellheadLogFieldRange.productionMin,
                  max: WellheadLogFieldRange.productionMax),
        FieldSpec(id: WellheadLogField.waterCutPercent,
                  min: WellheadLogFieldRange.waterCutMin,
                  max: WellheadLogFieldRange.waterCutMax),
    ]

    private static let injectionSpecs: [FieldSpec] = [
        FieldSpec(id: WellheadLogField.steamInjectionPressure,
                  min: WellheadLogFieldRange.steamPressureMin,
                  max: WellheadLogFieldRange.steamPressureMax),
        FieldSpec(id: WellheadLogField.steamTemperature,
                  min: WellheadLogFieldRange.steamTemperatureMin,
                  max: WellheadLogFieldRange.steamTemperatureMax),
        FieldSpec(id: WellheadLogField.steamFlowRate,
                  min: WellheadLogFieldRange.steamFlowMin,
                  max: WellheadLogFieldRange.steamFlowMax),
    ]

    // MARK: - Helpers

    private static func errors<T>(_ result: ValidationResult<T>) -> [FieldError] {
        switch result {
        case .valid: return []
        case .invalid(let e): return e
        }
    }

    private static func isValid<T>(_ result: ValidationResult<T>) -> Bool {
        if case .valid = result { return true }
        return false
    }

    /// The exact expected error list for a set of per-field choices, in the
    /// validator's field order, one entry per offending field.
    private static func expectedErrors(
        specs: [FieldSpec],
        choices: [FieldChoice]
    ) -> [FieldError] {
        zip(specs, choices).compactMap { spec, choice in
            guard let kind = choice.errorKind(min: spec.min, max: spec.max) else {
                return nil
            }
            return FieldError(field: spec.id, kind: kind)
        }
    }

    // MARK: - Generators

    /// A well-formed numeric string whose value lies inside `[min, max]`.
    /// Rendered with two decimals so it parses back in range (both bounds here
    /// are multiples of 1/100). All ranges start at 0, so staying non-negative
    /// and at/below `max` stays in range.
    private static func inRangeString(min: Double, max: Double) -> Gen<String> {
        let minH = Int((min * 100).rounded())
        let maxH = Int((max * 100).rounded())
        return Gen<Int>.choose((minH, maxH)).map { String(format: "%.2f", Double($0) / 100.0) }
    }

    /// A well-formed numeric string strictly outside `[min, max]` — either below
    /// the minimum (a negative, via the grammar's leading minus) or above the
    /// maximum. The offset is >= 1/100 so, after two-decimal rounding, it stays
    /// strictly out of range.
    private static func outOfRangeString(min: Double, max: Double) -> Gen<String> {
        let below = Gen<Int>.choose((1, 100_000)).map { String(format: "%.2f", min - Double($0) / 100.0) }
        let above = Gen<Int>.choose((1, 100_000)).map { String(format: "%.2f", max + Double($0) / 100.0) }
        return Gen<String>.one(of: [below, above])
    }

    /// An empty or whitespace-only string (trimmed to empty by the parser).
    private static let emptyString: Gen<String> =
        Gen<String>.fromElements(of: ["", " ", "   ", "\t", " \t "])

    /// A malformed (non-numeric) string that is neither empty nor a well-formed
    /// number, so the parser reports `.notNumeric` (not `.empty`).
    private static let notNumericString: Gen<String> =
        Gen<String>.fromElements(of: [
            "abc", "12a", "a12", "1 2", "+1", "1+1", "1e3", "1E3",
            "1.2e3", "1.2.3", "..5", "5..", "--1", "1-2", "1,000",
            "$5", "0x1F", "NaN", "inf", "-", ".", "-.", "1_000",
        ])

    /// A generator of one field's choice for the given range, uniformly across
    /// the four kinds (valid / empty / non-numeric / out-of-range) so that over
    /// 100+ cases both all-valid and every offending combination are exercised.
    private static func fieldChoice(min: Double, max: Double) -> Gen<FieldChoice> {
        Gen<FieldChoice>.one(of: [
            inRangeString(min: min, max: max).map { FieldChoice.valid($0) },
            emptyString.map { FieldChoice.empty($0) },
            notNumericString.map { FieldChoice.notNumeric($0) },
            outOfRangeString(min: min, max: max).map { FieldChoice.outOfRange($0) },
        ])
    }

    /// Four independent field choices for the production form (emulsion flow
    /// rate, casing gas pressure, ESP motor temperature, water cut), in the
    /// validator's field order.
    private static func productionChoices() -> Gen<[FieldChoice]> {
        let f0 = fieldChoice(min: productionSpecs[0].min, max: productionSpecs[0].max)
        let f1 = fieldChoice(min: productionSpecs[1].min, max: productionSpecs[1].max)
        let f2 = fieldChoice(min: productionSpecs[2].min, max: productionSpecs[2].max)
        let f3 = fieldChoice(min: productionSpecs[3].min, max: productionSpecs[3].max)
        return Gen<(FieldChoice, FieldChoice, FieldChoice, FieldChoice)>
            .zip(f0, f1, f2, f3)
            .map { [$0.0, $0.1, $0.2, $0.3] }
    }

    /// Three independent field choices for the injection form (steam injection
    /// pressure, steam temperature, steam flow rate), in the validator's field
    /// order.
    private static func injectionChoices() -> Gen<[FieldChoice]> {
        let f0 = fieldChoice(min: injectionSpecs[0].min, max: injectionSpecs[0].max)
        let f1 = fieldChoice(min: injectionSpecs[1].min, max: injectionSpecs[1].max)
        let f2 = fieldChoice(min: injectionSpecs[2].min, max: injectionSpecs[2].max)
        return Gen<(FieldChoice, FieldChoice, FieldChoice)>
            .zip(f0, f1, f2)
            .map { [$0.0, $0.1, $0.2] }
    }

    // MARK: - Property 12

    func testSaveBlockedExactlyOnInvalidFields() {
        property(
            propertyName(12, "save blocked exactly on invalid fields"),
            arguments: pbtArguments()
        ) <- forAll(Self.productionChoices(), Self.injectionChoices()) {
            (prodChoices: [FieldChoice], injChoices: [FieldChoice]) in

            // --- Production validator ---
            let productionResult = Self.checkProduction(prodChoices)
            // --- Injection validator ---
            let injectionResult = Self.checkInjection(injChoices)

            return productionResult ^&&^ injectionResult
        }
    }

    /// Builds a production input from the per-field choices, validates it, and
    /// checks the valid-iff-no-offenders property, the exact offending-field
    /// set, and value retention.
    private static func checkProduction(_ choices: [FieldChoice]) -> Property {
        let input = ProductionLogInput(
            wellId: "WELL-1",
            emulsionFlowRate: choices[0].raw,
            casingGasPressure: choices[1].raw,
            espMotorTemperature: choices[2].raw,
            waterCutPercent: choices[3].raw
        )
        let result = ProductionLogValidator().validate(input)
        let expected = expectedErrors(specs: productionSpecs, choices: choices)
        return assertMatches(
            label: "production",
            input: input,
            readBack: {
                ProductionLogInput(
                    wellId: input.wellId,
                    emulsionFlowRate: $0.emulsionFlowRate,
                    casingGasPressure: $0.casingGasPressure,
                    espMotorTemperature: $0.espMotorTemperature,
                    waterCutPercent: $0.waterCutPercent
                )
            },
            result: result,
            expected: expected
        )
    }

    /// Builds an injection input from the per-field choices, validates it, and
    /// checks the same three facts.
    private static func checkInjection(_ choices: [FieldChoice]) -> Property {
        let input = InjectionLogInput(
            wellId: "WELL-1",
            steamInjectionPressure: choices[0].raw,
            steamTemperature: choices[1].raw,
            steamFlowRate: choices[2].raw
        )
        let result = InjectionLogValidator().validate(input)
        let expected = expectedErrors(specs: injectionSpecs, choices: choices)
        return assertMatches(
            label: "injection",
            input: input,
            readBack: { $0 },
            result: result,
            expected: expected
        )
    }

    /// Shared assertions for one validator run:
    ///   1. `.valid` iff the expected offending set is empty.
    ///   2. When invalid, the error list equals exactly the expected offending
    ///      set (same fields, same kinds, each once, in field order).
    ///   3. The input value is retained unchanged (the pure validator does not
    ///      mutate it), satisfying the "retain all entered values" requirement.
    private static func assertMatches<Input: Equatable, Normalized>(
        label: String,
        input: Input,
        readBack: (Input) -> Input,
        result: ValidationResult<Normalized>,
        expected: [FieldError]
    ) -> Property {
        let shouldBeValid = expected.isEmpty
        let actualValid = isValid(result)
        let actualErrors = errors(result)

        // 1. valid iff no offenders.
        let validIff = (actualValid == shouldBeValid)
            <?> "\(label): expected .valid == \(shouldBeValid) (offenders: \(expected)); got valid=\(actualValid), errors=\(actualErrors)"

        // 2. invalid carries exactly the offending fields (order + kinds).
        let exactSet: Property
        if shouldBeValid {
            exactSet = (actualErrors.isEmpty)
                <?> "\(label): valid case must carry no errors; got \(actualErrors)"
        } else {
            exactSet = (actualErrors == expected)
                <?> "\(label): invalid case must carry exactly \(expected); got \(actualErrors)"
        }

        // 3. entered values retained (input unchanged).
        let retained = (readBack(input) == input)
            <?> "\(label): entered values must be retained unchanged"

        return validIff ^&&^ exactSet ^&&^ retained
    }
}
