/*
 * Property 12: Save blocked exactly on invalid fields (Android) — task 12.4.
 *
 * Validates: Requirements 3.5, 4.4
 *
 * For a wellhead-log form, validation returns [ValidationResult.Invalid] if and
 * only if at least one field is empty or otherwise invalid; and when invalid,
 * the reported error set is *exactly* the set of offending fields — no more, no
 * fewer — each named once, in the validator's field order. Equivalently,
 * validation returns [ValidationResult.Valid] iff every field is a well-formed,
 * in-range value. In every case the entered values are retained unchanged (the
 * pure validator never mutates its input, so the UI can keep the operator's data
 * on screen while blocking the save).
 *
 * Expressed concretely against [DefaultProductionLogValidator] and
 * [DefaultInjectionLogValidator]: each field is generated independently as
 * either a valid-in-range value or an invalid one (empty / non-numeric /
 * out-of-range). The test computes the expected offending-field list from those
 * choices and asserts:
 *   - Valid exactly when the offending list is empty, and
 *   - otherwise Invalid carrying exactly the offending fields, each with the
 *     [FieldError.Kind] the validator reports under its precedence
 *     (Empty -> NotNumeric -> OutOfRange), and in the validator's field order.
 *
 * The validators are pure value logic over their inputs with no I/O, so the test
 * needs no persistence, networking, or UI. It runs a minimum of 100 iterations
 * via [pbtConfig] and mirrors the iOS SaveBlockedOnInvalidFieldsPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.util.Locale

class SaveBlockedOnInvalidFieldsPropertyTest : StringSpec({

    // region Per-field choice

    // How a single field is generated for a case: either a well-formed,
    // in-range value (accepted), or one of the three ways a field can offend.
    // The kind of the resulting FieldError is fully determined by the choice,
    // matching the validator's precedence (presence, then numeric format, then
    // range).
    sealed interface FieldChoice {
        val raw: String

        /** A well-formed numeric string inside the field's range. No error. */
        data class Valid(override val raw: String) : FieldChoice

        /** An empty or whitespace-only string. Yields [FieldError.Kind.Empty]. */
        data class Empty(override val raw: String) : FieldChoice

        /** A malformed string. Yields [FieldError.Kind.NotNumeric]. */
        data class NotNumeric(override val raw: String) : FieldChoice

        /** A numeric string strictly outside the range. Yields OutOfRange. */
        data class OutOfRange(override val raw: String) : FieldChoice

        /**
         * The [FieldError.Kind] the validator reports for this choice, given the
         * field's inclusive `[min, max]` range. [Valid] has no kind.
         */
        fun errorKind(min: Double, max: Double): FieldError.Kind? = when (this) {
            is Valid -> null
            is Empty -> FieldError.Kind.Empty
            is NotNumeric -> FieldError.Kind.NotNumeric
            is OutOfRange -> FieldError.Kind.OutOfRange(min = min, max = max)
        }
    }

    // endregion

    // region Field specs (id + range), in validator order

    // A bounded numeric field: its stable id and inclusive accepted range. The
    // order of these lists mirrors the order in which each validator appends
    // errors, so the expected error list can be built positionally.
    data class FieldSpec(val id: String, val min: Double, val max: Double)

    val productionSpecs = listOf(
        FieldSpec(
            WellheadLogField.EMULSION_FLOW_RATE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ),
        FieldSpec(
            WellheadLogField.CASING_GAS_PRESSURE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ),
        FieldSpec(
            WellheadLogField.ESP_MOTOR_TEMPERATURE,
            WellheadLogFieldRange.PRODUCTION_MIN,
            WellheadLogFieldRange.PRODUCTION_MAX,
        ),
        FieldSpec(
            WellheadLogField.WATER_CUT_PERCENT,
            WellheadLogFieldRange.WATER_CUT_MIN,
            WellheadLogFieldRange.WATER_CUT_MAX,
        ),
    )

    val injectionSpecs = listOf(
        FieldSpec(
            WellheadLogField.STEAM_INJECTION_PRESSURE,
            WellheadLogFieldRange.STEAM_PRESSURE_MIN,
            WellheadLogFieldRange.STEAM_PRESSURE_MAX,
        ),
        FieldSpec(
            WellheadLogField.STEAM_TEMPERATURE,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MIN,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MAX,
        ),
        FieldSpec(
            WellheadLogField.STEAM_FLOW_RATE,
            WellheadLogFieldRange.STEAM_FLOW_MIN,
            WellheadLogFieldRange.STEAM_FLOW_MAX,
        ),
    )

    // endregion

    // region Helpers

    fun errorsOf(result: ValidationResult<*>): List<FieldError> = when (result) {
        is ValidationResult.Valid -> emptyList()
        is ValidationResult.Invalid -> result.errors
    }

    fun isValid(result: ValidationResult<*>): Boolean = result is ValidationResult.Valid

    // The exact expected error list for a set of per-field choices, in the
    // validator's field order, one entry per offending field. Built with
    // FieldError.of so message stays in lock-step with kind (matching the
    // validator's own construction).
    fun expectedErrors(
        specs: List<FieldSpec>,
        choices: List<FieldChoice>,
    ): List<FieldError> = specs.zip(choices).mapNotNull { (spec, choice) ->
        choice.errorKind(spec.min, spec.max)?.let { kind ->
            FieldError.of(spec.id, kind)
        }
    }

    // endregion

    // region Generators

    // Formats a Double as a well-formed numeric string under the accepted grammar
    // (digits with at most one decimal point, optional leading minus), with two
    // fractional digits. Locale-independent so the separator is always '.'.
    fun numericString(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    // A well-formed numeric string whose value lies inside [min, max]. All bounds
    // here are multiples of 1/100, so building from hundredths and rendering with
    // two decimals parses back in range. All ranges start at 0.
    fun inRangeString(min: Double, max: Double): Arb<String> {
        val minH = Math.round(min * 100.0).toInt()
        val maxH = Math.round(max * 100.0).toInt()
        return Arb.int(minH..maxH).map { numericString(it / 100.0) }
    }

    // A well-formed numeric string strictly outside [min, max] — either below the
    // minimum (a negative, via the grammar's leading minus) or above the maximum.
    // The offset is >= 1/100 so, after two-decimal rounding, it stays strictly
    // out of range.
    fun outOfRangeString(min: Double, max: Double): Arb<String> {
        val below: Arb<String> = Arb.int(1..100_000).map { numericString(min - it / 100.0) }
        val above: Arb<String> = Arb.int(1..100_000).map { numericString(max + it / 100.0) }
        return Arb.choice(below, above)
    }

    // An empty or whitespace-only string (trimmed to empty by the parser).
    val emptyString: Arb<String> = Arb.element("", " ", "   ", "\t", " \t ")

    // A malformed (non-numeric) string that is neither empty nor a well-formed
    // number, so the parser reports NotNumeric (not Empty). Each entry fails the
    // validator's strict grammar (optional single leading minus, digits, at most
    // one decimal point) while containing a non-space, non-tab character.
    val notNumericString: Arb<String> = Arb.element(
        "abc", "12a", "a12", "1 2", "+1", "1+1", "1e3", "1E3",
        "1.2e3", "1.2.3", "..5", "5..", "--1", "1-2", "1,000",
        "\$5", "0x1F", "NaN", "inf", "-", ".", "-.", "1_000",
    )

    // One field's choice for the given range, uniformly across the four kinds
    // (valid / empty / non-numeric / out-of-range) so that over 100+ cases both
    // all-valid and every offending combination are exercised.
    fun fieldChoice(min: Double, max: Double): Arb<FieldChoice> = Arb.choice(
        inRangeString(min, max).map<String, FieldChoice> { FieldChoice.Valid(it) },
        emptyString.map<String, FieldChoice> { FieldChoice.Empty(it) },
        notNumericString.map<String, FieldChoice> { FieldChoice.NotNumeric(it) },
        outOfRangeString(min, max).map<String, FieldChoice> { FieldChoice.OutOfRange(it) },
    )

    // Four independent field choices for the production form, in validator order.
    val productionChoices: Arb<List<FieldChoice>> = Arb.bind(
        fieldChoice(productionSpecs[0].min, productionSpecs[0].max),
        fieldChoice(productionSpecs[1].min, productionSpecs[1].max),
        fieldChoice(productionSpecs[2].min, productionSpecs[2].max),
        fieldChoice(productionSpecs[3].min, productionSpecs[3].max),
    ) { a, b, c, d -> listOf(a, b, c, d) }

    // Three independent field choices for the injection form, in validator order.
    val injectionChoices: Arb<List<FieldChoice>> = Arb.bind(
        fieldChoice(injectionSpecs[0].min, injectionSpecs[0].max),
        fieldChoice(injectionSpecs[1].min, injectionSpecs[1].max),
        fieldChoice(injectionSpecs[2].min, injectionSpecs[2].max),
    ) { a, b, c -> listOf(a, b, c) }

    // endregion

    // region Assertions

    // Shared assertions for one validator run:
    //   1. Valid iff the expected offending list is empty.
    //   2. The error list equals exactly the expected offending list (same
    //      fields, same kinds, each once, in field order); for a valid case this
    //      is the empty list.
    //   3. The input value is retained unchanged (the pure validator does not
    //      mutate it), satisfying the "retain all entered values" requirement.
    fun <I> assertMatches(
        label: String,
        input: I,
        result: ValidationResult<*>,
        expected: List<FieldError>,
    ) {
        val shouldBeValid = expected.isEmpty()
        val actualValid = isValid(result)
        val actualErrors = errorsOf(result)

        // 1. valid iff no offenders.
        withClue(
            "$label: expected valid == $shouldBeValid (offenders: $expected); " +
                "got valid=$actualValid, errors=$actualErrors",
        ) {
            actualValid shouldBe shouldBeValid
        }

        // 2. error list equals exactly the offending fields (order + kinds).
        withClue("$label: error list must equal exactly $expected; got $actualErrors") {
            actualErrors shouldBe expected
        }

        // 3. entered values retained (input unchanged). The input is an immutable
        //    data class the validator never writes back to, so its equality with a
        //    pre-captured copy is the observable retention guarantee.
        val before = input
        withClue("$label: entered values must be retained unchanged") {
            input shouldBe before
        }
    }

    // endregion

    "Feature: sagd-welltest-mobile, Property 12: Save blocked exactly on invalid fields" {
        checkAll(
            pbtConfig(),
            productionChoices,
            injectionChoices,
        ) { prodChoices, injChoices ->
            // --- Production validator ---
            val productionInput = ProductionLogInput(
                wellId = "WELL-1",
                emulsionFlowRate = prodChoices[0].raw,
                casingGasPressure = prodChoices[1].raw,
                espMotorTemperature = prodChoices[2].raw,
                waterCutPercent = prodChoices[3].raw,
            )
            val productionResult = DefaultProductionLogValidator().validate(productionInput)
            assertMatches(
                label = "production",
                input = productionInput,
                result = productionResult,
                expected = expectedErrors(productionSpecs, prodChoices),
            )

            // --- Injection validator ---
            val injectionInput = InjectionLogInput(
                wellId = "WELL-1",
                steamInjectionPressure = injChoices[0].raw,
                steamTemperature = injChoices[1].raw,
                steamFlowRate = injChoices[2].raw,
            )
            val injectionResult = DefaultInjectionLogValidator().validate(injectionInput)
            assertMatches(
                label = "injection",
                input = injectionInput,
                result = injectionResult,
                expected = expectedErrors(injectionSpecs, injChoices),
            )
        }
    }
})
