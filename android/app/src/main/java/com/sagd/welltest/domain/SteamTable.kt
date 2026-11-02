/*
 * Bundled saturated-steam table and its lookup/interpolation logic.
 *
 * The table is a monotonic dataset of (saturation pressure -> saturation
 * temperature) pairs shipped with the app. Saturation temperature increases
 * monotonically with saturation pressure, a relationship linear interpolation
 * preserves exactly. See design.md "Subcool Estimator" > "Steam-table lookup
 * and interpolation".
 *
 * IMPORTANT: these values are shared verbatim with the iOS client so the two
 * platforms compute identical saturation temperatures for the same pressure.
 * Any change here must be made identically on iOS (SteamTable.swift) and the
 * subcool property tests re-run on both platforms. Pressure is in kPa
 * (absolute), temperature in degrees Celsius.
 */
package com.sagd.welltest.domain

/** A single saturation point: [pressureKpa] (absolute) maps to [temperatureCelsius]. */
data class SteamTablePoint(
    val pressureKpa: Double,
    val temperatureCelsius: Double,
)

/**
 * A monotonic saturated-steam table supporting bracketed linear interpolation.
 *
 * The table enforces at construction that pressures are strictly increasing and
 * temperatures are non-decreasing, so [saturationTemperature] is well defined
 * and monotonic across the whole domain (Req 5.1 monotonicity).
 */
class SteamTable(points: List<SteamTablePoint>) {

    /** Points sorted and validated to be strictly increasing in pressure. */
    val points: List<SteamTablePoint>

    init {
        require(points.size >= 2) { "SteamTable requires at least two points." }
        val sorted = points.sortedBy { it.pressureKpa }
        for (i in 1 until sorted.size) {
            require(sorted[i].pressureKpa > sorted[i - 1].pressureKpa) {
                "SteamTable pressures must be strictly increasing; " +
                    "duplicate or unordered pressure at ${sorted[i].pressureKpa} kPa."
            }
            require(sorted[i].temperatureCelsius >= sorted[i - 1].temperatureCelsius) {
                "SteamTable temperatures must be non-decreasing with pressure; " +
                    "violation at ${sorted[i].pressureKpa} kPa."
            }
        }
        this.points = sorted
    }

    /** Lowest pressure covered by the table (inclusive lower bound of the domain). */
    val minPressure: Double get() = points.first().pressureKpa

    /** Highest pressure covered by the table (inclusive upper bound of the domain). */
    val maxPressure: Double get() = points.last().pressureKpa

    /**
     * Saturation temperature at [pressure] via bracketed linear interpolation,
     * or `null` when [pressure] is outside `[minPressure, maxPressure]` or is
     * not a finite number (Req 5.4).
     *
     * An exact table-point pressure returns that point's temperature; otherwise
     * the result is `T0 + (P - P0) * (T1 - T0) / (P1 - P0)` for the bracketing
     * pair `(P0, T0)`, `(P1, T1)` with `P0 <= P <= P1`.
     */
    fun saturationTemperature(pressure: Double): Double? {
        if (!pressure.isFinite()) return null
        if (pressure < minPressure || pressure > maxPressure) return null

        // Exact lower bound.
        if (pressure == minPressure) return points.first().temperatureCelsius
        // Exact upper bound.
        if (pressure == maxPressure) return points.last().temperatureCelsius

        // Find the bracketing pair such that p0 <= pressure <= p1.
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].pressureKpa <= pressure) lo = mid else hi = mid
        }

        val (p0, t0) = points[lo].let { it.pressureKpa to it.temperatureCelsius }
        val (p1, t1) = points[hi].let { it.pressureKpa to it.temperatureCelsius }

        // Exact table point lands on p0.
        if (pressure == p0) return t0
        if (pressure == p1) return t1
        if (p1 == p0) return t0

        return t0 + (pressure - p0) * (t1 - t0) / (p1 - p0)
    }

    companion object {
        /**
         * The steam table bundled with the app. Shared verbatim with iOS.
         *
         * Saturated water/steam values over the field operating range: absolute
         * pressure in kPa paired with saturation temperature in degrees Celsius.
         * The set is dense enough that linear interpolation error is negligible
         * for field use, as noted in the design.
         */
        val bundled: SteamTable = SteamTable(
            listOf(
                SteamTablePoint(10.0, 45.81),
                SteamTablePoint(20.0, 60.06),
                SteamTablePoint(30.0, 69.10),
                SteamTablePoint(40.0, 75.87),
                SteamTablePoint(50.0, 81.33),
                SteamTablePoint(75.0, 91.78),
                SteamTablePoint(100.0, 99.61),
                SteamTablePoint(101.325, 99.97),
                SteamTablePoint(125.0, 105.97),
                SteamTablePoint(150.0, 111.35),
                SteamTablePoint(200.0, 120.21),
                SteamTablePoint(250.0, 127.41),
                SteamTablePoint(300.0, 133.52),
                SteamTablePoint(400.0, 143.61),
                SteamTablePoint(500.0, 151.84),
                SteamTablePoint(600.0, 158.83),
                SteamTablePoint(700.0, 164.95),
                SteamTablePoint(800.0, 170.41),
                SteamTablePoint(900.0, 175.36),
                SteamTablePoint(1000.0, 179.88),
                SteamTablePoint(1250.0, 189.81),
                SteamTablePoint(1500.0, 198.29),
                SteamTablePoint(1750.0, 205.72),
                SteamTablePoint(2000.0, 212.38),
                SteamTablePoint(2500.0, 223.95),
                SteamTablePoint(3000.0, 233.85),
                SteamTablePoint(3500.0, 242.56),
                SteamTablePoint(4000.0, 250.35),
                SteamTablePoint(5000.0, 263.94),
                SteamTablePoint(6000.0, 275.59),
                SteamTablePoint(7000.0, 285.83),
                SteamTablePoint(8000.0, 295.01),
                SteamTablePoint(9000.0, 303.35),
                SteamTablePoint(10000.0, 311.00),
            ),
        )
    }
}
