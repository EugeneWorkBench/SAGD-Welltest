/*
 * UI layer — wellhead-log capture view model.
 *
 * Holds all business logic for the well-selection + wellhead-log-form screens so
 * the composables stay thin: they render [WellheadLogUiState] and forward raw
 * user intents (select a well, edit a field, tap Save). Mirrors the iOS task
 * 20.1 view model exactly.
 *
 * Responsibilities (design.md "UI ↔ domain ↔ repository flow"):
 *   - Pick the production vs injection form from the selected [Well.type]
 *     (Req 3.1 / 4.1).
 *   - On Save, run the matching pure validator
 *     ([DefaultProductionLogValidator] / [DefaultInjectionLogValidator]). On
 *     [ValidationResult.Invalid] it blocks the save, keeps every entered value,
 *     and surfaces exactly the offending fields as per-field messages
 *     (Req 3.2-3.5, 4.2-4.4).
 *   - On [ValidationResult.Valid] it calls [LogRepository.saveWellheadLog] and
 *     maps the [SaveResult] to a success indicator (Req 1.1, shown well within
 *     2s because the repository save is a local transaction), a storage-full
 *     message (Req 1.5), or a generic failure message (Req 1.4) — in every
 *     non-success case the entered values are retained.
 *
 * All state is exposed as a single immutable [StateFlow] of [WellheadLogUiState];
 * the view model never exposes mutable fields. Validation and persistence are the
 * domain/repository layers' concern — this class only orchestrates them and maps
 * results to display state.
 */
package com.sagd.welltest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagd.welltest.domain.FieldError
import com.sagd.welltest.domain.InjectionLogInput
import com.sagd.welltest.domain.InjectionLogValidator
import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.ProductionLogInput
import com.sagd.welltest.domain.ProductionLogValidator
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.ValidationResult
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.domain.WellheadLogField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The status of the most recent save attempt. Drives the success indicator and
 * the error banner the form shows.
 */
sealed interface SaveStatus {
    /** No save attempted yet, or the form has been edited since the last attempt. */
    data object Idle : SaveStatus

    /** A save is in flight; the UI should disable the Save control. */
    data object Saving : SaveStatus

    /** The log was persisted locally; the UI shows the success indicator (Req 1.1). */
    data object Saved : SaveStatus

    /** The device is out of storage; values are retained (Req 1.5). */
    data object StorageFull : SaveStatus

    /** A non-storage I/O failure; values are retained (Req 1.4). */
    data class Failure(val message: String) : SaveStatus
}

/**
 * Immutable snapshot of everything the well-selection + log-form screens render.
 *
 * Field values are kept as the raw strings the operator typed so the form can
 * echo them back verbatim and the pure validators can enforce the numeric
 * grammar. [fieldErrors] maps a [WellheadLogField] identifier to the message for
 * that field, so the form can place a message beneath exactly the inputs that
 * failed and retain the others' values.
 *
 * @property selectedWell the well whose form is shown, or null on the selection
 *   screen. Its [Well.type] selects the production vs injection form.
 * @property emulsionFlowRate etc. raw production-field strings (meaningful when
 *   [selectedWell] is a production well).
 * @property steamInjectionPressure etc. raw injection-field strings (meaningful
 *   when [selectedWell] is an injection well).
 * @property fieldErrors per-field validation messages keyed by field id; empty
 *   when nothing is flagged.
 * @property saveStatus the outcome of the latest save attempt.
 */
data class WellheadLogUiState(
    val selectedWell: Well? = null,
    // Production fields.
    val emulsionFlowRate: String = "",
    val casingGasPressure: String = "",
    val espMotorTemperature: String = "",
    val waterCutPercent: String = "",
    // Injection fields.
    val steamInjectionPressure: String = "",
    val steamTemperature: String = "",
    val steamFlowRate: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val saveStatus: SaveStatus = SaveStatus.Idle,
) {
    /** True once a well is selected and its form should be shown. */
    val isEditingLog: Boolean get() = selectedWell != null

    /** Convenience for the success indicator (Req 1.1). */
    val isSaved: Boolean get() = saveStatus is SaveStatus.Saved

    /** Convenience for disabling the Save control while a save is in flight. */
    val isSaving: Boolean get() = saveStatus is SaveStatus.Saving
}

/**
 * Orchestrates well selection and wellhead-log capture.
 *
 * @param logRepository the transactional, storage-aware log store (Req 1).
 * @param productionValidator pure production-log validator (Req 3).
 * @param injectionValidator pure injection-log validator (Req 4).
 * @param scope coroutine scope for save work; defaults to [viewModelScope].
 *   Injectable so tests can drive saves on a test dispatcher.
 */
class WellheadLogViewModel(
    private val logRepository: LogRepository,
    private val productionValidator: ProductionLogValidator,
    private val injectionValidator: InjectionLogValidator,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(WellheadLogUiState())

    /** The single source of UI truth for both screens. */
    val uiState: StateFlow<WellheadLogUiState> = _uiState.asStateFlow()

    // region Selection

    /**
     * Select [well] and present its form. Clears any form state left over from a
     * previous selection so fields start empty and no stale errors or success
     * indicator carry over.
     */
    fun selectWell(well: Well) {
        _uiState.value = WellheadLogUiState(selectedWell = well)
    }

    /** Return to the selection screen, discarding the in-progress form. */
    fun clearSelection() {
        _uiState.value = WellheadLogUiState()
    }

    // endregion

    // region Field edits

    /**
     * Every field edit clears that field's error and resets the save status to
     * [SaveStatus.Idle], so a stale success indicator or validation message never
     * lingers once the operator starts correcting input. The raw string is kept
     * verbatim; the numeric grammar is enforced only at save time by the
     * validator, which lets the operator type freely.
     */
    fun onEmulsionFlowRateChange(value: String) =
        updateField(WellheadLogField.EMULSION_FLOW_RATE) { it.copy(emulsionFlowRate = value) }

    fun onCasingGasPressureChange(value: String) =
        updateField(WellheadLogField.CASING_GAS_PRESSURE) { it.copy(casingGasPressure = value) }

    fun onEspMotorTemperatureChange(value: String) =
        updateField(WellheadLogField.ESP_MOTOR_TEMPERATURE) { it.copy(espMotorTemperature = value) }

    fun onWaterCutPercentChange(value: String) =
        updateField(WellheadLogField.WATER_CUT_PERCENT) { it.copy(waterCutPercent = value) }

    fun onSteamInjectionPressureChange(value: String) =
        updateField(WellheadLogField.STEAM_INJECTION_PRESSURE) { it.copy(steamInjectionPressure = value) }

    fun onSteamTemperatureChange(value: String) =
        updateField(WellheadLogField.STEAM_TEMPERATURE) { it.copy(steamTemperature = value) }

    fun onSteamFlowRateChange(value: String) =
        updateField(WellheadLogField.STEAM_FLOW_RATE) { it.copy(steamFlowRate = value) }

    private fun updateField(field: String, edit: (WellheadLogUiState) -> WellheadLogUiState) {
        _uiState.update { current ->
            edit(current).copy(
                fieldErrors = current.fieldErrors - field,
                saveStatus = SaveStatus.Idle,
            )
        }
    }

    // endregion

    // region Save

    /**
     * Validate the current form against the selected well's type and, if valid,
     * persist it. Does nothing if no well is selected.
     *
     * On [ValidationResult.Invalid] the save is blocked, every entered value is
     * retained (we only replace [WellheadLogUiState.fieldErrors]), and the
     * offending fields are surfaced (Req 3.5 / 4.4). On [ValidationResult.Valid]
     * the normalized [WellheadLog] is handed to the repository; the mapped
     * [SaveResult] drives the success / storage-full / failure status while the
     * values stay in the form in every non-success case (Req 1.1, 1.4, 1.5).
     */
    fun save() {
        val state = _uiState.value
        val well = state.selectedWell ?: return

        val result = when (well.type) {
            WellType.PRODUCTION -> productionValidator.validate(state.toProductionInput())
            WellType.INJECTION -> injectionValidator.validate(state.toInjectionInput())
        }

        when (result) {
            is ValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        fieldErrors = result.errors.toMessageMap(),
                        saveStatus = SaveStatus.Idle,
                    )
                }
            }
            is ValidationResult.Valid -> persist(result.value)
        }
    }

    private fun persist(log: WellheadLog) {
        _uiState.update { it.copy(fieldErrors = emptyMap(), saveStatus = SaveStatus.Saving) }
        workScope.launch {
            val status = when (val save = logRepository.saveWellheadLog(log)) {
                is SaveResult.Success -> SaveStatus.Saved
                is SaveResult.StorageFull -> SaveStatus.StorageFull
                is SaveResult.Failure -> SaveStatus.Failure(save.reason)
            }
            _uiState.update { it.copy(saveStatus = status) }
        }
    }

    // endregion
}

/** Maps the current production-form strings to the validator's raw input. */
private fun WellheadLogUiState.toProductionInput(): ProductionLogInput =
    ProductionLogInput(
        wellId = requireNotNull(selectedWell).id,
        emulsionFlowRate = emulsionFlowRate,
        casingGasPressure = casingGasPressure,
        espMotorTemperature = espMotorTemperature,
        waterCutPercent = waterCutPercent,
    )

/** Maps the current injection-form strings to the validator's raw input. */
private fun WellheadLogUiState.toInjectionInput(): InjectionLogInput =
    InjectionLogInput(
        wellId = requireNotNull(selectedWell).id,
        steamInjectionPressure = steamInjectionPressure,
        steamTemperature = steamTemperature,
        steamFlowRate = steamFlowRate,
    )

/**
 * Collapses a list of [FieldError] into a field-id → message map the UI renders.
 * The validators emit at most one error per field, so this keeps the last entry
 * per field defensively without losing any flagged field.
 */
private fun List<FieldError>.toMessageMap(): Map<String, String> =
    associate { it.field to FieldErrorMessage.text(it) }
