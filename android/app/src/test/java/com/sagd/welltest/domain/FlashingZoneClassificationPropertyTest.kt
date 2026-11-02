/*
 * Property 16: Flashing-zone classification (Android) — task 11.4.
 *
 * Validates: Requirements 5.3
 *
 * Requirement 5.3 classifies a subcool result as flashing when the calculated
 * subcool value is at or below the configured flashing-zone threshold. This
 * property asserts that boundary directly against [SubcoolEstimator]: for any
 * injected `flashingThreshold`, any casing pressure inside the bundled
 * steam-table domain, and any pump temperature, a [SubcoolResult.Success]'s
 * `isFlashing` flag is true if and only if its `subcoolValue` is less than or
 * equal to the injected threshold.
 *
 *     isFlashing == (subcoolValue <= flashingThreshold)
 *
 * The estimator is pure value logic over the injected bundled steam table and
 * the injected threshold, so the test needs no persistence, networking, or UI.
 * It runs a minimum of 100 iterations via [pbtConfig] and mirrors the iOS
 * FlashingZoneClassificationPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll

class FlashingZoneClassificationPropertyTest : StringSpec({

    val table = SteamTable.bundled

    // Flashing thresholds (°C) across a wide finite range spanning well below
    // and well above the subcool values the estimator can produce, so the
    // generated subcool lands on both sides of the boundary and the `<=`
    // comparison — including near-equality — is exercised.
    //
    // numericDouble (not double) is used throughout so no NaN or infinity edge
    // cases are injected: a non-finite pressure would fall outside the domain
    // (OutOfRange) and a non-finite threshold/pump temp would make the subcool
    // comparison ill-defined. This mirrors iOS's uniform `Gen.choose` ranges.
    val flashingThresholds: Arb<Double> = Arb.numericDouble(min = -100.0, max = 300.0)

    // Casing pressures uniformly within the closed steam-table domain
    // [minPressure, maxPressure], including the exact endpoints so the boundary
    // brackets are exercised. Staying strictly inside the domain guarantees the
    // estimator returns Success (never OutOfRange), which is exactly the case
    // this property is about.
    val pressuresInDomain: Arb<Double> = Arb.numericDouble(min = 0.0, max = 1.0).map { fraction ->
        table.minPressure + fraction * (table.maxPressure - table.minPressure)
    }

    // Arbitrary pump temperatures across a wide, finite range so the resulting
    // subcool spans both positive and negative values relative to any generated
    // threshold.
    val pumpTemperatures: Arb<Double> = Arb.numericDouble(min = -50.0, max = 400.0)

    "Feature: sagd-welltest-mobile, Property 16: Flashing-zone classification" {
        checkAll(
            pbtConfig(),
            flashingThresholds,
            pressuresInDomain,
            pumpTemperatures,
        ) { threshold, pressure, pumpTemperature ->
            // Inject the generated flashing threshold so the classification
            // boundary itself is under test, not a fixed default.
            val estimator = SubcoolEstimator(steamTable = table, flashingThreshold = threshold)

            val result = estimator.estimate(
                casingPressure = pressure,
                pumpTemperature = pumpTemperature,
            )

            // An in-domain pressure with valid numeric inputs always yields
            // Success; anything else means the estimator misclassified a valid
            // submission.
            result.shouldBeInstanceOf<SubcoolResult.Success>()

            // The requirement-5.3 definition: flashing iff subcool is at or
            // below the configured threshold.
            result.isFlashing shouldBe (result.subcoolValue <= threshold)
        }
    }
})
