/*
 * Property 17: Out-of-range pressure rejection (Android) — task 11.5.
 *
 * Validates: Requirements 5.4
 *
 * Requirement 5.4: IF a submitted casing pressure falls outside the range
 * covered by the Steam_Table, THEN the Subcool_Estimator SHALL reject the
 * submission (and name the offending field). The complement — a pressure
 * inside the domain, paired with a valid pump temperature, produces a value —
 * is the behaviour that gives the rejection meaning: the estimator rejects
 * exactly the out-of-domain pressures, not everything.
 *
 * This property asserts both directions directly against [SubcoolEstimator],
 * driven through its string-based `estimate(casingPressure, pumpTemperature)`
 * submission entry point (the same path the UI uses):
 *
 *   * A pressure strictly below `minPressure` or strictly above `maxPressure`,
 *     with a valid numeric pump temperature, yields
 *     `SubcoolResult.OutOfRange(field = "casingPressure")`.
 *   * A pressure inside `[minPressure, maxPressure]`, with a valid numeric
 *     pump temperature, yields `SubcoolResult.Success`.
 *
 * The estimator is pure value logic over the injected bundled steam table, so
 * the test needs no persistence, networking, or UI. It runs a minimum of 100
 * iterations via [pbtConfig] and mirrors the iOS
 * OutOfRangePressureRejectionPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.merge
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import java.util.Locale

class OutOfRangePressureRejectionPropertyTest : StringSpec({

    val table = SteamTable.bundled

    // The estimator under test, using the bundled steam table. The flashing
    // threshold only affects the `isFlashing` flag of a Success; this property
    // cares only about which result *case* is produced, so the default
    // threshold is fine.
    val estimator = SubcoolEstimator(steamTable = table)

    // Field identifier the estimator names when it rejects a pressure outside
    // the steam-table domain (Req 5.4).
    val casingPressureField = SubcoolEstimator.FIELD_CASING_PRESSURE

    // A small margin keeps the "just outside" values clearly out of domain
    // without relying on exact floating-point equality at the endpoints (the
    // domain endpoints themselves are in-domain and belong to the other Arb).
    val epsilon = 1e-6

    // numericDouble (not double) is used throughout so no NaN or infinity edge
    // cases are injected: a non-finite pressure would itself be rejected (as
    // out of domain) for an unrelated reason, muddying which band produced the
    // rejection. This mirrors iOS's uniform `Gen.choose` ranges.
    //
    // Casing pressures strictly outside the closed steam-table domain
    // [minPressure, maxPressure] — a lower band from a non-negative floor up to
    // just under minPressure, and an upper band from just over maxPressure to
    // well beyond it — merged so both out-of-range directions are exercised.
    val pressuresOutOfDomain: Arb<Double> = Arb.numericDouble(
        min = 0.0,
        max = table.minPressure - epsilon,
    ).merge(
        Arb.numericDouble(
            min = table.maxPressure + epsilon,
            max = table.maxPressure * 3.0,
        ),
    )

    // Casing pressures uniformly within the closed steam-table domain
    // [minPressure, maxPressure], including the exact endpoints so the boundary
    // brackets are exercised. Every value here is in-domain, so the estimator
    // returns a Success.
    val pressuresInDomain: Arb<Double> = Arb.numericDouble(min = 0.0, max = 1.0).map { fraction ->
        table.minPressure + fraction * (table.maxPressure - table.minPressure)
    }

    // Valid pump temperatures across a wide, finite range. The pump temperature
    // does not affect which result case the estimator returns (a valid number
    // never trips InvalidInput, and the domain check is on pressure only), so
    // any well-formed numeric value works.
    val pumpTemperatures: Arb<Double> = Arb.numericDouble(min = -50.0, max = 400.0)

    // Formats a generated Double as a plain numeric string the estimator's
    // numeric-format rule accepts (optional leading minus, digits, at most one
    // decimal point). Using the string entry point exercises the real
    // submission path end to end; a fixed-point representation never introduces
    // grouping separators or exponent notation. Locale.US pins the decimal
    // separator to '.', which the estimator's numeric-format rule accepts
    // regardless of the host locale.
    fun numericString(value: Double): String = String.format(Locale.US, "%.6f", value)

    // A casing pressure outside the steam-table domain (below minPressure or
    // above maxPressure), submitted with a valid pump temperature, is rejected
    // as OutOfRange naming the casing-pressure field.
    "Feature: sagd-welltest-mobile, Property 17: Out-of-range pressure rejection" {
        checkAll(
            pbtConfig(),
            pressuresOutOfDomain,
            pumpTemperatures,
        ) { pressure, pumpTemperature ->
            val result = estimator.estimate(
                casingPressure = numericString(pressure),
                pumpTemperature = numericString(pumpTemperature),
            )

            result.shouldBeInstanceOf<SubcoolResult.OutOfRange>()
            result.field shouldBe casingPressureField
        }
    }

    // A casing pressure inside the steam-table domain, submitted with a valid
    // pump temperature, produces a Success value (never OutOfRange and never
    // InvalidInput, since both inputs are well-formed numbers).
    "Feature: sagd-welltest-mobile, Property 17: Out-of-range pressure rejection (in-domain produces a value)" {
        checkAll(
            pbtConfig(),
            pressuresInDomain,
            pumpTemperatures,
        ) { pressure, pumpTemperature ->
            val result = estimator.estimate(
                casingPressure = numericString(pressure),
                pumpTemperature = numericString(pumpTemperature),
            )

            result.shouldBeInstanceOf<SubcoolResult.Success>()
        }
    }
})
