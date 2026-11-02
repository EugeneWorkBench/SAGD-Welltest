/*
 * Property 11: Range validation per field (Android) — task 12.3.
 *
 * Validates: Requirements 3.2, 3.4, 4.3
 *
 * For any numeric value entered into a bounded field, the validator rejects the
 * entry if and only if the value falls outside that field's accepted range —
 * water cut percentage outside [0, 100]; production fields (emulsion flow rate,
 * casing gas pressure, ESP motor temperature) outside [0.00, 999,999.99]; steam
 * injection pressure outside [0, 25,000]; steam temperature outside [0, 400];
 * steam flow rate outside [0, 2,000] — and otherwise accepts it.
 *
 * Expressed concretely against the validators, with every other field held
 * valid and in range so the field under test is the only one that can fail:
 *
 *   * an in-range value placed in a field produces NO OutOfRange error for that
 *     field (it is accepted); and
 *   * an out-of-range value (strictly below the field's minimum or strictly
 *     above its maximum) produces EXACTLY one [FieldError] for that field with
 *     kind [FieldError.Kind.OutOfRange] and no other field errors.
 *
 * Out-of-range values are generated as *well-formed* numeric strings (the
 * grammar permits an optional leading minus, so below-minimum negatives are
 * numerically well-formed) so the range rule is isolated from the
 * numeric-format rule: a well-formed out-of-range value must be rejected for
 * range, not for format.
 *
 * Uses [DefaultProductionLogValidator]/[DefaultInjectionLogValidator] over
 * [WellheadLogFieldRange]/[WellheadLogField], runs a minimum of 100 iterations
 * via [pbtConfig], and mirrors the iOS RangeValidationPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.util.Locale

class RangeValidationPropertyTest : StringSpec({

    val production = DefaultProductionLogValidator()
    val injection = DefaultInjectionLogValidator()

    // Fixed, known-valid, in-range placeholder values for the "other" fields so
    // the only field that can ever fail is the one under test. "10" is
    // well-formed and inside every range used below.
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

    // A bounded numeric field under test: its id, its accepted inclusive range,
    // and how to inject a raw string into the relevant validator (with all other
    // fields held fixed-valid) and run it, returning the resulting error list.
    data class FieldUnderTest(
        val id: String,
        val rangeMin: Double,
        val rangeMax: Double,
        val validate: (raw: String) -> List<FieldError>,
    )

    // All six bounded numeric fields across the production and injection
    // validators, each paired with its accepted inclusive range (matching the
    // ranges in WellheadLogFieldRange).
    val fields: List<FieldUnderTest> = listOf(
        // --- Production fields: [0.00, 999,999.99] (Req 3.4) ---
        FieldUnderTest(
            WellheadLogField.EMULSION_FLOW_RATE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(emulsionFlowRate = raw))) },
        FieldUnderTest(
            WellheadLogField.CASING_GAS_PRESSURE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(casingGasPressure = raw))) },
        FieldUnderTest(
            WellheadLogField.ESP_MOTOR_TEMPERATURE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(espMotorTemperature = raw))) },
        // --- Water cut percentage: [0, 100] (Req 3.2) ---
        FieldUnderTest(
            WellheadLogField.WATER_CUT_PERCENT,
            WellheadLogFieldRange.WATER_CUT_MIN,
            WellheadLogFieldRange.WATER_CUT_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(waterCutPercent = raw))) },
        // --- Steam injection pressure: [0, 25,000] (Req 4.3) ---
        FieldUnderTest(
            WellheadLogField.STEAM_INJECTION_PRESSURE,
            WellheadLogFieldRange.STEAM_PRESSURE_MIN,
            WellheadLogFieldRange.STEAM_PRESSURE_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamInjectionPressure = raw))) },
        // --- Steam temperature: [0, 400] (Req 4.3) ---
        FieldUnderTest(
            WellheadLogField.STEAM_TEMPERATURE,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MIN,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamTemperature = raw))) },
        // --- Steam flow rate: [0, 2,000] (Req 4.3) ---
        FieldUnderTest(
            WellheadLogField.STEAM_FLOW_RATE,
            WellheadLogFieldRange.STEAM_FLOW_MIN,
            WellheadLogFieldRange.STEAM_FLOW_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamFlowRate = raw))) },
    )

    // Formats a Double as a well-formed numeric string under the accepted grammar
    // (digits with at most one decimal point, optional leading minus; no
    // exponent, no grouping separators), with exactly two fractional digits. The
    // value parsed back equals the input rounded to 1/100, which the generators
    // account for when deciding in/out of range. Locale.ROOT keeps the decimal
    // separator '.', matching the validator's grammar.
    fun numericString(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    // An in-range value for [min, max] as a well-formed numeric string, spanning
    // the interior and both inclusive boundaries. After the two-decimal rounding
    // in numericString, the parsed value still lies in [min, max] because both
    // bounds are themselves multiples of 1/100.
    fun inRange(min: Double, max: Double): Arb<String> {
        val minH = Math.round(min * 100).toInt()
        val maxH = Math.round(max * 100).toInt()
        // Interior values uniformly across the range (in hundredths).
        val interior: Arb<String> = Arb.int(minH..maxH).map { numericString(it / 100.0) }
        // Exact inclusive boundaries, which must be accepted.
        val boundaries: Arb<String> = Arb.element(numericString(min), numericString(max))
        return Arb.choice(interior, boundaries)
    }

    // An out-of-range value for [min, max] as a well-formed numeric string:
    // either strictly below min or strictly above max. The offset is at least
    // 1/100 so, after two-decimal rounding, the value stays strictly outside the
    // range. All ranges here have min == 0, so the below-min branch produces
    // negatives (well-formed via the leading minus).
    fun outOfRange(min: Double, max: Double): Arb<String> {
        // Below the minimum: min - delta, delta in [0.01, 1000.00].
        val below: Arb<String> = Arb.int(1..100_000).map { numericString(min - it / 100.0) }
        // Above the maximum: max + delta, delta in [0.01, 1000.00].
        val above: Arb<String> = Arb.int(1..100_000).map { numericString(max + it / 100.0) }
        return Arb.choice(below, above)
    }

    // One generated case: a uniformly chosen field paired with a well-formed
    // in-range draw and a well-formed out-of-range draw for that field, so each
    // iteration exercises both branches of the property against the same field.
    data class Case(val field: FieldUnderTest, val inRangeRaw: String, val outOfRangeRaw: String)

    val cases: Arb<Case> = Arb.element(fields).flatMap { field ->
        inRange(field.rangeMin, field.rangeMax).flatMap { inRangeRaw ->
            outOfRange(field.rangeMin, field.rangeMax).map { outOfRangeRaw ->
                Case(field, inRangeRaw, outOfRangeRaw)
            }
        }
    }

    "Feature: sagd-welltest-mobile, Property 11: Range validation per field" {
        checkAll(pbtConfig(), cases) { case ->
            val field = case.field
            val expectedOutOfRange = FieldError.of(
                field.id,
                FieldError.Kind.OutOfRange(min = field.rangeMin, max = field.rangeMax),
            )

            // --- In-range branch: NO OutOfRange error for the field. ---
            // The value is well-formed and inside the range, so the field — and
            // every other field, which is fixed-valid — must be accepted.
            field.validate(case.inRangeRaw)
                .filter { it.field == field.id && it.kind == expectedOutOfRange.kind }
                .shouldBeEmpty()

            // --- Out-of-range branch: EXACTLY one OutOfRange error for the
            //     field, and no other field errors (other fields are valid). ---
            field.validate(case.outOfRangeRaw) shouldBe listOf(expectedOutOfRange)
        }
    }
})
