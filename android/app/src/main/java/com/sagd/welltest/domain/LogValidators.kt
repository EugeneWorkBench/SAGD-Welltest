/*
 * Production and injection wellhead-log validators.
 *
 * Pure, platform-agnostic field validators for the two wellhead-log forms,
 * mirroring the iOS `LogValidators.swift` behavior exactly so both platforms
 * validate identically. Each validator runs the same two-stage check on every
 * numeric field — a numeric-format check (digits, at most one decimal point,
 * optional single leading minus) followed by a per-field range check — and then
 * either normalizes the input into a [WellheadLog] (associating the selected
 * well identifier and a UTC, one-second-precision capture timestamp) or reports
 * exactly the set of offending fields while the UI retains the entered values.
 *
 * Deterministic functions over their inputs with no I/O. Implemented by task
 * 12.1.
 *
 * Requirements: 3.2, 3.3, 3.4, 3.5, 3.6, 4.2, 4.3, 4.4, 4.5.
 * Correctness properties: 10 (numeric-format), 11 (per-field range),
 * 12 (save blocked exactly on invalid fields), 13 (association + timestamp).
 */
package com.sagd.welltest.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// region Numeric field parsing

/**
 * Shared, pure numeric-field parsing used by both log validators.
 *
 * A field is parsed in two stages so that it reports the *first* reason it is
 * unacceptable, matching the per-field validation messages the UI shows and the
 * iOS `NumericFieldParser`:
 *
 * 1. **Presence** — an empty (or whitespace-only) string is [FieldError.Kind.Empty].
 * 2. **Numeric format** — the trimmed string must consist solely of digits with
 *    at most one decimal point and an optional single leading minus sign;
 *    otherwise it is [FieldError.Kind.NotNumeric] (Req 3.3, 4.2; Property 10).
 * 3. **Range** — the parsed value must lie within the field's inclusive
 *    `[min, max]` range; otherwise it is [FieldError.Kind.OutOfRange]
 *    (Req 3.2, 3.4, 4.3; Property 11).
 */
internal object NumericFieldParser {

    /** The outcome of parsing a single as-entered numeric field. */
    sealed interface Outcome {
        /** The field parsed and lies within range; carries the value. */
        data class Value(val value: Double) : Outcome

        /** The field failed; carries the specific reason. */
        data class Failure(val kind: FieldError.Kind) : Outcome
    }

    /**
     * Parse a raw field string against an inclusive `[min, max]` range.
     *
     * The numeric-format grammar accepted here is deliberately strict and
     * independent of locale: an optional single leading `-`, one or more digits,
     * and at most one `.` decimal point. This is the exact grammar the
     * requirements describe ("digits, a single decimal point, or a leading minus
     * sign") rather than the looser set [String.toDoubleOrNull] would accept
     * (which permits exponents, `+`, grouping separators, `d`/`f` suffixes,
     * `Infinity`/`NaN`, etc.). Leading/trailing whitespace is trimmed before the
     * grammar check so surrounding spaces are tolerated but interior non-numeric
     * characters are not.
     */
    fun parse(raw: String, min: Double, max: Double): Outcome {
        // Trim only spaces and horizontal tabs, matching the iOS
        // `.whitespaces` character set (which excludes newlines) so a value
        // containing an interior/trailing newline is rejected identically on
        // both platforms rather than silently trimmed.
        val trimmed = raw.trim(' ', '\t')

        // 1. Presence.
        if (trimmed.isEmpty()) {
            return Outcome.Failure(FieldError.Kind.Empty)
        }

        // 2. Numeric format.
        val value = if (isWellFormedNumber(trimmed)) trimmed.toDoubleOrNull() else null
        if (value == null) {
            return Outcome.Failure(FieldError.Kind.NotNumeric)
        }

        // A value that parses but is not finite (should not occur under the
        // grammar, which admits no infinities or NaN literals) is treated as
        // non-numeric defensively.
        if (!value.isFinite()) {
            return Outcome.Failure(FieldError.Kind.NotNumeric)
        }

        // 3. Range (inclusive bounds).
        if (value < min || value > max) {
            return Outcome.Failure(FieldError.Kind.OutOfRange(min = min, max = max))
        }

        return Outcome.Value(value)
    }

    /**
     * Whether [s] matches the accepted numeric grammar: an optional single
     * leading minus, one or more digits, and at most one decimal point. The
     * decimal point may appear between digits or after them, and at least one
     * digit must be present overall (so `"-"`, `"."`, `"-."`, `"--1"`, `"1.2.3"`,
     * and `"1e3"` are all rejected). This matches the iOS `isWellFormedNumber`
     * character scan exactly.
     */
    private fun isWellFormedNumber(s: String): Boolean {
        var start = 0

        // Optional single leading minus.
        if (s.isNotEmpty() && s[0] == '-') {
            start = 1
        }

        // Nothing after an optional sign is not a number.
        if (start >= s.length) {
            return false
        }

        var sawDigit = false
        var sawDot = false
        for (i in start until s.length) {
            val ch = s[i]
            when {
                ch == '.' -> {
                    if (sawDot) return false // more than one decimal point
                    sawDot = true
                }
                ch in '0'..'9' -> sawDigit = true
                else -> return false // any other character (sign, exponent, etc.)
            }
        }

        // At least one digit is required (rejects "-", ".", "-.").
        return sawDigit
    }
}

// endregion

// region Field ranges

/**
 * The inclusive numeric ranges for each wellhead-log field, in one place so the
 * validators and their tests share a single source of truth.
 *
 * Requirements: water cut [0, 100] (Req 3.2); production emulsion flow rate,
 * casing gas pressure, and ESP motor temperature [0.00, 999,999.99] (Req 3.4);
 * steam injection pressure [0, 25,000], steam temperature [0, 400], steam flow
 * rate [0, 2,000] (Req 4.3).
 */
object WellheadLogFieldRange {
    /**
     * Production fields (emulsion flow rate, casing gas pressure, ESP motor
     * temperature): [0.00, 999,999.99].
     */
    const val PRODUCTION_MIN: Double = 0.0
    const val PRODUCTION_MAX: Double = 999_999.99

    /** Water cut percentage: [0, 100]. */
    const val WATER_CUT_MIN: Double = 0.0
    const val WATER_CUT_MAX: Double = 100.0

    /** Steam injection pressure (kPa): [0, 25,000]. */
    const val STEAM_PRESSURE_MIN: Double = 0.0
    const val STEAM_PRESSURE_MAX: Double = 25_000.0

    /** Steam temperature (°C): [0, 400]. */
    const val STEAM_TEMPERATURE_MIN: Double = 0.0
    const val STEAM_TEMPERATURE_MAX: Double = 400.0

    /** Steam flow rate (m³/day): [0, 2,000]. */
    const val STEAM_FLOW_MIN: Double = 0.0
    const val STEAM_FLOW_MAX: Double = 2_000.0
}

// endregion

// region Field identifiers

/**
 * Stable field identifiers used in [FieldError.field] so the UI can map a
 * validation failure back to the specific input, and so the offending-field set
 * is deterministic and testable. These strings match the iOS
 * `WellheadLogField` constants verbatim.
 */
object WellheadLogField {
    const val EMULSION_FLOW_RATE: String = "emulsionFlowRate"
    const val CASING_GAS_PRESSURE: String = "casingGasPressure"
    const val ESP_MOTOR_TEMPERATURE: String = "espMotorTemperature"
    const val WATER_CUT_PERCENT: String = "waterCutPercent"

    const val STEAM_INJECTION_PRESSURE: String = "steamInjectionPressure"
    const val STEAM_TEMPERATURE: String = "steamTemperature"
    const val STEAM_FLOW_RATE: String = "steamFlowRate"
}

// endregion

// region Timestamp normalization

/**
 * Truncates an [Instant] to one-second precision in UTC.
 *
 * Both log timestamps are "recorded to the second in UTC" (Req 3.6, 4.5). An
 * [Instant] is an absolute instant on the UTC timeline, so "UTC" here governs
 * how the instant is rendered; the value stored is the same instant truncated to
 * a whole second. [ChronoUnit.SECONDS] truncation drops the sub-second field,
 * keeping the capture time at or before the moment of capture — matching the
 * iOS floor-to-whole-second behavior.
 */
fun utcSecondTruncated(instant: Instant): Instant =
    instant.truncatedTo(ChronoUnit.SECONDS)

// endregion

// region Production log validator

/**
 * Validates an as-entered production wellhead log.
 *
 * Every field (emulsion flow rate, casing gas pressure, ESP motor temperature,
 * water cut percentage) is required and must be numeric and in range. On success
 * the input is normalized into a [WellheadLog] with `logType == LogType.PRODUCTION`,
 * the selected well identifier, and a UTC one-second-precision `capturedAt`. On
 * failure the result carries exactly the offending fields (one [FieldError] per
 * field) so the UI blocks the save and retains the entered values.
 *
 * @property now supplies the capture instant; injectable so tests can assert the
 *   association-and-timestamp property deterministically (defaults to [Instant.now]).
 * @property newId supplies the locally generated primary key; injectable for
 *   deterministic tests (defaults to [UUID.randomUUID]).
 */
class DefaultProductionLogValidator(
    private val now: () -> Instant = Instant::now,
    private val newId: () -> UUID = UUID::randomUUID,
) : ProductionLogValidator {

    override fun validate(fields: ProductionLogInput): ValidationResult<WellheadLog> {
        val errors = mutableListOf<FieldError>()

        val emulsion = field(
            WellheadLogField.EMULSION_FLOW_RATE, fields.emulsionFlowRate,
            WellheadLogFieldRange.PRODUCTION_MIN, WellheadLogFieldRange.PRODUCTION_MAX,
            errors,
        )
        val casing = field(
            WellheadLogField.CASING_GAS_PRESSURE, fields.casingGasPressure,
            WellheadLogFieldRange.PRODUCTION_MIN, WellheadLogFieldRange.PRODUCTION_MAX,
            errors,
        )
        val esp = field(
            WellheadLogField.ESP_MOTOR_TEMPERATURE, fields.espMotorTemperature,
            WellheadLogFieldRange.PRODUCTION_MIN, WellheadLogFieldRange.PRODUCTION_MAX,
            errors,
        )
        val waterCut = field(
            WellheadLogField.WATER_CUT_PERCENT, fields.waterCutPercent,
            WellheadLogFieldRange.WATER_CUT_MIN, WellheadLogFieldRange.WATER_CUT_MAX,
            errors,
        )

        if (errors.isNotEmpty() ||
            emulsion == null || casing == null || esp == null || waterCut == null
        ) {
            return ValidationResult.Invalid(errors)
        }

        val capturedAt = utcSecondTruncated(now())
        val log = WellheadLog(
            id = newId(),
            wellId = fields.wellId,
            logType = LogType.PRODUCTION,
            capturedAt = capturedAt,
            emulsionFlowRate = emulsion,
            casingGasPressure = casing,
            espMotorTemperature = esp,
            waterCutPercent = waterCut,
            sync = SyncMetadata(createdAt = capturedAt),
        )
        return ValidationResult.Valid(log)
    }
}

// endregion

// region Injection log validator

/**
 * Validates an as-entered injection wellhead log.
 *
 * Every field (steam injection pressure, steam temperature, steam flow rate) is
 * required and must be numeric and in range. On success the input is normalized
 * into a [WellheadLog] with `logType == LogType.INJECTION`, the selected well
 * identifier, and a UTC one-second-precision `capturedAt`. On failure the result
 * carries exactly the offending fields so the UI blocks the save and retains the
 * entered values.
 *
 * @property now supplies the capture instant; injectable so tests can assert the
 *   association-and-timestamp property deterministically (defaults to [Instant.now]).
 * @property newId supplies the locally generated primary key; injectable for
 *   deterministic tests (defaults to [UUID.randomUUID]).
 */
class DefaultInjectionLogValidator(
    private val now: () -> Instant = Instant::now,
    private val newId: () -> UUID = UUID::randomUUID,
) : InjectionLogValidator {

    override fun validate(fields: InjectionLogInput): ValidationResult<WellheadLog> {
        val errors = mutableListOf<FieldError>()

        val pressure = field(
            WellheadLogField.STEAM_INJECTION_PRESSURE, fields.steamInjectionPressure,
            WellheadLogFieldRange.STEAM_PRESSURE_MIN, WellheadLogFieldRange.STEAM_PRESSURE_MAX,
            errors,
        )
        val temperature = field(
            WellheadLogField.STEAM_TEMPERATURE, fields.steamTemperature,
            WellheadLogFieldRange.STEAM_TEMPERATURE_MIN, WellheadLogFieldRange.STEAM_TEMPERATURE_MAX,
            errors,
        )
        val flow = field(
            WellheadLogField.STEAM_FLOW_RATE, fields.steamFlowRate,
            WellheadLogFieldRange.STEAM_FLOW_MIN, WellheadLogFieldRange.STEAM_FLOW_MAX,
            errors,
        )

        if (errors.isNotEmpty() || pressure == null || temperature == null || flow == null) {
            return ValidationResult.Invalid(errors)
        }

        val capturedAt = utcSecondTruncated(now())
        val log = WellheadLog(
            id = newId(),
            wellId = fields.wellId,
            logType = LogType.INJECTION,
            capturedAt = capturedAt,
            steamInjectionPressure = pressure,
            steamTemperature = temperature,
            steamFlowRate = flow,
            sync = SyncMetadata(createdAt = capturedAt),
        )
        return ValidationResult.Valid(log)
    }
}

// endregion

/**
 * Parses one field, appending a [FieldError] to [errors] on failure and
 * returning the parsed value on success. Shared by both log validators so the
 * per-field two-stage check is identical.
 */
private fun field(
    name: String,
    raw: String,
    min: Double,
    max: Double,
    errors: MutableList<FieldError>,
): Double? = when (val outcome = NumericFieldParser.parse(raw, min, max)) {
    is NumericFieldParser.Outcome.Value -> outcome.value
    is NumericFieldParser.Outcome.Failure -> {
        errors.add(FieldError.of(name, outcome.kind))
        null
    }
}
