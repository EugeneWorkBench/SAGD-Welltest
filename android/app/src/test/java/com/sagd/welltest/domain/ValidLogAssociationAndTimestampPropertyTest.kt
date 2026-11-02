/*
 * Property 13: Valid log association and timestamp (Android) — task 12.5.
 *
 * Validates: Requirements 3.6, 4.5
 *
 * For any valid wellhead log (production or injection) that is validated, the
 * normalized [WellheadLog] is associated with the selected well identifier,
 * carries the correct [LogType], and has a capture timestamp expressed in UTC at
 * one-second precision (no sub-second component).
 *
 * Expressed concretely against [DefaultProductionLogValidator] and
 * [DefaultInjectionLogValidator]: a known clock is injected via `now`. For any
 * arbitrary well identifier and any injected instant (deliberately including a
 * sub-second component so truncation is actually exercised), with an all-valid,
 * in-range field set, the validator returns [ValidationResult.Valid] carrying a
 * [WellheadLog] where:
 *
 *   - `log.wellId == input.wellId` (associated with the selected well);
 *   - `log.logType` matches the validator kind (PRODUCTION / INJECTION);
 *   - `log.capturedAt == injected.truncatedTo(ChronoUnit.SECONDS)`, i.e. the
 *     injected instant floored to a whole second with no sub-second component.
 *
 * The validators are pure value logic over their inputs with no I/O, so the test
 * needs no persistence, networking, or UI. It runs a minimum of 100 iterations
 * via [pbtConfig] and mirrors the iOS ValidLogAssociationAndTimestampPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

class ValidLogAssociationAndTimestampPropertyTest : StringSpec({

    // region Generators

    // Formats a non-negative Double as a well-formed numeric string under the
    // accepted grammar (digits with at most one decimal point, no exponent, no
    // grouping separators), with two fractional digits. Locale-independent so the
    // separator is always '.'.
    fun numericString(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    // A well-formed, in-range numeric string for [min, max], spanning the interior
    // and both inclusive boundaries. Every bound used here is a multiple of 1/100,
    // so building from hundredths and rendering with two decimals parses back in
    // range.
    fun inRange(min: Double, max: Double): Arb<String> {
        val minH = Math.round(min * 100.0).toInt()
        val maxH = Math.round(max * 100.0).toInt()
        val interior: Arb<String> = Arb.int(minH..maxH).map { numericString(it / 100.0) }
        val boundaries: Arb<String> = Arb.element(numericString(min), numericString(max))
        return Arb.choice(interior, boundaries)
    }

    // An arbitrary, non-empty well identifier. The well id is opaque to the
    // validator (copied through verbatim), so a mix of QR-style identifiers and
    // free-form strings exercises the association faithfully.
    val wellId: Arb<String> = Arb.choice(
        Arb.string(1..24),
        Arb.list(
            Arb.element(('A'..'Z') + ('0'..'9') + listOf('-', '_')),
            1..24,
        ).map { it.joinToString("") },
        Arb.element(
            "WELL-1", "WELL-42", "PAD-7/INJ-3", "a well with spaces", "井-123",
        ),
    ).map { if (it.isEmpty()) "W" else it }

    // An injected instant as an arbitrary Instant, deliberately including instants
    // with a sub-second component so truncation to whole seconds is exercised. The
    // whole-second offset keeps the instant in a realistic window (~1970..2065) and
    // the nanosecond component (0..999,999,999) forces a non-zero sub-second part
    // on most cases.
    val injectedInstant: Arb<Instant> = Arb.long(0L..3_000_000_000L).flatMap { secs ->
        Arb.int(0..999_999_999).map { nanos ->
            Instant.ofEpochSecond(secs, nanos.toLong())
        }
    }

    // endregion

    // region Helpers

    // The expected UTC one-second-precision instant for a given injected instant:
    // floored (truncated) to a whole second.
    fun expectedCapturedAt(injected: Instant): Instant =
        injected.truncatedTo(ChronoUnit.SECONDS)

    // endregion

    "Feature: sagd-welltest-mobile, Property 13: Valid log association and timestamp" {
        checkAll(
            pbtConfig(),
            wellId,
            injectedInstant,
            // All-valid production fields.
            inRange(WellheadLogFieldRange.PRODUCTION_MIN, WellheadLogFieldRange.PRODUCTION_MAX),
            inRange(WellheadLogFieldRange.PRODUCTION_MIN, WellheadLogFieldRange.PRODUCTION_MAX),
            inRange(WellheadLogFieldRange.PRODUCTION_MIN, WellheadLogFieldRange.PRODUCTION_MAX),
            inRange(WellheadLogFieldRange.WATER_CUT_MIN, WellheadLogFieldRange.WATER_CUT_MAX),
            // All-valid injection fields.
            inRange(WellheadLogFieldRange.STEAM_PRESSURE_MIN, WellheadLogFieldRange.STEAM_PRESSURE_MAX),
            inRange(WellheadLogFieldRange.STEAM_TEMPERATURE_MIN, WellheadLogFieldRange.STEAM_TEMPERATURE_MAX),
            inRange(WellheadLogFieldRange.STEAM_FLOW_MIN, WellheadLogFieldRange.STEAM_FLOW_MAX),
        ) { id, injected, emulsion, casing, esp, water, pressure, temp, flow ->
            val expected = expectedCapturedAt(injected)

            // Inject the known clock so capturedAt is deterministic. The injected
            // instant carries a sub-second component; the validator must truncate it.
            val productionValidator = DefaultProductionLogValidator(now = { injected })
            val injectionValidator = DefaultInjectionLogValidator(now = { injected })

            // --- Production branch. ---
            val productionInput = ProductionLogInput(
                wellId = id,
                emulsionFlowRate = emulsion,
                casingGasPressure = casing,
                espMotorTemperature = esp,
                waterCutPercent = water,
            )
            when (val result = productionValidator.validate(productionInput)) {
                is ValidationResult.Invalid ->
                    fail("all-valid production input unexpectedly rejected: ${result.errors}")
                is ValidationResult.Valid -> {
                    val log = result.value
                    withClue("production log wellId ${log.wellId} != input $id") {
                        log.wellId shouldBe id
                    }
                    withClue("production log logType is ${log.logType}, expected PRODUCTION") {
                        log.logType shouldBe LogType.PRODUCTION
                    }
                    withClue("production capturedAt ${log.capturedAt} has a sub-second component") {
                        log.capturedAt.nano shouldBe 0
                    }
                    withClue("production capturedAt ${log.capturedAt} != truncated instant $expected") {
                        log.capturedAt shouldBe expected
                    }
                }
            }

            // --- Injection branch. ---
            val injectionInput = InjectionLogInput(
                wellId = id,
                steamInjectionPressure = pressure,
                steamTemperature = temp,
                steamFlowRate = flow,
            )
            when (val result = injectionValidator.validate(injectionInput)) {
                is ValidationResult.Invalid ->
                    fail("all-valid injection input unexpectedly rejected: ${result.errors}")
                is ValidationResult.Valid -> {
                    val log = result.value
                    withClue("injection log wellId ${log.wellId} != input $id") {
                        log.wellId shouldBe id
                    }
                    withClue("injection log logType is ${log.logType}, expected INJECTION") {
                        log.logType shouldBe LogType.INJECTION
                    }
                    withClue("injection capturedAt ${log.capturedAt} has a sub-second component") {
                        log.capturedAt.nano shouldBe 0
                    }
                    withClue("injection capturedAt ${log.capturedAt} != truncated instant $expected") {
                        log.capturedAt shouldBe expected
                    }
                }
            }
        }
    }
})
