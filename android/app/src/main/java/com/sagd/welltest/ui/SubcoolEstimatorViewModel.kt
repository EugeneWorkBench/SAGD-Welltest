/*
 * UI layer — subcool estimator view model.
 *
 * Holds all logic for the subcool estimator screen so the composable stays
 * thin: it renders [SubcoolEstimatorUiState] and forwards raw intents (edit a
 * field, tap Estimate). Mirrors the iOS task 20.2 `SubcoolEstimatorViewModel`
 * exactly.
 *
 * Responsibilities (design.md "Subcool Estimator"):
 *   - Keep the as-entered field strings so an out-of-range or invalid
 *     submission retains the operator's values (Req 5.4, 5.5).
 *   - On Estimate, call the pure [SubcoolEstimating] use case and map its
 *     [SubcoolResult] onto a renderable [SubcoolDisplayState]. The calculation
 *     is synchronous and pure, so the value is available within the same frame,
 *     comfortably inside the 1-second display budget (Req 5.2).
 *   - A [SubcoolDisplayState.Value] carries the flashing flag that drives the
 *     distinguished flashing-zone alert state (Req 5.3).
 *
 * State is exposed as a single immutable [StateFlow]; the view model never
 * exposes mutable fields. No I/O.
 *
 * Implemented by task 21.2.
 * Requirements: 5.2, 5.3, 5.4, 5.5.
 */
package com.sagd.welltest.ui

import androidx.lifecycle.ViewModel
import com.sagd.welltest.domain.SubcoolEstimating
import com.sagd.welltest.domain.SubcoolEstimator
import com.sagd.welltest.domain.SubcoolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the subcool screen renders after a submit. Derived from the pure
 * [SubcoolResult] so the composable has nothing to decide beyond layout.
 */
sealed interface SubcoolDisplayState {
    /** No submit yet (or the form was cleared): show the empty prompt. */
    data object Idle : SubcoolDisplayState

    /**
     * A computed value. [isFlashing] drives the distinguished alert state
     * (Req 5.3).
     */
    data class Value(val subcool: Double, val isFlashing: Boolean) : SubcoolDisplayState

    /**
     * The casing pressure is outside the steam-table domain (Req 5.4). Carries
     * the offending field identifier so the UI can anchor the message.
     */
    data class OutOfRange(val field: String) : SubcoolDisplayState

    /**
     * An input was empty or non-numeric (Req 5.5). Carries the offending field
     * identifier.
     */
    data class InvalidInput(val field: String) : SubcoolDisplayState
}

/**
 * Immutable snapshot the subcool screen renders.
 *
 * Field values are kept as the raw strings the operator typed so the form can
 * echo them back verbatim across a submit (Req 5.4, 5.5) and the pure estimator
 * can enforce the numeric grammar.
 *
 * @property casingPressure casing pressure as entered (kPa).
 * @property pumpTemperature pump temperature as entered (°C).
 * @property displayState the result of the latest estimate; starts
 *   [SubcoolDisplayState.Idle].
 */
data class SubcoolEstimatorUiState(
    val casingPressure: String = "",
    val pumpTemperature: String = "",
    val displayState: SubcoolDisplayState = SubcoolDisplayState.Idle,
)

/**
 * Drives the subcool estimator screen.
 *
 * @param estimator the pure subcool estimator; defaults to the standard
 *   [SubcoolEstimator] over the bundled steam table.
 */
class SubcoolEstimatorViewModel(
    private val estimator: SubcoolEstimating = SubcoolEstimator(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubcoolEstimatorUiState())

    /** The single source of UI truth for the screen. */
    val uiState: StateFlow<SubcoolEstimatorUiState> = _uiState.asStateFlow()

    // region Field edits

    /**
     * Each field edit keeps the raw string verbatim and resets the result back
     * to [SubcoolDisplayState.Idle] so a stale value, alert, or message never
     * lingers once the operator changes an input. The numeric grammar is
     * enforced only at Estimate time by the estimator, which lets the operator
     * type freely.
     */
    fun onCasingPressureChange(value: String) =
        _uiState.update { it.copy(casingPressure = value, displayState = SubcoolDisplayState.Idle) }

    fun onPumpTemperatureChange(value: String) =
        _uiState.update { it.copy(pumpTemperature = value, displayState = SubcoolDisplayState.Idle) }

    // endregion

    // region Estimate

    /**
     * Compute the subcool from the entered fields and update the display state.
     * The field strings are left untouched so an out-of-range or invalid
     * submission retains the operator's entries (Req 5.4, 5.5).
     *
     * Parsing uses the estimator's shared numeric grammar
     * ([SubcoolEstimator.parseNumeric]) so empty/non-numeric inputs map to
     * [SubcoolResult.InvalidInput] naming the offending field — checking casing
     * pressure before pump temperature (Req 5.5) — before delegating the typed
     * estimate to the injected [SubcoolEstimating]. Doing the parse here (rather
     * than relying on the concrete class's string overload) keeps behavior
     * identical whatever implementation is injected, including test doubles.
     */
    fun estimate() {
        val state = _uiState.value
        val result = when (val r = runEstimate(state)) {
            is SubcoolResult.Success ->
                SubcoolDisplayState.Value(subcool = r.subcoolValue, isFlashing = r.isFlashing)
            is SubcoolResult.OutOfRange -> SubcoolDisplayState.OutOfRange(r.field)
            is SubcoolResult.InvalidInput -> SubcoolDisplayState.InvalidInput(r.field)
        }
        _uiState.update { it.copy(displayState = result) }
    }

    private fun runEstimate(state: SubcoolEstimatorUiState): SubcoolResult {
        val pressure = SubcoolEstimator.parseNumeric(state.casingPressure)
            ?: return SubcoolResult.InvalidInput(SubcoolEstimator.FIELD_CASING_PRESSURE)
        val pumpTemp = SubcoolEstimator.parseNumeric(state.pumpTemperature)
            ?: return SubcoolResult.InvalidInput(SubcoolEstimator.FIELD_PUMP_TEMPERATURE)
        return estimator.estimate(casingPressure = pressure, pumpTemperature = pumpTemp)
    }

    // endregion

    companion object {
        /** A human label for an offending field identifier. */
        fun fieldLabel(field: String): String = when (field) {
            SubcoolEstimator.FIELD_CASING_PRESSURE -> "Casing pressure"
            SubcoolEstimator.FIELD_PUMP_TEMPERATURE -> "Pump temperature"
            else -> field
        }

        /** The subcool value formatted for display (one decimal place, °C). */
        fun formattedSubcool(value: Double): String = "%.1f °C".format(value)
    }
}
