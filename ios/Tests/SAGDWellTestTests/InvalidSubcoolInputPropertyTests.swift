//
//  InvalidSubcoolInputPropertyTests.swift
//  Property 18: Invalid subcool input rejection (iOS) — task 8.6.
//
//  Validates: Requirements 5.5
//
//  Requirement 5.5: if an Operator submits an empty or non-numeric value for
//  casing pressure or pump temperature, the Subcool_Estimator rejects the
//  submission and names the invalid field. This property asserts that rejection
//  directly against the String-input `estimate(casingPressure:pumpTemperature:)`
//  overload, which validates the as-entered fields at the boundary.
//
//  Two complementary properties cover the two fields, with the estimator's
//  stated validation order (casing pressure is checked first) in mind:
//
//    1. For any empty / whitespace-only / non-numeric casing pressure paired
//       with an arbitrary pump-temperature string, the result is
//       `.invalidInput(field: "casingPressure")` — regardless of whether the
//       pump temperature is itself valid or invalid, because casing pressure is
//       validated first.
//
//    2. For a valid (numeric) casing pressure paired with any empty /
//       whitespace-only / non-numeric pump temperature, the result is
//       `.invalidInput(field: "pumpTemperature")`. A valid casing pressure lets
//       validation advance to the pump-temperature check; the numeric casing
//       string need not lie inside the steam-table domain because input
//       validation of both fields happens before the domain check.
//
//  The estimator is pure value logic over the injected bundled steam table, so
//  the test needs no persistence, networking, or UI. It runs a minimum of 100
//  iterations via `pbtArguments()` and is tagged through `propertyName(_:_:)`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class InvalidSubcoolInputPropertyTests: XCTestCase {

    /// The estimator under test, using the bundled steam table. The flashing
    /// threshold is irrelevant to this property — every expected outcome is an
    /// `.invalidInput` rejection that never reaches the subcool computation.
    private let estimator = SubcoolEstimator(steamTable: .bundled)

    // MARK: - Property

    func testInvalidSubcoolInputRejection() {

        // Case 1: an invalid casing pressure is rejected as `casingPressure`,
        // independent of the pump-temperature string, because casing pressure
        // is validated first.
        property(
            propertyName(18, "Invalid subcool input rejection"),
            arguments: pbtArguments()
        ) <- forAll(Self.invalidNumericStringGenerator(), Self.anyPumpTemperatureStringGenerator()) {
            (casingPressure: String, pumpTemperature: String) in

            let result = self.estimator.estimate(
                casingPressure: casingPressure,
                pumpTemperature: pumpTemperature
            )

            return (result == .invalidInput(field: SubcoolEstimator.Field.casingPressure))
                <?> "expected .invalidInput(casingPressure) for invalid casing pressure "
                    + "\(Self.describe(casingPressure)) (pump temp \(Self.describe(pumpTemperature))), "
                    + "got \(result)"
        }

        // Case 2: with a valid (numeric) casing pressure, an invalid pump
        // temperature is rejected as `pumpTemperature`.
        property(
            propertyName(18, "Invalid subcool input rejection"),
            arguments: pbtArguments()
        ) <- forAll(Self.validNumericStringGenerator(), Self.invalidNumericStringGenerator()) {
            (casingPressure: String, pumpTemperature: String) in

            let result = self.estimator.estimate(
                casingPressure: casingPressure,
                pumpTemperature: pumpTemperature
            )

            return (result == .invalidInput(field: SubcoolEstimator.Field.pumpTemperature))
                <?> "expected .invalidInput(pumpTemperature) for valid casing pressure "
                    + "\(Self.describe(casingPressure)) and invalid pump temp "
                    + "\(Self.describe(pumpTemperature)), got \(result)"
        }
    }

    // MARK: - Generators

    /// Generates strings the estimator must reject as empty or non-numeric: the
    /// empty string, whitespace-only strings, and strings containing at least
    /// one non-numeric character. The three sub-generators are drawn with equal
    /// weight so each rejection cause is well represented.
    private static func invalidNumericStringGenerator() -> Gen<String> {
        Gen<String>.one(of: [
            emptyStringGenerator(),
            whitespaceOnlyGenerator(),
            nonNumericStringGenerator(),
        ])
    }

    /// The empty string — rejected because, after trimming, it has no digits.
    private static func emptyStringGenerator() -> Gen<String> {
        // A single-element generator; `map` onto a fixed value keeps the API to
        // the subset used elsewhere in this test target.
        Gen<Character>.fromElements(of: [" "]).map { _ in "" }
    }

    /// Generates non-empty strings composed solely of whitespace characters;
    /// after the estimator trims surrounding whitespace these are empty.
    private static func whitespaceOnlyGenerator() -> Gen<String> {
        Gen<Character>.fromElements(of: [" ", "\t", "\n"])
            .proliferateNonEmpty
            .suchThat { $0.count <= 5 }
            .map { String($0) }
    }

    /// Generates non-numeric strings: number-like strings with at least one
    /// character the numeric format forbids (anything other than digits, a
    /// single leading `-`, and at most one `.`). A guaranteed non-numeric
    /// character is always injected, so the generator never accidentally
    /// produces a valid number.
    private static func nonNumericStringGenerator() -> Gen<String> {
        // Characters that are never part of a valid numeric string.
        let nonNumericChar = Gen<Character>.fromElements(
            of: Array("aZxX+$%efF,;:/?*gh")
        )
        // Filler drawn from characters that *are* legal in isolation, so the
        // result looks number-like but is still rejected (e.g. "12a", "3.4x").
        let filler = Gen<Character>.fromElements(of: Array("0123456789.-"))
            .proliferate
            .suchThat { $0.count <= 5 }
            .map { String($0) }

        return Gen<(String, Character)>.zip(filler, nonNumericChar).flatMap { pair in
            let (prefix, bad) = pair
            // Append more optional filler after the bad character so the bad
            // character appears in varied positions across cases.
            return filler.map { suffix in prefix + String(bad) + suffix }
        }
    }

    /// Generates valid numeric strings in the estimator's accepted format:
    /// optional leading minus, digits, and at most one decimal point with a
    /// digit present. Values are intentionally unconstrained relative to the
    /// steam-table domain — input validation of both fields precedes the domain
    /// check, so any parseable number lets validation reach the pump field.
    private static func validNumericStringGenerator() -> Gen<String> {
        let sign = Gen<String>.fromElements(of: ["", "-"])
        let digits = Gen<Character>.fromElements(of: Array("0123456789"))
            .proliferateNonEmpty
            .suchThat { $0.count <= 6 }
            .map { String($0) }

        let integer = Gen<(String, String)>.zip(sign, digits).map { $0 + $1 }
        let decimal = Gen<(String, String)>.zip(sign, digits).flatMap { signPart, whole in
            digits.map { frac in "\(signPart)\(whole).\(frac)" }
        }

        return Gen<String>.one(of: [integer, decimal])
    }

    /// Generates an arbitrary pump-temperature string for case 1: a mix of
    /// valid numeric strings and invalid (empty / whitespace / non-numeric)
    /// strings. Either way the casing-pressure rejection must take precedence,
    /// so this exercises both "both fields invalid" and "only casing invalid".
    private static func anyPumpTemperatureStringGenerator() -> Gen<String> {
        Gen<String>.one(of: [
            validNumericStringGenerator(),
            invalidNumericStringGenerator(),
        ])
    }

    // MARK: - Diagnostics

    /// Renders a string for failure messages with whitespace made visible, so a
    /// counterexample like a lone tab or trailing space is legible.
    private static func describe(_ value: String) -> String {
        let visible = value
            .replacingOccurrences(of: "\t", with: "\\t")
            .replacingOccurrences(of: "\n", with: "\\n")
        return "\"\(visible)\""
    }
}
