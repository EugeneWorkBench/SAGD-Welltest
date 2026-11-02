/*
 * Task 12.6 — Unit test: range boundary examples (Android).
 *
 * Validates: Requirements 3.2, 3.4, 4.3
 *
 * Example-based companion to the Property 11 range test
 * ([RangeValidationPropertyTest]). Where the property test samples the full
 * range, this test pins down the *exact inclusive boundaries* of every bounded
 * wellhead-log field and the first step just beyond them, so a regression that
 * shifts a comparison from `<`/`>` to `<=`/`>=` (or vice versa) is caught by a
 * named example:
 *
 *   * each field accepts its exact inclusive minimum (0) and its exact inclusive
 *     maximum (water cut 100; production fields 999,999.99; steam injection
 *     pressure 25,000; steam temperature 400; steam flow rate 2,000); and
 *   * each field rejects the smallest representable step beyond either bound
 *     (-0.01 below the minimum, max + 0.01 above the maximum) with exactly one
 *     [FieldError.Kind.OutOfRange] error for that field and no other errors.
 *
 * Exact boundaries exercised: 0, 100, 25000, 400, 2000, 999999.99.
 *
 * Uses [DefaultProductionLogValidator]/[DefaultInjectionLogValidator] over
 * [WellheadLogFieldRange]/[WellheadLogField] with [Locale.ROOT] formatting, and
 * mirrors the iOS RangeBoundaryTests.
 */
package com.sagd.welltest.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.Locale

class RangeBoundaryTest : StringSpec({

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

    // A bounded numeric field under test: its stable identifier, the inclusive
    // range it accepts, and how to substitute a raw string into that field (all
    // other fields held known-valid and in range), returning the resulting
    // FieldError list (empty when the whole form is valid).
    data class BoundedField(
        val id: String,
        val rangeMin: Double,
        val rangeMax: Double,
        val validate: (raw: String) -> List<FieldError>,
    )

    // All six bounded numeric fields across the two log validators, each paired
    // with the inclusive range from WellheadLogFieldRange.
    val boundedFields: List<BoundedField> = listOf(
        // --- Production fields: [0.00, 999,999.99] (Req 3.4) ---
        BoundedField(
            WellheadLogField.EMULSION_FLOW_RATE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(emulsionFlowRate = raw))) },
        BoundedField(
            WellheadLogField.CASING_GAS_PRESSURE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(casingGasPressure = raw))) },
        BoundedField(
            WellheadLogField.ESP_MOTOR_TEMPERATURE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(espMotorTemperature = raw))) },
        // --- Water cut percentage: [0, 100] (Req 3.2) ---
        BoundedField(
            WellheadLogField.WATER_CUT_PERCENT,
            WellheadLogFieldRange.WATER_CUT_MIN,
            WellheadLogFieldRange.WATER_CUT_MAX,
        ) { raw -> errorsOf(production.validate(validProduction.copy(waterCutPercent = raw))) },
        // --- Steam injection pressure: [0, 25,000] (Req 4.3) ---
        BoundedField(
            WellheadLogField.STEAM_INJECTION_PRESSURE,
            WellheadLogFieldRange.STEAM_PRESSURE_MIN,
            WellheadLogFieldRange.STEAM_PRESSURE_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamInjectionPressure = raw))) },
        // --- Steam temperature: [0, 400] (Req 4.3) ---
        BoundedField(
            WellheadLogField.STEAM_TEMPERATURE,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MIN,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamTemperature = raw))) },
        // --- Steam flow rate: [0, 2,000] (Req 4.3) ---
        BoundedField(
            WellheadLogField.STEAM_FLOW_RATE,
            WellheadLogFieldRange.STEAM_FLOW_MIN,
            WellheadLogFieldRange.STEAM_FLOW_MAX,
        ) { raw -> errorsOf(injection.validate(validInjection.copy(steamFlowRate = raw))) },
    )

    // Renders a boundary value as a well-formed numeric string under the accepted
    // grammar, using exactly two fractional digits so exact values like
    // 999999.99 and 25000.01 round-trip without precision loss or exponent
    // notation. Locale.ROOT keeps the decimal separator '.', matching the
    // validator's grammar.
    fun numericString(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    // --- Accepts exact inclusive boundaries ---
    //
    // Every bounded field accepts its exact inclusive minimum (0) and maximum
    // (100 / 999,999.99 / 25,000 / 400 / 2,000), producing a valid form with no
    // field errors at all.
    "accepts the exact inclusive minimum and maximum of every bounded field" {
        for (field in boundedFields) {
            for (boundary in listOf(field.rangeMin, field.rangeMax)) {
                val raw = numericString(boundary)
                withClue("field ${field.id} must accept its inclusive boundary $raw") {
                    field.validate(raw).shouldBeEmpty()
                }
            }
        }
    }

    // --- Rejects values just beyond the boundaries ---
    //
    // Every bounded field rejects the smallest step beyond either inclusive
    // bound — min - 0.01 and max + 0.01 — with exactly one OutOfRange error
    // naming that field and no other errors (every other field is held valid).
    "rejects the step just beyond each boundary with exactly one OutOfRange error" {
        val step = 0.01
        for (field in boundedFields) {
            val expected = FieldError.of(
                field.id,
                FieldError.Kind.OutOfRange(min = field.rangeMin, max = field.rangeMax),
            )

            val belowRaw = numericString(field.rangeMin - step) // e.g. "-0.01"
            withClue("field ${field.id} must reject just-below-min $belowRaw with a single OutOfRange error") {
                field.validate(belowRaw) shouldBe listOf(expected)
            }

            val aboveRaw = numericString(field.rangeMax + step) // e.g. "100.01"
            withClue("field ${field.id} must reject just-above-max $aboveRaw with a single OutOfRange error") {
                field.validate(aboveRaw) shouldBe listOf(expected)
            }
        }
    }

    // --- Named boundary examples from the task ---
    //
    // Pins the specific maximum boundary values called out by the task — 100,
    // 25000, 400, 2000, 999999.99 — plus the shared inclusive minimum 0, each
    // accepted exactly and rejected one step beyond. These are spelled out as
    // literal strings (independent of WellheadLogFieldRange) so the test fails
    // loudly if a range constant is ever changed away from the requirement.
    "pins the named boundary examples: 0, 100, 25000, 400, 2000, 999999.99" {
        fun productionErrors(mutate: (ProductionLogInput) -> ProductionLogInput): List<FieldError> =
            errorsOf(production.validate(mutate(validProduction)))

        fun injectionErrors(mutate: (InjectionLogInput) -> InjectionLogInput): List<FieldError> =
            errorsOf(injection.validate(mutate(validInjection)))

        // Shared inclusive minimum: 0 accepted, -0.01 rejected (water cut).
        productionErrors { it.copy(waterCutPercent = "0") }.shouldBeEmpty()
        productionErrors { it.copy(waterCutPercent = "-0.01") } shouldBe listOf(
            FieldError.of(
                WellheadLogField.WATER_CUT_PERCENT,
                FieldError.Kind.OutOfRange(min = 0.0, max = 100.0),
            ),
        )

        // Water cut maximum: 100 accepted, 100.01 rejected.
        productionErrors { it.copy(waterCutPercent = "100") }.shouldBeEmpty()
        productionErrors { it.copy(waterCutPercent = "100.01") } shouldBe listOf(
            FieldError.of(
                WellheadLogField.WATER_CUT_PERCENT,
                FieldError.Kind.OutOfRange(min = 0.0, max = 100.0),
            ),
        )

        // Production field maximum: 999999.99 accepted, 1000000.00 rejected.
        productionErrors { it.copy(emulsionFlowRate = "999999.99") }.shouldBeEmpty()
        productionErrors { it.copy(emulsionFlowRate = "1000000.00") } shouldBe listOf(
            FieldError.of(
                WellheadLogField.EMULSION_FLOW_RATE,
                FieldError.Kind.OutOfRange(min = 0.0, max = 999_999.99),
            ),
        )

        // Steam injection pressure maximum: 25000 accepted, 25000.01 rejected.
        injectionErrors { it.copy(steamInjectionPressure = "25000") }.shouldBeEmpty()
        injectionErrors { it.copy(steamInjectionPressure = "25000.01") } shouldBe listOf(
            FieldError.of(
                WellheadLogField.STEAM_INJECTION_PRESSURE,
                FieldError.Kind.OutOfRange(min = 0.0, max = 25_000.0),
            ),
        )

        // Steam temperature maximum: 400 accepted, 400.01 rejected.
        injectionErrors { it.copy(steamTemperature = "400") }.shouldBeEmpty()
        injectionErrors { it.copy(steamTemperature = "400.01") } shouldBe listOf(
            FieldError.of(
                WellheadLogField.STEAM_TEMPERATURE,
                FieldError.Kind.OutOfRange(min = 0.0, max = 400.0),
            ),
        )

        // Steam flow rate maximum: 2000 accepted, 2000.01 rejected.
        injectionErrors { it.copy(steamFlowRate = "2000") }.shouldBeEmpty()
        injectionErrors { it.copy(steamFlowRate = "2000.01") } shouldBe listOf(
            FieldError.of(
                WellheadLogField.STEAM_FLOW_RATE,
                FieldError.Kind.OutOfRange(min = 0.0, max = 2_000.0),
            ),
        )
    }
})
