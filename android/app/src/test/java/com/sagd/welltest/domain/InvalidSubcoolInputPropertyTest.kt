/*
 * Property 18: Invalid subcool input rejection (Android) — task 11.6.
 *
 * Validates: Requirements 5.5
 *
 * Requirement 5.5: if an Operator submits an empty or non-numeric value for
 * casing pressure or pump temperature, the Subcool_Estimator rejects the
 * submission and names the invalid field. This property asserts that rejection
 * directly against the String-input `estimate(casingPressure, pumpTemperature)`
 * overload, which validates the as-entered fields at the boundary.
 *
 * Two complementary sub-properties cover the two fields, with the estimator's
 * stated validation order (casing pressure is checked first) in mind:
 *
 *   1. For any empty / whitespace-only / non-numeric casing pressure paired
 *      with an arbitrary pump-temperature string, the result is
 *      InvalidInput("casingPressure") — regardless of whether the pump
 *      temperature is itself valid or invalid, because casing pressure is
 *      validated first.
 *
 *   2. For a valid (numeric) casing pressure paired with any empty /
 *      whitespace-only / non-numeric pump temperature, the result is
 *      InvalidInput("pumpTemperature"). A valid casing pressure lets validation
 *      advance to the pump-temperature check; the numeric casing string need not
 *      lie inside the steam-table domain because input validation of both fields
 *      happens before the domain check.
 *
 * The estimator is pure value logic over the injected bundled steam table, so
 * the test needs no persistence, networking, or UI. Each sub-property runs a
 * minimum of 100 iterations via [pbtConfig] and mirrors the iOS
 * InvalidSubcoolInputPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

class InvalidSubcoolInputPropertyTest : StringSpec({

    // The estimator under test, using the bundled steam table. The flashing
    // threshold is irrelevant to this property — every expected outcome is an
    // InvalidInput rejection that never reaches the subcool computation.
    val estimator = SubcoolEstimator(steamTable = SteamTable.bundled)

    // --- Generators ---------------------------------------------------------

    // The empty string — rejected because, after trimming, it has no digits.
    val emptyString: Arb<String> = Arb.constant("")

    // Non-empty strings composed solely of whitespace characters; after the
    // estimator trims surrounding whitespace these are empty, so they are
    // rejected exactly like the empty string.
    val whitespaceOnly: Arb<String> =
        Arb.list(Arb.element(' ', '\t', '\n'), range = 1..5).map { it.joinToString("") }

    // Non-numeric strings: number-like filler with at least one character the
    // numeric format forbids (anything other than digits, a single leading `-`,
    // and at most one `.`). A guaranteed-bad character is always injected so the
    // generator never accidentally produces a valid number, and it is placed in
    // varied positions across cases via the surrounding filler.
    val nonNumericChar: Arb<Char> = Arb.element("aZxX+\$%efF,;:/?*gh".toList())
    val filler: Arb<String> =
        Arb.list(Arb.element("0123456789.-".toList()), range = 0..5).map { it.joinToString("") }
    val nonNumericString: Arb<String> =
        Arb.bind(filler, nonNumericChar, filler) { prefix, bad, suffix -> prefix + bad + suffix }

    // Any string the estimator must reject as empty or non-numeric. The three
    // sub-generators are drawn with equal weight so each rejection cause is well
    // represented.
    val invalidNumericString: Arb<String> =
        Arb.choice(emptyString, whitespaceOnly, nonNumericString)

    // Valid numeric strings in the estimator's accepted format: optional leading
    // minus, digits, and at most one decimal point with a digit present. Values
    // are intentionally unconstrained relative to the steam-table domain — input
    // validation of both fields precedes the domain check, so any parseable
    // number lets validation reach the pump field.
    val sign: Arb<String> = Arb.of("", "-")
    val digits: Arb<String> =
        Arb.list(Arb.element("0123456789".toList()), range = 1..6).map { it.joinToString("") }
    val integerString: Arb<String> = Arb.bind(sign, digits) { s, d -> s + d }
    val decimalString: Arb<String> =
        Arb.bind(sign, digits, digits) { s, whole, frac -> "$s$whole.$frac" }
    val validNumericString: Arb<String> = Arb.choice(integerString, decimalString)

    // An arbitrary pump-temperature string for case 1: a mix of valid numeric
    // strings and invalid (empty / whitespace / non-numeric) strings. Either way
    // the casing-pressure rejection must take precedence, so this exercises both
    // "both fields invalid" and "only casing invalid".
    val anyPumpTemperatureString: Arb<String> =
        Arb.choice(validNumericString, invalidNumericString)

    // --- Properties ---------------------------------------------------------

    // Case 1: an invalid casing pressure is rejected as "casingPressure",
    // independent of the pump-temperature string, because casing pressure is
    // validated first.
    "Feature: sagd-welltest-mobile, Property 18: Invalid subcool input rejection (invalid casing pressure)" {
        checkAll(pbtConfig(), invalidNumericString, anyPumpTemperatureString) { casingPressure, pumpTemperature ->
            val result = estimator.estimate(casingPressure = casingPressure, pumpTemperature = pumpTemperature)
            result shouldBe SubcoolResult.InvalidInput(SubcoolEstimator.FIELD_CASING_PRESSURE)
        }
    }

    // Case 2: with a valid (numeric) casing pressure, an invalid pump
    // temperature is rejected as "pumpTemperature".
    "Feature: sagd-welltest-mobile, Property 18: Invalid subcool input rejection (invalid pump temperature)" {
        checkAll(pbtConfig(), validNumericString, invalidNumericString) { casingPressure, pumpTemperature ->
            val result = estimator.estimate(casingPressure = casingPressure, pumpTemperature = pumpTemperature)
            result shouldBe SubcoolResult.InvalidInput(SubcoolEstimator.FIELD_PUMP_TEMPERATURE)
        }
    }
})
