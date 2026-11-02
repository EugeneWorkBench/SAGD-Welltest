//
//  SubcoolMonotonicityPropertyTests.swift
//  Property 15: Subcool monotonicity (iOS) — task 8.3.
//
//  Validates: Requirements 5.1
//
//  Requirement 5.1 defines the subcool value as
//
//      subcool(P, Tpump) = Tsat(P) - Tpump
//
//  where `Tsat` is the Steam_Table saturation temperature at the casing
//  pressure `P`. Two monotonicity facts follow directly from that definition
//  and from the steam table being monotonic non-decreasing in pressure:
//
//    1. Holding the pump temperature fixed, subcool is NON-DECREASING in
//       casing pressure: if `p1 <= p2` then `subcool(p2) >= subcool(p1)`,
//       because `Tsat` never decreases as pressure rises.
//
//    2. Holding the casing pressure fixed, subcool is STRICTLY DECREASING in
//       pump temperature: if `t1 < t2` then `subcool(t2) < subcool(t1)`,
//       because the pump temperature is subtracted — raising it strictly
//       lowers the subcool by the same amount.
//
//  Both sub-properties are asserted against `SubcoolEstimator` over the bundled
//  `SteamTable`. The estimator is pure value logic over the injected table, so
//  the test needs no persistence, networking, or UI. Each sub-property runs a
//  minimum of 100 iterations via `pbtArguments()` and is tagged through
//  `propertyName(_:_:)`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class SubcoolMonotonicityPropertyTests: XCTestCase {

    /// The estimator under test, using the bundled steam table. The flashing
    /// threshold only affects the `isFlashing` flag, not the subcool value this
    /// property reasons about, so the default is fine.
    private let estimator = SubcoolEstimator(steamTable: .bundled)

    /// Absolute tolerance, scaled by magnitude, for the non-strict comparison.
    ///
    /// The non-decreasing-in-pressure direction compares two independently
    /// interpolated saturation temperatures; floating-point rounding can make
    /// an equal pair differ by a few ULPs. A small scaled epsilon keeps the
    /// `>=` comparison from flagging those as violations while still catching a
    /// genuine decrease.
    private let tolerance = 1e-9

    // MARK: - Non-decreasing in casing pressure (fixed pump temperature)

    /// For any two in-domain casing pressures `p1 <= p2` evaluated at a single
    /// fixed pump temperature, the subcool at the higher pressure is at least
    /// the subcool at the lower pressure.
    func testSubcoolNonDecreasingInPressure() {
        let table = SteamTable.bundled

        property(
            propertyName(15, "Subcool monotonicity"),
            arguments: pbtArguments()
        ) <- forAll(
            orderedPressurePairGenerator(table),
            pumpTemperatureGenerator()
        ) { (pair: (Double, Double), pumpTemperature: Double) in
            let (p1, p2) = pair  // p1 <= p2 by construction

            let r1 = self.estimator.estimate(
                casingPressure: p1,
                pumpTemperature: pumpTemperature
            )
            let r2 = self.estimator.estimate(
                casingPressure: p2,
                pumpTemperature: pumpTemperature
            )

            // Both pressures are drawn from the in-domain generator, so both
            // estimates must succeed.
            guard case let .success(subcool1, _) = r1 else {
                return false
                    <?> "expected .success for in-domain pressure \(p1), got \(r1)"
            }
            guard case let .success(subcool2, _) = r2 else {
                return false
                    <?> "expected .success for in-domain pressure \(p2), got \(r2)"
            }

            // Non-decreasing: subcool(p2) >= subcool(p1), up to rounding.
            let scale = max(1.0, abs(subcool1), abs(subcool2))
            let nonDecreasing = subcool2 >= subcool1 - self.tolerance * scale

            return nonDecreasing
                <?> "subcool at higher pressure \(p2) (=\(subcool2)) < subcool at lower pressure \(p1) (=\(subcool1)) at fixed Tpump=\(pumpTemperature)"
        }
    }

    // MARK: - Strictly decreasing in pump temperature (fixed pressure)

    /// For any single in-domain casing pressure and any two pump temperatures
    /// `t1 < t2`, the subcool strictly decreases as the pump temperature rises.
    ///
    /// This direction is exact: at a fixed pressure `Tsat(P)` is a constant, so
    /// `subcool(t1) - subcool(t2) = t2 - t1 > 0` with no interpolation
    /// difference between the two sides. The assertion is therefore strict
    /// (`<`) with no tolerance.
    func testSubcoolStrictlyDecreasingInPumpTemperature() {
        let table = SteamTable.bundled

        property(
            propertyName(15, "Subcool monotonicity"),
            arguments: pbtArguments()
        ) <- forAll(
            pressureInDomainGenerator(table),
            orderedDistinctPumpTemperaturePairGenerator()
        ) { (pressure: Double, pair: (Double, Double)) in
            let (t1, t2) = pair  // t1 < t2 by construction

            let r1 = self.estimator.estimate(
                casingPressure: pressure,
                pumpTemperature: t1
            )
            let r2 = self.estimator.estimate(
                casingPressure: pressure,
                pumpTemperature: t2
            )

            guard case let .success(subcool1, _) = r1 else {
                return false
                    <?> "expected .success for in-domain pressure \(pressure), got \(r1)"
            }
            guard case let .success(subcool2, _) = r2 else {
                return false
                    <?> "expected .success for in-domain pressure \(pressure), got \(r2)"
            }

            // Strictly decreasing: the higher pump temperature yields the
            // strictly smaller subcool.
            let strictlyDecreasing = subcool2 < subcool1

            return strictlyDecreasing
                <?> "subcool at higher Tpump=\(t2) (=\(subcool2)) not strictly less than subcool at lower Tpump=\(t1) (=\(subcool1)) at fixed pressure \(pressure)"
        }
    }

    // MARK: - Generators

    /// Generates a pair of in-domain casing pressures ordered so the first is
    /// no greater than the second (`p1 <= p2`), both drawn uniformly across the
    /// closed steam-table domain `[minPressure, maxPressure]` including the
    /// exact endpoints.
    private func orderedPressurePairGenerator(_ table: SteamTable) -> Gen<(Double, Double)> {
        let single = pressureInDomainGenerator(table)
        return Gen<(Double, Double)>.zip(single, single).map { a, b in
            a <= b ? (a, b) : (b, a)
        }
    }

    /// Generates a single in-domain casing pressure uniformly across the closed
    /// steam-table domain, including the endpoints so the boundary brackets are
    /// exercised.
    private func pressureInDomainGenerator(_ table: SteamTable) -> Gen<Double> {
        return Gen<Double>.choose((0.0, 1.0)).map { fraction in
            table.minPressure + fraction * (table.maxPressure - table.minPressure)
        }
    }

    /// Generates a single pump temperature across a wide, finite range.
    ///
    /// Requirement 5.1 places no bound on the pump temperature for the subcool
    /// definition, so the range spans well below freezing to above the table's
    /// top saturation temperature, producing both positive and negative subcool
    /// values.
    private func pumpTemperatureGenerator() -> Gen<Double> {
        return Gen<Double>.choose((-50.0, 400.0))
    }

    /// Generates a pair of DISTINCT pump temperatures ordered strictly
    /// ascending (`t1 < t2`).
    ///
    /// Two independent draws are combined; when they collide, the second is
    /// nudged up by a positive delta so the pair is always strictly ordered and
    /// no iteration is wasted on an equal pair (which the strict `<` property
    /// does not cover).
    private func orderedDistinctPumpTemperaturePairGenerator() -> Gen<(Double, Double)> {
        let single = pumpTemperatureGenerator()
        return Gen<(Double, Double)>.zip(single, single).map { a, b in
            if a < b { return (a, b) }
            if b < a { return (b, a) }
            // Equal draw: separate them so t1 < t2 strictly.
            return (a, a + 1.0)
        }
    }
}
