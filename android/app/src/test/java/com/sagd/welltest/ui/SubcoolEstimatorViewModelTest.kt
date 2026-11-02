/*
 * Unit tests for [SubcoolEstimatorViewModel] (task 21.2).
 *
 * Exercises the view model's mapping of the pure estimator's [SubcoolResult]
 * onto [SubcoolDisplayState]: the computed value and the distinguished flashing
 * flag (Req 5.2, 5.3), the out-of-range branch (Req 5.4), and the
 * empty/non-numeric branch with the offending field named and entered values
 * retained (Req 5.5). The estimator itself is the real pure
 * [SubcoolEstimator] over the bundled steam table — no mocking of domain logic.
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.SteamTable
import com.sagd.welltest.domain.SubcoolEstimator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SubcoolEstimatorViewModelTest : StringSpec({

    val table = SteamTable.bundled

    fun viewModel() = SubcoolEstimatorViewModel(SubcoolEstimator(table, flashingThreshold = 10.0))

    "starts idle" {
        viewModel().uiState.value.displayState shouldBe SubcoolDisplayState.Idle
    }

    "a valid non-flashing submission renders the computed value (Req 5.2)" {
        val vm = viewModel()
        vm.onCasingPressureChange("1000")
        vm.onPumpTemperatureChange("100")
        vm.estimate()

        val display = vm.uiState.value.displayState
        display.shouldBeInstanceOf<SubcoolDisplayState.Value>()
        // Tsat(1000 kPa) = 179.88; subcool = 79.88, well above the threshold.
        display.subcool shouldBe (79.88 plusOrMinus 1e-6)
        display.isFlashing shouldBe false
    }

    "a subcool at or below the threshold renders the flashing alert (Req 5.3)" {
        val vm = viewModel()
        vm.onCasingPressureChange("1000")
        vm.onPumpTemperatureChange("169.88") // subcool == 10.0 == threshold
        vm.estimate()

        val display = vm.uiState.value.displayState
        display.shouldBeInstanceOf<SubcoolDisplayState.Value>()
        display.isFlashing shouldBe true
    }

    "a pressure outside the steam-table domain renders out-of-range and retains inputs (Req 5.4)" {
        val vm = viewModel()
        vm.onCasingPressureChange((table.maxPressure + 1.0).toString())
        vm.onPumpTemperatureChange("100")
        vm.estimate()

        vm.uiState.value.displayState shouldBe
            SubcoolDisplayState.OutOfRange(SubcoolEstimator.FIELD_CASING_PRESSURE)
        // Entered values retained.
        vm.uiState.value.casingPressure shouldBe (table.maxPressure + 1.0).toString()
        vm.uiState.value.pumpTemperature shouldBe "100"
    }

    "an empty casing pressure renders invalid-input naming that field (Req 5.5)" {
        val vm = viewModel()
        vm.onPumpTemperatureChange("100")
        vm.estimate()

        vm.uiState.value.displayState shouldBe
            SubcoolDisplayState.InvalidInput(SubcoolEstimator.FIELD_CASING_PRESSURE)
    }

    "a non-numeric pump temperature renders invalid-input naming that field (Req 5.5)" {
        val vm = viewModel()
        vm.onCasingPressureChange("1000")
        vm.onPumpTemperatureChange("abc")
        vm.estimate()

        vm.uiState.value.displayState shouldBe
            SubcoolDisplayState.InvalidInput(SubcoolEstimator.FIELD_PUMP_TEMPERATURE)
    }

    "casing pressure is checked before pump temperature (Req 5.5)" {
        val vm = viewModel()
        vm.onCasingPressureChange("bad")
        vm.onPumpTemperatureChange("alsoBad")
        vm.estimate()

        vm.uiState.value.displayState shouldBe
            SubcoolDisplayState.InvalidInput(SubcoolEstimator.FIELD_CASING_PRESSURE)
    }

    "editing a field after a result resets the display to idle" {
        val vm = viewModel()
        vm.onCasingPressureChange("1000")
        vm.onPumpTemperatureChange("100")
        vm.estimate()
        vm.uiState.value.displayState.shouldBeInstanceOf<SubcoolDisplayState.Value>()

        vm.onPumpTemperatureChange("120")
        vm.uiState.value.displayState shouldBe SubcoolDisplayState.Idle
    }
})
