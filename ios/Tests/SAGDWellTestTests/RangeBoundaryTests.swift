//
//  RangeBoundaryTests.swift
//
//  Task 9.6 — Unit test: range boundary examples (iOS).
//
//  Example-based companion to the Property 11 range test
//  (`RangeValidationPropertyTests`). Where the property test samples the full
//  range, this test pins down the *exact inclusive boundaries* of every bounded
//  wellhead-log field and the first step just beyond them, so a regression that
//  shifts a comparison from `<`/`>` to `<=`/`>=` (or vice versa) is caught by a
//  named example:
//
//    * each field accepts its exact inclusive minimum (0) and its exact
//      inclusive maximum (water cut 100; production fields 999,999.99; steam
//      injection pressure 25,000; steam temperature 400; steam flow rate
//      2,000); and
//    * each field rejects the smallest representable step beyond either bound
//      (e.g. `-0.01` below the minimum, `100.01` / `25000.01` / … above the
//      maximum) with exactly one `.outOfRange(min:max:)` error for that field.
//
//  Exact boundaries exercised: 0, 100, 25000, 400, 2000, 999999.99.
//
//  Requirements: 3.2, 3.4, 4.3.

import XCTest
@testable import SAGDWellTest

final class RangeBoundaryTests: XCTestCase {

    // MARK: - Harness

    /// A bounded numeric field under test: its stable identifier, the inclusive
    /// range it accepts, and a closure that substitutes a raw string into that
    /// field (all other fields held known-valid and in range) and returns the
    /// resulting `FieldError` list (empty when the whole form is valid).
    private struct BoundedField {
        let id: String
        let rangeMin: Double
        let rangeMax: Double
        let validate: (_ raw: String) -> [FieldError]
    }

    // Fixed, known-valid, in-range placeholder values for the "other" fields so
    // the only field that can ever fail is the one under test. "10" is
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

    /// All six bounded numeric fields across the two log validators, each paired
    /// with the inclusive range from `WellheadLogFieldRange`.
    private static func boundedFields() -> [BoundedField] {
        let production = ProductionLogValidator()
        let injection = InjectionLogValidator()

        return [
            // --- Production fields: [0.00, 999,999.99] (Req 3.4) ---
            BoundedField(
                id: WellheadLogField.emulsionFlowRate,
                rangeMin: WellheadLogFieldRange.productionMin,
                rangeMax: WellheadLogFieldRange.productionMax
            ) { raw in
                var input = validProduction
                input.emulsionFlowRate = raw
                return errors(production.validate(input))
            },
            BoundedField(
                id: WellheadLogField.casingGasPressure,
                rangeMin: WellheadLogFieldRange.productionMin,
                rangeMax: WellheadLogFieldRange.productionMax
            ) { raw in
                var input = validProduction
                input.casingGasPressure = raw
                return errors(production.validate(input))
            },
            BoundedField(
                id: WellheadLogField.espMotorTemperature,
                rangeMin: WellheadLogFieldRange.productionMin,
                rangeMax: WellheadLogFieldRange.productionMax
            ) { raw in
                var input = validProduction
                input.espMotorTemperature = raw
                return errors(production.validate(input))
            },
            // --- Water cut percentage: [0, 100] (Req 3.2) ---
            BoundedField(
                id: WellheadLogField.waterCutPercent,
                rangeMin: WellheadLogFieldRange.waterCutMin,
                rangeMax: WellheadLogFieldRange.waterCutMax
            ) { raw in
                var input = validProduction
                input.waterCutPercent = raw
                return errors(production.validate(input))
            },
            // --- Steam injection pressure: [0, 25,000] (Req 4.3) ---
            BoundedField(
                id: WellheadLogField.steamInjectionPressure,
                rangeMin: WellheadLogFieldRange.steamPressureMin,
                rangeMax: WellheadLogFieldRange.steamPressureMax
            ) { raw in
                var input = validInjection
                input.steamInjectionPressure = raw
                return errors(injection.validate(input))
            },
            // --- Steam temperature: [0, 400] (Req 4.3) ---
            BoundedField(
                id: WellheadLogField.steamTemperature,
                rangeMin: WellheadLogFieldRange.steamTemperatureMin,
                rangeMax: WellheadLogFieldRange.steamTemperatureMax
            ) { raw in
                var input = validInjection
                input.steamTemperature = raw
                return errors(injection.validate(input))
            },
            // --- Steam flow rate: [0, 2,000] (Req 4.3) ---
            BoundedField(
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

    /// Renders a boundary value as a well-formed numeric string under the
    /// accepted grammar, using up to two fractional digits so exact values like
    /// `999999.99` and `25000.01` round-trip without precision loss or exponent
    /// notation. Whole numbers render without a trailing `.00` is unnecessary;
    /// `%.2f` keeps a single, deterministic form the validator parses exactly.
    private static func numericString(_ value: Double) -> String {
        String(format: "%.2f", value)
    }

    // MARK: - Accepts exact inclusive boundaries

    /// Every bounded field accepts its exact inclusive minimum (0) and maximum
    /// (100 / 999,999.99 / 25,000 / 400 / 2,000), producing a valid form with
    /// no field errors at all.
    func testAcceptsExactInclusiveBoundaries() {
        for field in Self.boundedFields() {
            for boundary in [field.rangeMin, field.rangeMax] {
                let raw = Self.numericString(boundary)
                let errs = field.validate(raw)
                XCTAssertTrue(
                    errs.isEmpty,
                    "field \(field.id) must accept its inclusive boundary \(raw); got \(errs)"
                )
            }
        }
    }

    // MARK: - Rejects values just beyond the boundaries

    /// Every bounded field rejects the smallest step beyond either inclusive
    /// bound — `min - 0.01` and `max + 0.01` — with exactly one
    /// `.outOfRange(min:max:)` error naming that field and no other errors
    /// (every other field is held valid).
    func testRejectsValuesJustBeyondBoundaries() {
        let step = 0.01
        for field in Self.boundedFields() {
            let expected = FieldError(
                field: field.id,
                kind: .outOfRange(min: field.rangeMin, max: field.rangeMax)
            )

            let belowRaw = Self.numericString(field.rangeMin - step)  // e.g. "-0.01"
            XCTAssertEqual(
                field.validate(belowRaw), [expected],
                "field \(field.id) must reject just-below-min \(belowRaw) with a single outOfRange error"
            )

            let aboveRaw = Self.numericString(field.rangeMax + step)  // e.g. "100.01"
            XCTAssertEqual(
                field.validate(aboveRaw), [expected],
                "field \(field.id) must reject just-above-max \(aboveRaw) with a single outOfRange error"
            )
        }
    }

    // MARK: - Named boundary examples from the task

    /// Pins the specific maximum boundary values called out by the task —
    /// 100, 25000, 400, 2000, 999999.99 — plus the shared inclusive minimum 0,
    /// each accepted exactly and rejected one step beyond. These are spelled out
    /// as literal strings (independent of `WellheadLogFieldRange`) so the test
    /// fails loudly if a range constant is ever changed away from the
    /// requirement.
    func testNamedBoundaryExamples() {
        let production = ProductionLogValidator()
        let injection = InjectionLogValidator()

        func productionErrors(_ mutate: (inout ProductionLogInput) -> Void) -> [FieldError] {
            var input = Self.validProduction
            mutate(&input)
            return Self.errors(production.validate(input))
        }
        func injectionErrors(_ mutate: (inout InjectionLogInput) -> Void) -> [FieldError] {
            var input = Self.validInjection
            mutate(&input)
            return Self.errors(injection.validate(input))
        }

        // Shared inclusive minimum: 0 accepted, -0.01 rejected (water cut).
        XCTAssertTrue(productionErrors { $0.waterCutPercent = "0" }.isEmpty)
        XCTAssertEqual(
            productionErrors { $0.waterCutPercent = "-0.01" },
            [FieldError(field: WellheadLogField.waterCutPercent,
                        kind: .outOfRange(min: 0, max: 100))]
        )

        // Water cut maximum: 100 accepted, 100.01 rejected.
        XCTAssertTrue(productionErrors { $0.waterCutPercent = "100" }.isEmpty)
        XCTAssertEqual(
            productionErrors { $0.waterCutPercent = "100.01" },
            [FieldError(field: WellheadLogField.waterCutPercent,
                        kind: .outOfRange(min: 0, max: 100))]
        )

        // Production field maximum: 999999.99 accepted, 1000000.00 rejected.
        XCTAssertTrue(productionErrors { $0.emulsionFlowRate = "999999.99" }.isEmpty)
        XCTAssertEqual(
            productionErrors { $0.emulsionFlowRate = "1000000.00" },
            [FieldError(field: WellheadLogField.emulsionFlowRate,
                        kind: .outOfRange(min: 0, max: 999_999.99))]
        )

        // Steam injection pressure maximum: 25000 accepted, 25000.01 rejected.
        XCTAssertTrue(injectionErrors { $0.steamInjectionPressure = "25000" }.isEmpty)
        XCTAssertEqual(
            injectionErrors { $0.steamInjectionPressure = "25000.01" },
            [FieldError(field: WellheadLogField.steamInjectionPressure,
                        kind: .outOfRange(min: 0, max: 25_000))]
        )

        // Steam temperature maximum: 400 accepted, 400.01 rejected.
        XCTAssertTrue(injectionErrors { $0.steamTemperature = "400" }.isEmpty)
        XCTAssertEqual(
            injectionErrors { $0.steamTemperature = "400.01" },
            [FieldError(field: WellheadLogField.steamTemperature,
                        kind: .outOfRange(min: 0, max: 400))]
        )

        // Steam flow rate maximum: 2000 accepted, 2000.01 rejected.
        XCTAssertTrue(injectionErrors { $0.steamFlowRate = "2000" }.isEmpty)
        XCTAssertEqual(
            injectionErrors { $0.steamFlowRate = "2000.01" },
            [FieldError(field: WellheadLogField.steamFlowRate,
                        kind: .outOfRange(min: 0, max: 2_000))]
        )
    }
}
