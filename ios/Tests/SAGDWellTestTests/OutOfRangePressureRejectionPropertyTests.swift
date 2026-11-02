//
//  OutOfRangePressureRejectionPropertyTests.swift
//  Property 17: Out-of-range pressure rejection (iOS) — task 8.5.
//
//  Validates: Requirements 5.4
//
//  Requirement 5.4: IF a submitted casing pressure falls outside the range
//  covered by the Steam_Table, THEN the Subcool_Estimator SHALL reject the
//  submission (and name the offending field). The complement — a pressure
//  inside the domain, paired with a valid pump temperature, produces a value —
//  is the behaviour that gives the rejection meaning: the estimator rejects
//  exactly the out-of-domain pressures, not everything.
//
//  This property asserts both directions directly against `SubcoolEstimator`,
//  driven through its string-based `estimate(casingPressure:pumpTemperature:)`
//  submission entry point (the same path the UI uses):
//
//    * A pressure strictly below `minPressure` or strictly above `maxPressure`,
//      with a valid numeric pump temperature, yields
//      `.outOfRange(field: "casingPressure")`.
//    * A pressure inside `[minPressure, maxPressure]`, with a valid numeric
//      pump temperature, yields `.success`.
//
//  The estimator is pure value logic over the injected bundled steam table, so
//  the test needs no persistence, networking, or UI. It runs a minimum of 100
//  iterations via `pbtArguments()` and is tagged through `propertyName(_:_:)`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class OutOfRangePressureRejectionPropertyTests: XCTestCase {

    /// The estimator under test, using the bundled steam table. The flashing
    /// threshold only affects the `isFlashing` flag of a `.success`; this
    /// property cares only about which result *case* is produced, so the
    /// default threshold is fine.
    private let estimator = SubcoolEstimator(steamTable: .bundled)

    /// Field identifier the estimator names when it rejects a pressure outside
    /// the steam-table domain (Req 5.4).
    private let casingPressureField = SubcoolEstimator.Field.casingPressure

    // MARK: - Out-of-range pressures reject

    /// A casing pressure outside the steam-table domain (below `minPressure` or
    /// above `maxPressure`), submitted with a valid pump temperature, is
    /// rejected as `.outOfRange` naming the casing-pressure field.
    func testOutOfRangePressureIsRejected() {
        let table = SteamTable.bundled

        property(
            propertyName(17, "Out-of-range pressure rejection"),
            arguments: pbtArguments()
        ) <- forAll(
            pressureOutOfDomainGenerator(table),
            pumpTemperatureGenerator()
        ) { (pressure: Double, pumpTemperature: Double) in

            let result = self.estimator.estimate(
                casingPressure: Self.numericString(pressure),
                pumpTemperature: Self.numericString(pumpTemperature)
            )

            guard case let .outOfRange(field) = result else {
                return false
                    <?> "expected .outOfRange for out-of-domain pressure \(pressure), got \(result)"
            }

            return (field == self.casingPressureField)
                <?> "out-of-range field was \(field), expected \(self.casingPressureField)"
        }
    }

    // MARK: - In-domain pressures produce a value

    /// A casing pressure inside the steam-table domain, submitted with a valid
    /// pump temperature, produces a `.success` value (never `.outOfRange` and
    /// never `.invalidInput`, since both inputs are well-formed numbers).
    func testInDomainPressureProducesValue() {
        let table = SteamTable.bundled

        property(
            propertyName(17, "Out-of-range pressure rejection"),
            arguments: pbtArguments()
        ) <- forAll(
            pressureInDomainGenerator(table),
            pumpTemperatureGenerator()
        ) { (pressure: Double, pumpTemperature: Double) in

            let result = self.estimator.estimate(
                casingPressure: Self.numericString(pressure),
                pumpTemperature: Self.numericString(pumpTemperature)
            )

            guard case .success = result else {
                return false
                    <?> "expected .success for in-domain pressure \(pressure), got \(result)"
            }

            return true <?> "in-domain pressure \(pressure) produced a value"
        }
    }

    // MARK: - Generators

    /// Generates casing pressures strictly outside the closed steam-table
    /// domain `[minPressure, maxPressure]` — half below `minPressure`, half
    /// above `maxPressure` — so both out-of-range directions are exercised.
    ///
    /// The lower band stops just short of `minPressure` and the upper band
    /// starts just beyond `maxPressure`, so every generated value is genuinely
    /// out of domain (the domain endpoints themselves are in-domain and belong
    /// to the other generator).
    private func pressureOutOfDomainGenerator(_ table: SteamTable) -> Gen<Double> {
        // A small margin keeps the "just outside" values clearly out of domain
        // without relying on exact floating-point equality at the endpoints.
        let epsilon = 1e-6

        // Below the domain: from a sensible floor up to just under minPressure.
        // The floor stays non-negative since pressure is a physical quantity,
        // but we still probe the near-zero region where the table starts.
        let below = Gen<Double>.choose((0.0, table.minPressure - epsilon))

        // Above the domain: from just over maxPressure to well beyond it, so a
        // wide band of too-high pressures is covered.
        let above = Gen<Double>.choose((table.maxPressure + epsilon, table.maxPressure * 3.0))

        return Gen<Double>.one(of: [below, above])
    }

    /// Generates casing pressures uniformly within the closed steam-table
    /// domain `[minPressure, maxPressure]`, including the exact endpoints so the
    /// boundary brackets are exercised. Every value here is in-domain, so the
    /// estimator returns a `.success`.
    private func pressureInDomainGenerator(_ table: SteamTable) -> Gen<Double> {
        return Gen<Double>.choose((0.0, 1.0)).map { fraction in
            table.minPressure + fraction * (table.maxPressure - table.minPressure)
        }
    }

    /// Generates valid pump temperatures across a wide, finite range. The pump
    /// temperature does not affect which result case the estimator returns (a
    /// valid number never trips `.invalidInput`, and the domain check is on
    /// pressure only), so any well-formed numeric value works.
    private func pumpTemperatureGenerator() -> Gen<Double> {
        return Gen<Double>.choose((-50.0, 400.0))
    }

    // MARK: - Helpers

    /// Formats a generated `Double` as a plain numeric string the estimator's
    /// numeric-format rule accepts (optional leading minus, decimal digits, at
    /// most one decimal point). Using the string entry point exercises the real
    /// submission path end to end; a `%f` representation never introduces
    /// grouping separators or exponent notation.
    private static func numericString(_ value: Double) -> String {
        String(format: "%.6f", value)
    }
}
