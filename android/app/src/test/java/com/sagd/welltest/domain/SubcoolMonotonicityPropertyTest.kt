package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

/**
 * Property 15: Subcool monotonicity (Req 5.1).
 *
 * Mirrors the iOS `SubcoolMonotonicityPropertyTests`. Requirement 5.1 defines
 * the subcool value as
 *
 *     subcool(P, Tpump) = Tsat(P) - Tpump
 *
 * where `Tsat` is the Steam_Table saturation temperature at the casing pressure
 * `P`. Two monotonicity facts follow directly from that definition and from the
 * steam table being monotonic non-decreasing in pressure:
 *
 *   1. Holding the pump temperature fixed, subcool is NON-DECREASING in casing
 *      pressure: if `p1 <= p2` then `subcool(p2) >= subcool(p1)`, because
 *      `Tsat` never decreases as pressure rises.
 *
 *   2. Holding the casing pressure fixed, subcool is STRICTLY DECREASING in pump
 *      temperature: if `t1 < t2` then `subcool(t2) < subcool(t1)`, because the
 *      pump temperature is subtracted — raising it strictly lowers the subcool
 *      by the same amount.
 *
 * Both sub-properties are asserted against [SubcoolEstimator] over the bundled
 * [SteamTable] (shared verbatim with iOS). The estimator is pure value logic
 * over the injected table, so the test needs no persistence, networking, or UI.
 * Each sub-property runs at least 100 iterations via [pbtConfig].
 *
 * **Validates: Requirements 5.1**
 */
class SubcoolMonotonicityPropertyTest : StringSpec({

    val table = SteamTable.bundled
    val estimator = SubcoolEstimator(table)

    // Absolute tolerance, scaled by magnitude, for the non-strict comparison.
    //
    // The non-decreasing-in-pressure direction compares two independently
    // interpolated saturation temperatures; floating-point rounding can make an
    // equal pair differ by a few ULPs. A small scaled epsilon keeps the `>=`
    // comparison from flagging those as violations while still catching a
    // genuine decrease.
    val tolerance = 1e-9

    // A single casing pressure drawn uniformly across the closed steam-table
    // domain [minPressure, maxPressure], endpoints included so the boundary
    // brackets are exercised.
    val pressureInDomain: Arb<Double> = Arb.double(min = 0.0, max = 1.0)
        .filter { it.isFinite() }
        .map { fraction -> table.minPressure + fraction * (table.maxPressure - table.minPressure) }

    // A pair of in-domain casing pressures ordered so the first is no greater
    // than the second (p1 <= p2).
    val orderedPressurePair: Arb<Pair<Double, Double>> =
        Arb.bind(pressureInDomain, pressureInDomain) { a, b ->
            if (a <= b) a to b else b to a
        }

    // A single pump temperature across a wide, finite range. Requirement 5.1
    // places no bound on the pump temperature for the subcool definition, so the
    // range spans well below freezing to above the table's top saturation
    // temperature, producing both positive and negative subcool values.
    val pumpTemperature: Arb<Double> = Arb.double(min = -50.0, max = 400.0)
        .filter { it.isFinite() }

    // A pair of DISTINCT pump temperatures ordered strictly ascending (t1 < t2).
    // When two draws collide, the second is nudged up by a positive delta so the
    // pair is always strictly ordered and no iteration is wasted on an equal pair
    // (which the strict `<` property does not cover).
    val orderedDistinctPumpTemperaturePair: Arb<Pair<Double, Double>> =
        Arb.bind(pumpTemperature, pumpTemperature) { a, b ->
            when {
                a < b -> a to b
                b < a -> b to a
                else -> a to (a + 1.0)
            }
        }

    // Non-decreasing in casing pressure (fixed pump temperature): for any two
    // in-domain pressures p1 <= p2 at a single fixed pump temperature, the
    // subcool at the higher pressure is at least the subcool at the lower one.
    "Feature: sagd-welltest-mobile, Property 15: Subcool monotonicity (non-decreasing in pressure)" {
        checkAll(pbtConfig(), orderedPressurePair, pumpTemperature) { pair, pumpTemp ->
            val (p1, p2) = pair // p1 <= p2 by construction

            val r1 = estimator.estimate(casingPressure = p1, pumpTemperature = pumpTemp)
            val r2 = estimator.estimate(casingPressure = p2, pumpTemperature = pumpTemp)

            // Both pressures are drawn from the in-domain generator, so both
            // estimates must succeed.
            r1.shouldBeInstanceOf<SubcoolResult.Success>()
            r2.shouldBeInstanceOf<SubcoolResult.Success>()

            val subcool1 = r1.subcoolValue
            val subcool2 = r2.subcoolValue

            // Non-decreasing: subcool(p2) >= subcool(p1), up to rounding.
            val scale = maxOf(1.0, kotlin.math.abs(subcool1), kotlin.math.abs(subcool2))
            (subcool2 >= subcool1 - tolerance * scale) shouldBe true
        }
    }

    // Strictly decreasing in pump temperature (fixed pressure): for any single
    // in-domain pressure and any two pump temperatures t1 < t2, the subcool
    // strictly decreases as the pump temperature rises. This direction is exact:
    // at a fixed pressure Tsat(P) is constant, so
    // subcool(t1) - subcool(t2) = t2 - t1 > 0 with no interpolation difference
    // between the two sides. The assertion is therefore strict (`<`) with no
    // tolerance.
    "Feature: sagd-welltest-mobile, Property 15: Subcool monotonicity (strictly decreasing in pump temperature)" {
        checkAll(pbtConfig(), pressureInDomain, orderedDistinctPumpTemperaturePair) { pressure, pair ->
            val (t1, t2) = pair // t1 < t2 by construction

            val r1 = estimator.estimate(casingPressure = pressure, pumpTemperature = t1)
            val r2 = estimator.estimate(casingPressure = pressure, pumpTemperature = t2)

            r1.shouldBeInstanceOf<SubcoolResult.Success>()
            r2.shouldBeInstanceOf<SubcoolResult.Success>()

            // Strictly decreasing: the higher pump temperature yields the
            // strictly smaller subcool.
            (r2.subcoolValue < r1.subcoolValue) shouldBe true
        }
    }
})
