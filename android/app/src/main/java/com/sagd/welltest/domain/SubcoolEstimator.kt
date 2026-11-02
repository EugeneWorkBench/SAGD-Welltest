/*
 * Pure subcool estimator.
 *
 * Mirrors the iOS SubcoolEstimator exactly: a bundled monotonic steam table,
 * bracketed linear interpolation, a domain (out-of-range) check, and the three
 * result cases from design.md "Subcool Estimator" > "Algorithm". The flashing
 * threshold is injected so the flashing-zone classification is configurable
 * (Req 5.3). No I/O; deterministic over its inputs.
 *
 *   subcool   = saturationTemperature(casingPressure) - pumpTemperature   (Req 5.1)
 *   isFlashing = subcool <= flashingThreshold                              (Req 5.3)
 */
package com.sagd.welltest.domain

/**
 * Computes subcool and the flashing-zone flag from a casing pressure and pump
 * temperature against an injected [steamTable] and [flashingThreshold].
 *
 * @property steamTable the saturation table used for interpolation; defaults to
 *   the bundled table shared verbatim with iOS.
 * @property flashingThreshold the subcool value (degrees Celsius) at or below
 *   which a reading is classified as flashing (Req 5.3).
 */
class SubcoolEstimator(
    private val steamTable: SteamTable = SteamTable.bundled,
    private val flashingThreshold: Double = DEFAULT_FLASHING_THRESHOLD,
) : SubcoolEstimating {

    /**
     * Typed estimate over already-parsed numeric inputs (Req 5.1, 5.3, 5.4).
     *
     * A non-finite input (`NaN`, infinity) is treated as non-numeric and yields
     * [SubcoolResult.InvalidInput] (Req 5.5); a pressure outside the steam-table
     * domain yields [SubcoolResult.OutOfRange] (Req 5.4).
     */
    override fun estimate(casingPressure: Double, pumpTemperature: Double): SubcoolResult {
        if (!casingPressure.isFinite()) {
            return SubcoolResult.InvalidInput(FIELD_CASING_PRESSURE)
        }
        if (!pumpTemperature.isFinite()) {
            return SubcoolResult.InvalidInput(FIELD_PUMP_TEMPERATURE)
        }

        val tsat = steamTable.saturationTemperature(casingPressure)
            ?: return SubcoolResult.OutOfRange(FIELD_CASING_PRESSURE)

        val subcool = tsat - pumpTemperature
        val isFlashing = subcool <= flashingThreshold
        return SubcoolResult.Success(subcoolValue = subcool, isFlashing = isFlashing)
    }

    /**
     * Estimate over raw, as-entered strings, mirroring the iOS algorithm and the
     * design pseudocode: empty or non-numeric inputs are rejected with
     * [SubcoolResult.InvalidInput] naming exactly the offending field, checking
     * casing pressure before pump temperature (Req 5.5). Numeric inputs delegate
     * to [estimate].
     */
    fun estimate(casingPressure: String, pumpTemperature: String): SubcoolResult {
        val pressure = parseNumeric(casingPressure)
            ?: return SubcoolResult.InvalidInput(FIELD_CASING_PRESSURE)
        val pumpTemp = parseNumeric(pumpTemperature)
            ?: return SubcoolResult.InvalidInput(FIELD_PUMP_TEMPERATURE)
        return estimate(casingPressure = pressure, pumpTemperature = pumpTemp)
    }

    /** Interpolated saturation temperature, or null outside the domain (Req 5.4). */
    override fun saturationTemperature(pressure: Double): Double? =
        steamTable.saturationTemperature(pressure)

    companion object {
        /** Field identifier reported for an invalid/out-of-range casing pressure. */
        const val FIELD_CASING_PRESSURE: String = "casingPressure"

        /** Field identifier reported for an invalid pump temperature. */
        const val FIELD_PUMP_TEMPERATURE: String = "pumpTemperature"

        /**
         * Default flashing-zone threshold in degrees Celsius. A subcool at or
         * below this value is flashing (Req 5.3). Injectable via the constructor.
         */
        const val DEFAULT_FLASHING_THRESHOLD: Double = 10.0

        /**
         * Numeric-format parse shared with the field validators: optional single
         * leading minus, digits, at most one decimal point. Returns the parsed
         * finite [Double], or `null` when the string is empty, malformed, or not
         * finite. No locale-specific grouping or exponent syntax is accepted so
         * both platforms parse identically (Req 5.5).
         */
        fun parseNumeric(raw: String): Double? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (!NUMERIC_FORMAT.matches(trimmed)) return null
            val value = trimmed.toDoubleOrNull() ?: return null
            return if (value.isFinite()) value else null
        }

        /** Digits with an optional single leading minus and at most one decimal point. */
        private val NUMERIC_FORMAT = Regex("^-?(\\d+(\\.\\d*)?|\\.\\d+)$")
    }
}
