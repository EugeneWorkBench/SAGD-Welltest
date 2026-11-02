//
//  SubcoolDefinitionPropertyTests.swift
//  Property 14: Subcool definition (iOS) — task 8.2.
//
//  Validates: Requirements 5.1
//
//  Requirement 5.1 defines the subcool value as the difference, in degrees
//  Celsius, between the Steam_Table saturation temperature at the submitted
//  casing pressure and the submitted pump temperature. This property asserts
//  that definition directly against `SubcoolEstimator`: for any casing pressure
//  inside the bundled steam-table domain and any pump temperature, a `.success`
//  result's subcool equals
//
//      steamTable.saturationTemperature(pressure) - pumpTemperature
//
//  within a floating-point tolerance.
//
//  The estimator is pure value logic over the injected bundled steam table, so
//  the test needs no persistence, networking, or UI. It runs a minimum of 100
//  iterations via `pbtArguments()` and is tagged through `propertyName(_:_:)`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class SubcoolDefinitionPropertyTests: XCTestCase {

    /// The estimator under test, using the bundled steam table. The flashing
    /// threshold is irrelevant to this property (it only affects the
    /// `isFlashing` flag, not the subcool value) so the default is fine.
    private let estimator = SubcoolEstimator(steamTable: .bundled)

    /// Absolute tolerance for comparing the estimator's subcool against the
    /// independently recomputed definition. Both sides perform the same linear
    /// interpolation and a single subtraction, so the only difference is
    /// floating-point rounding; a small epsilon scaled to the magnitude of the
    /// values comfortably covers it.
    private let tolerance = 1e-9

    func testSubcoolDefinition() {
        let table = SteamTable.bundled

        property(
            propertyName(14, "Subcool definition"),
            arguments: pbtArguments()
        ) <- forAll(pressureInDomainGenerator(table), pumpTemperatureGenerator()) {
            (pressure: Double, pumpTemperature: Double) in

            // The estimator's numeric core: both inputs are already valid
            // numbers, so the only outcome inside the domain is `.success`.
            let result = self.estimator.estimate(
                casingPressure: pressure,
                pumpTemperature: pumpTemperature
            )

            // Independently recompute the requirement-5.1 definition from the
            // same injected table. A pressure drawn from the in-domain
            // generator always has a saturation temperature.
            guard let tsat = table.saturationTemperature(pressure: pressure) else {
                return false
                    <?> "in-domain pressure \(pressure) unexpectedly had no saturation temperature"
            }
            let expectedSubcool = tsat - pumpTemperature

            guard case let .success(subcool, _) = result else {
                return false
                    <?> "expected .success for in-domain pressure \(pressure), got \(result)"
            }

            // Scale the tolerance to the magnitude of the compared values so
            // the comparison stays meaningful for large pump temperatures.
            let scale = max(1.0, abs(expectedSubcool))
            let withinTolerance = abs(subcool - expectedSubcool) <= self.tolerance * scale

            return withinTolerance
                <?> "subcool \(subcool) != Tsat(\(tsat)) - Tpump(\(pumpTemperature)) = \(expectedSubcool)"
        }
    }

    // MARK: - Generators

    /// Generates casing pressures uniformly within the closed steam-table
    /// domain `[minPressure, maxPressure]`, including the exact endpoints so the
    /// boundary brackets are exercised.
    ///
    /// Staying strictly inside the domain guarantees the estimator returns
    /// `.success` (never `.outOfRange`), which is exactly the case this
    /// property is about.
    private func pressureInDomainGenerator(_ table: SteamTable) -> Gen<Double> {
        // Draw a fraction in [0, 1] and map it across the domain so every
        // in-domain pressure — including the endpoints — is reachable.
        return Gen<Double>.choose((0.0, 1.0)).map { fraction in
            table.minPressure + fraction * (table.maxPressure - table.minPressure)
        }
    }

    /// Generates arbitrary pump temperatures across a wide, finite range.
    ///
    /// Requirement 5.1 places no bound on the pump temperature for the
    /// definition itself — the subcool is simply `Tsat - Tpump` — so the range
    /// spans well below freezing to well above the table's top saturation
    /// temperature, producing both positive and negative subcool values.
    private func pumpTemperatureGenerator() -> Gen<Double> {
        return Gen<Double>.choose((-50.0, 400.0))
    }
}
