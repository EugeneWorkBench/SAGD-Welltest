package com.sagd.welltest.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Example-based unit tests for [SteamTable] and [SubcoolEstimator] (Req 5.1,
 * 5.3, 5.4, 5.5). Property-based coverage lives in tasks 11.2-11.6.
 */
class SubcoolEstimatorTest : StringSpec({

    val table = SteamTable.bundled

    // --- SteamTable lookup / interpolation (Req 5.1, 5.4) ---

    "saturationTemperature returns the exact point value at a table pressure" {
        table.saturationTemperature(1000.0)!! shouldBe (179.88 plusOrMinus 1e-9)
    }

    "saturationTemperature at the lower domain bound returns the first point" {
        table.saturationTemperature(table.minPressure)!! shouldBe (45.81 plusOrMinus 1e-9)
    }

    "saturationTemperature at the upper domain bound returns the last point" {
        table.saturationTemperature(table.maxPressure)!! shouldBe (311.00 plusOrMinus 1e-9)
    }

    "saturationTemperature linearly interpolates between bracketing points" {
        // Midway between 1000 kPa (179.88) and 1250 kPa (189.81) at 1125 kPa.
        val expected = 179.88 + (1125.0 - 1000.0) * (189.81 - 179.88) / (1250.0 - 1000.0)
        table.saturationTemperature(1125.0)!! shouldBe (expected plusOrMinus 1e-9)
    }

    "saturationTemperature is null below the domain" {
        table.saturationTemperature(table.minPressure - 0.001).shouldBeNull()
    }

    "saturationTemperature is null above the domain" {
        table.saturationTemperature(table.maxPressure + 0.001).shouldBeNull()
    }

    "saturationTemperature is null for a non-finite pressure" {
        table.saturationTemperature(Double.NaN).shouldBeNull()
        table.saturationTemperature(Double.POSITIVE_INFINITY).shouldBeNull()
    }

    "saturationTemperature is monotonic non-decreasing across sampled pressures" {
        var previous = Double.NEGATIVE_INFINITY
        var p = table.minPressure
        while (p <= table.maxPressure) {
            val t = table.saturationTemperature(p)!!
            t shouldBeGreaterThanOrEqual previous
            previous = t
            p += 37.0
        }
    }

    // --- Subcool definition (Req 5.1) ---

    "estimate computes subcool as Tsat minus pump temperature" {
        val tsat = table.saturationTemperature(1000.0)!!
        val result = SubcoolEstimator(table).estimate(casingPressure = 1000.0, pumpTemperature = 150.0)
        result.shouldBeInstanceOf<SubcoolResult.Success>()
        result.subcoolValue shouldBe ((tsat - 150.0) plusOrMinus 1e-9)
    }

    // --- Flashing-zone classification (Req 5.3) ---

    "estimate flags flashing when subcool is at or below the threshold" {
        // Tsat(1000 kPa) = 179.88; choose pump temp so subcool == threshold (10.0).
        val estimator = SubcoolEstimator(table, flashingThreshold = 10.0)
        val atThreshold = estimator.estimate(casingPressure = 1000.0, pumpTemperature = 169.88)
        atThreshold.shouldBeInstanceOf<SubcoolResult.Success>()
        atThreshold.subcoolValue shouldBe (10.0 plusOrMinus 1e-6)
        atThreshold.isFlashing shouldBe true
    }

    "estimate does not flag flashing when subcool exceeds the threshold" {
        val estimator = SubcoolEstimator(table, flashingThreshold = 10.0)
        val result = estimator.estimate(casingPressure = 1000.0, pumpTemperature = 100.0)
        result.shouldBeInstanceOf<SubcoolResult.Success>()
        result.isFlashing shouldBe false
    }

    // --- Out-of-range pressure rejection (Req 5.4) ---

    "estimate rejects a pressure below the steam-table domain" {
        val result = SubcoolEstimator(table)
            .estimate(casingPressure = table.minPressure - 1.0, pumpTemperature = 100.0)
        result shouldBe SubcoolResult.OutOfRange("casingPressure")
    }

    "estimate rejects a pressure above the steam-table domain" {
        val result = SubcoolEstimator(table)
            .estimate(casingPressure = table.maxPressure + 1.0, pumpTemperature = 100.0)
        result shouldBe SubcoolResult.OutOfRange("casingPressure")
    }

    // --- Invalid input rejection (Req 5.5) ---

    "estimate rejects a non-finite casing pressure as invalid input" {
        SubcoolEstimator(table).estimate(casingPressure = Double.NaN, pumpTemperature = 100.0) shouldBe
            SubcoolResult.InvalidInput("casingPressure")
    }

    "estimate rejects a non-finite pump temperature as invalid input" {
        SubcoolEstimator(table).estimate(casingPressure = 1000.0, pumpTemperature = Double.NaN) shouldBe
            SubcoolResult.InvalidInput("pumpTemperature")
    }

    "string estimate rejects an empty casing pressure naming that field" {
        SubcoolEstimator(table).estimate(casingPressure = "", pumpTemperature = "100") shouldBe
            SubcoolResult.InvalidInput("casingPressure")
    }

    "string estimate rejects an empty pump temperature naming that field" {
        SubcoolEstimator(table).estimate(casingPressure = "1000", pumpTemperature = "   ") shouldBe
            SubcoolResult.InvalidInput("pumpTemperature")
    }

    "string estimate rejects a non-numeric casing pressure" {
        SubcoolEstimator(table).estimate(casingPressure = "abc", pumpTemperature = "100") shouldBe
            SubcoolResult.InvalidInput("casingPressure")
    }

    "string estimate rejects a value with two decimal points" {
        SubcoolEstimator(table).estimate(casingPressure = "10.0.0", pumpTemperature = "100") shouldBe
            SubcoolResult.InvalidInput("casingPressure")
    }

    "string estimate checks casing pressure before pump temperature" {
        SubcoolEstimator(table).estimate(casingPressure = "bad", pumpTemperature = "alsoBad") shouldBe
            SubcoolResult.InvalidInput("casingPressure")
    }

    "string estimate parses valid numbers and succeeds" {
        val result = SubcoolEstimator(table).estimate(casingPressure = "1000", pumpTemperature = "150.5")
        result.shouldBeInstanceOf<SubcoolResult.Success>()
    }
})
