//
//  FlashingZoneClassificationPropertyTests.swift
//  Property 16: Flashing-zone classification (iOS) — task 8.4.
//
//  Validates: Requirements 5.3
//
//  Requirement 5.3 classifies a result as flashing when the calculated subcool
//  value is at or below the configured flashing-zone threshold. This property
//  asserts that boundary directly against `SubcoolEstimator`: for any injected
//  `flashingThreshold`, any casing pressure inside the bundled steam-table
//  domain, and any pump temperature, a `.success` result's `isFlashing` flag is
//  true if and only if its subcool value is less than or equal to the injected
//  threshold.
//
//      isFlashing == (subcool <= flashingThreshold)
//
//  The estimator is pure value logic over the injected bundled steam table and
//  the injected threshold, so the test needs no persistence, networking, or UI.
//  It runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  through `propertyName(_:_:)`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class FlashingZoneClassificationPropertyTests: XCTestCase {

    func testFlashingZoneClassification() {
        let table = SteamTable.bundled

        property(
            propertyName(16, "Flashing-zone classification"),
            arguments: pbtArguments()
        ) <- forAll(
            flashingThresholdGenerator(),
            pressureInDomainGenerator(table),
            pumpTemperatureGenerator()
        ) { (threshold: Double, pressure: Double, pumpTemperature: Double) in

            // Inject the generated flashing threshold so the classification
            // boundary itself is under test, not a fixed default.
            let estimator = SubcoolEstimator(
                steamTable: table,
                flashingThreshold: threshold
            )

            let result = estimator.estimate(
                casingPressure: pressure,
                pumpTemperature: pumpTemperature
            )

            // An in-domain pressure with valid numeric inputs always yields
            // `.success`; anything else means the estimator misclassified a
            // valid submission.
            guard case let .success(subcool, isFlashing) = result else {
                return false
                    <?> "expected .success for in-domain pressure \(pressure), got \(result)"
            }

            // The requirement-5.3 definition: flashing iff subcool is at or
            // below the configured threshold.
            let expectedFlashing = subcool <= threshold

            return (isFlashing == expectedFlashing)
                <?> "isFlashing \(isFlashing) != (subcool \(subcool) <= threshold \(threshold)) = \(expectedFlashing)"
        }
    }

    // MARK: - Generators

    /// Generates flashing thresholds (°C) across a wide finite range spanning
    /// well below and well above the subcool values the estimator can produce,
    /// so the generated subcool lands on both sides of the boundary and the
    /// `<=` comparison — including near-equality — is exercised.
    private func flashingThresholdGenerator() -> Gen<Double> {
        return Gen<Double>.choose((-100.0, 300.0))
    }

    /// Generates casing pressures uniformly within the closed steam-table
    /// domain `[minPressure, maxPressure]`, including the exact endpoints so the
    /// boundary brackets are exercised.
    ///
    /// Staying strictly inside the domain guarantees the estimator returns
    /// `.success` (never `.outOfRange`), which is exactly the case this
    /// property is about.
    private func pressureInDomainGenerator(_ table: SteamTable) -> Gen<Double> {
        return Gen<Double>.choose((0.0, 1.0)).map { fraction in
            table.minPressure + fraction * (table.maxPressure - table.minPressure)
        }
    }

    /// Generates arbitrary pump temperatures across a wide, finite range so the
    /// resulting subcool spans both positive and negative values relative to
    /// any generated threshold.
    private func pumpTemperatureGenerator() -> Gen<Double> {
        return Gen<Double>.choose((-50.0, 400.0))
    }
}
