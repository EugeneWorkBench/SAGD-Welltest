package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll

/**
 * Property 14: Subcool definition (Req 5.1).
 *
 * For any casing pressure inside the bundled steam-table domain and any finite
 * pump temperature, the estimator returns a [SubcoolResult.Success] whose
 * [SubcoolResult.Success.subcoolValue] equals the interpolated saturation
 * temperature at that pressure minus the pump temperature:
 *
 *     subcool == steamTable.saturationTemperature(pressure) - pumpTemperature
 *
 * This is the definitional identity of subcool. The test injects the bundled
 * table (shared verbatim with iOS) so the comparison is deterministic, draws
 * pressures strictly within `[minPressure, maxPressure]` so the result is always
 * a success (out-of-range pressures are covered by Property 17, task 11.5), and
 * draws arbitrary finite pump temperatures. It runs at least 100 iterations via
 * [pbtConfig].
 *
 * **Validates: Requirements 5.1**
 */
class SubcoolDefinitionPropertyTest : StringSpec({

    val table = SteamTable.bundled
    val estimator = SubcoolEstimator(table)

    // Absolute tolerance for the subcool comparison. Both sides are computed
    // from the same linear interpolation over identical double inputs, so the
    // only slack is floating-point rounding in the subtraction; 1e-9 is ample.
    val tolerance = 1e-9

    // Casing pressures strictly inside the table domain. A tiny inset off each
    // bound keeps every draw a valid, interpolable pressure (never out of range)
    // while still exercising the full operating span.
    val inset = 1e-6
    val pressures: Arb<Double> = Arb.double(
        min = table.minPressure + inset,
        max = table.maxPressure - inset,
    ).filter { it.isFinite() }

    // Arbitrary finite pump temperatures spanning well beyond the realistic
    // field range so the definitional identity is checked across the real line,
    // including negatives and values above the saturation temperature.
    val pumpTemperatures: Arb<Double> = Arb.double(
        min = -1_000.0,
        max = 1_000.0,
    ).filter { it.isFinite() }

    "Feature: sagd-welltest-mobile, Property 14: Subcool definition" {
        checkAll(pbtConfig(), pressures, pumpTemperatures) { pressure, pumpTemperature ->
            // The pressure is drawn inside the domain, so the table must resolve
            // a saturation temperature; this is the reference side of the identity.
            val tsat = table.saturationTemperature(pressure)
                ?: error("pressure $pressure unexpectedly outside the steam-table domain")

            val result = estimator.estimate(
                casingPressure = pressure,
                pumpTemperature = pumpTemperature,
            )

            result.shouldBeInstanceOf<SubcoolResult.Success>()
            result.subcoolValue shouldBe ((tsat - pumpTemperature) plusOrMinus tolerance)
        }
    }
})
