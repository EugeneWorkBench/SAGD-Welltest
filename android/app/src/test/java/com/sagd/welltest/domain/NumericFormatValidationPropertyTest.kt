/*
 * Property 10: Numeric-format validation (Android) — task 12.2.
 *
 * Validates: Requirements 3.3, 4.2
 *
 * For any input string for a numeric wellhead-log field (production emulsion
 * flow rate, casing gas pressure, ESP motor temperature; injection steam
 * pressure, temperature, flow rate), the validator accepts the entry iff the
 * string consists solely of digits with at most one decimal point and an
 * optional single leading minus sign; otherwise it rejects the entry with a
 * numeric-format error.
 *
 * Expressed concretely against [DefaultProductionLogValidator] /
 * [DefaultInjectionLogValidator]: a *well-formed, in-range* value placed in a
 * field (with every other field valid and in range) produces NO
 * [FieldError.Kind.NotNumeric] error for that field, while a *malformed* value
 * placed in that field produces EXACTLY one [FieldError] for that field with
 * kind [FieldError.Kind.NotNumeric] (and no other field errors, since the other
 * fields are valid).
 *
 * The validators are pure value logic over their string inputs, so the test
 * needs no persistence, networking, or UI. It runs a minimum of 100 iterations
 * via [pbtConfig] and mirrors the iOS NumericFormatValidationPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

class NumericFormatValidationPropertyTest : StringSpec({

    // region Field descriptors

    /**
     * A numeric field under test: how to inject a raw string into the relevant
     * validator, run it, and the inclusive range the field accepts (so the
     * well-formed generator can stay in range and isolate the numeric-format
     * rule from the range rule).
     */
    data class FieldUnderTest(
        val id: String,
        val rangeMin: Double,
        val rangeMax: Double,
        /**
         * Validate with [raw] substituted into this field and all other fields
         * set to fixed, valid, in-range values; returns the error list (empty
         * when the result is [ValidationResult.Valid]).
         */
        val validate: (raw: String) -> List<FieldError>,
    )

    val production = DefaultProductionLogValidator()
    val injection = DefaultInjectionLogValidator()

    // Fixed, known-valid, in-range placeholder values for the "other" fields so
    // that the only field that can ever fail is the one under test. These are
    // plainly well-formed and inside every range used below.
    val validProduction = ProductionLogInput(
        wellId = "WELL-1",
        emulsionFlowRate = "10",
        casingGasPressure = "10",
        espMotorTemperature = "10",
        waterCutPercent = "10",
    )
    val validInjection = InjectionLogInput(
        wellId = "WELL-1",
        steamInjectionPressure = "10",
        steamTemperature = "10",
        steamFlowRate = "10",
    )

    fun <T> errorsOf(result: ValidationResult<T>): List<FieldError> = when (result) {
        is ValidationResult.Valid -> emptyList()
        is ValidationResult.Invalid -> result.errors
    }

    // All six numeric fields across the production and injection validators,
    // each paired with its accepted range.
    val fields: List<FieldUnderTest> = listOf(
        FieldUnderTest(
            id = WellheadLogField.EMULSION_FLOW_RATE,
            rangeMin = WellheadLogFieldRange.PRODUCTION_MIN,
            rangeMax = WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(emulsionFlowRate = raw))) },
        FieldUnderTest(
            id = WellheadLogField.CASING_GAS_PRESSURE,
            rangeMin = WellheadLogFieldRange.PRODUCTION_MIN,
            rangeMax = WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(casingGasPressure = raw))) },
        FieldUnderTest(
            id = WellheadLogField.ESP_MOTOR_TEMPERATURE,
            rangeMin = WellheadLogFieldRange.PRODUCTION_MIN,
            rangeMax = WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(espMotorTemperature = raw))) },
        FieldUnderTest(
            id = WellheadLogField.STEAM_INJECTION_PRESSURE,
            rangeMin = WellheadLogFieldRange.STEAM_PRESSURE_MIN,
            rangeMax = WellheadLogFieldRange.STEAM_PRESSURE_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamInjectionPressure = raw))) },
        FieldUnderTest(
            id = WellheadLogField.STEAM_TEMPERATURE,
            rangeMin = WellheadLogFieldRange.STEAM_TEMPERATURE_MIN,
            rangeMax = WellheadLogFieldRange.STEAM_TEMPERATURE_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamTemperature = raw))) },
        FieldUnderTest(
            id = WellheadLogField.STEAM_FLOW_RATE,
            rangeMin = WellheadLogFieldRange.STEAM_FLOW_MIN,
            rangeMax = WellheadLogFieldRange.STEAM_FLOW_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamFlowRate = raw))) },
    )

    // endregion

    // region Generators

    /**
     * Generates a well-formed numeric string per the accepted grammar (optional
     * single leading minus, digits, at most one decimal point), constrained so
     * its value lands inside the given `[min, max]` range. This isolates the
     * numeric-format rule: a well-formed in-range value must not produce a
     * [FieldError.Kind.NotNumeric] error.
     *
     * All ranges under test have min == 0, so staying non-negative and below
     * max keeps the value in range; a two-decimal fraction (< 1) keeps the
     * magnitude safely below max for every range here.
     */
    fun wellFormedInRange(min: Double, max: Double): Arb<String> {
        val upperInt = max.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val intPart = Arb.int(min = min.toInt().coerceAtLeast(0), max = (upperInt - 1).coerceAtLeast(0))
        return intPart.flatMap { whole ->
            // Half the draws carry a two-decimal fraction, half are whole.
            Arb.int(min = -1, max = 99).map { frac ->
                if (frac < 0) "$whole" else "$whole.$frac"
            }
        }
    }

    // A pool of concrete malformed literals covering the grammar violations
    // called out in the requirements: letters, '+', exponents, multiple decimal
    // points, interior signs, grouping/underscore separators, stray symbols,
    // and sign/dot-only strings.
    val malformedPool = listOf(
        "abc",      // letters
        "12a",      // trailing letter
        "a12",      // leading letter
        "1 2",      // interior space
        "+1",       // leading plus
        "1+1",      // interior plus
        "1e3",      // exponent
        "1E3",      // exponent (upper)
        "1.2e3",    // decimal + exponent
        "1.2.3",    // multiple decimal points
        "..5",      // multiple decimal points (leading)
        "5..",      // multiple decimal points (trailing)
        "--1",      // double minus
        "1-2",      // interior minus
        "1,000",    // grouping separator
        "\$5",      // currency symbol
        "0x1F",     // hex literal
        "NaN",      // not-a-number literal
        "inf",      // infinity literal
        "Infinity", // infinity literal
        "-",        // sign only (no digits)
        ".",        // dot only (no digits)
        "-.",       // sign + dot, no digits
        "1_000",    // underscore separator
        "１２３",   // full-width (non-ASCII) digits
    )

    // Also synthesize malformed strings by appending a disallowed character to
    // an otherwise-numeric string, broadening coverage beyond the fixed pool.
    // Space and tab are deliberately excluded: the validator trims leading and
    // trailing spaces/tabs, so a *trailing* space/tab would leave a well-formed
    // number and would not be a malformed case.
    val disallowed = Arb.element("+eE,x$%/:;?*abXYZ".toList())
    val synthesizedMalformed: Arb<String> =
        Arb.int(min = 0, max = 999).flatMap { digits ->
            disallowed.map { bad -> "$digits$bad" }
        }

    val malformed: Arb<String> =
        Arb.int(min = 0, max = 1).flatMap { which ->
            if (which == 0) Arb.element(malformedPool) else synthesizedMalformed
        }

    // Pick a field uniformly for each case so all six fields across both
    // validators are exercised over the 100+ iterations.
    val anyField: Arb<FieldUnderTest> = Arb.element(fields)

    // endregion

    "Feature: sagd-welltest-mobile, Property 10: Numeric-format validation" {
        // --- Well-formed, in-range branch: NO notNumeric error. ---
        checkAll(
            pbtConfig(),
            anyField.flatMap { f -> wellFormedInRange(f.rangeMin, f.rangeMax).map { f to it } },
        ) { (field, raw) ->
            val errors = field.validate(raw)
            val numericErrors = errors.filter {
                it.field == field.id && it.kind == FieldError.Kind.NotNumeric
            }
            numericErrors shouldBe emptyList()
        }

        // --- Malformed branch: EXACTLY one notNumeric error for the field, and
        //     no other field errors (the other fields are valid). ---
        checkAll(
            pbtConfig(),
            anyField.flatMap { f -> malformed.map { f to it } },
        ) { (field, raw) ->
            val errors = field.validate(raw)
            errors shouldBe listOf(FieldError.of(field.id, FieldError.Kind.NotNumeric))
        }
    }
})
