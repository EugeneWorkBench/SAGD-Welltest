/*
 * UI layer — well-selection and wellhead-log form composables.
 *
 * Thin Jetpack Compose screens that render [WellheadLogUiState] and forward raw
 * user intents to [WellheadLogViewModel]. They hold no business rules: which
 * form to show is driven by the selected [Well.type] (Req 3.1 / 4.1), the
 * success indicator / storage-full / failure banners reflect [SaveStatus], and
 * per-field validation messages come from [WellheadLogUiState.fieldErrors].
 * Validation and persistence are the view model's and the domain/repository
 * layers' concern.
 *
 * Mirrors the iOS task 20.1 SwiftUI screens.
 *
 * Implemented by task 21.1.
 * Requirements: 1.1, 1.5, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4.
 */
package com.sagd.welltest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.domain.WellheadLogField

/**
 * Root of the well-selection + wellhead-log flow. Shows the selection list
 * until a well is chosen, then the production or injection form for it.
 *
 * @param wells the wells the operator can pick from (supplied by the caller;
 *   the repository layer owns how they are loaded).
 * @param viewModel the orchestrating view model.
 */
@Composable
fun WellheadLogScreen(
    wells: List<Well>,
    viewModel: WellheadLogViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    val selected = state.selectedWell
    if (selected == null) {
        WellSelectionScreen(
            wells = wells,
            onSelect = viewModel::selectWell,
            modifier = modifier,
        )
    } else {
        WellheadLogForm(
            well = selected,
            state = state,
            viewModel = viewModel,
            modifier = modifier,
        )
    }
}

/** Well-selection list. Tapping a well presents its log form (Req 3.1 / 4.1). */
@Composable
fun WellSelectionScreen(
    wells: List<Well>,
    onSelect: (Well) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(text = "Select a well", style = MaterialTheme.typography.titleLarge)
        }
        items(wells, key = { it.id }) { well ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Well ${well.name}" },
            ) {
                TextButton(
                    onClick = { onSelect(well) },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = well.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = well.type.displayLabel(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The wellhead-log form for [well]. Picks the production or injection field set
 * from [Well.type] (Req 3.1 / 4.1) and renders the save-status banner.
 */
@Composable
fun WellheadLogForm(
    well: Well,
    state: WellheadLogUiState,
    viewModel: WellheadLogViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${well.name} — ${well.type.displayLabel()} log",
            style = MaterialTheme.typography.titleLarge,
        )

        when (well.type) {
            WellType.PRODUCTION -> ProductionFields(state, viewModel)
            WellType.INJECTION -> InjectionFields(state, viewModel)
        }

        SaveStatusBanner(state.saveStatus)

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = if (state.isSaving) "Saving…" else "Save")
        }

        TextButton(onClick = viewModel::clearSelection, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Choose a different well")
        }
    }
}

/** Production-well inputs (Req 3.1). */
@Composable
private fun ProductionFields(state: WellheadLogUiState, viewModel: WellheadLogViewModel) {
    NumericField(
        label = "Emulsion flow rate",
        value = state.emulsionFlowRate,
        error = state.fieldErrors[WellheadLogField.EMULSION_FLOW_RATE],
        onChange = viewModel::onEmulsionFlowRateChange,
    )
    NumericField(
        label = "Casing gas pressure",
        value = state.casingGasPressure,
        error = state.fieldErrors[WellheadLogField.CASING_GAS_PRESSURE],
        onChange = viewModel::onCasingGasPressureChange,
    )
    NumericField(
        label = "ESP motor temperature",
        value = state.espMotorTemperature,
        error = state.fieldErrors[WellheadLogField.ESP_MOTOR_TEMPERATURE],
        onChange = viewModel::onEspMotorTemperatureChange,
    )
    NumericField(
        label = "Water cut sample %",
        value = state.waterCutPercent,
        error = state.fieldErrors[WellheadLogField.WATER_CUT_PERCENT],
        onChange = viewModel::onWaterCutPercentChange,
    )
}

/** Injection-well inputs (Req 4.1). */
@Composable
private fun InjectionFields(state: WellheadLogUiState, viewModel: WellheadLogViewModel) {
    NumericField(
        label = "Steam injection pressure",
        value = state.steamInjectionPressure,
        error = state.fieldErrors[WellheadLogField.STEAM_INJECTION_PRESSURE],
        onChange = viewModel::onSteamInjectionPressureChange,
    )
    NumericField(
        label = "Steam temperature",
        value = state.steamTemperature,
        error = state.fieldErrors[WellheadLogField.STEAM_TEMPERATURE],
        onChange = viewModel::onSteamTemperatureChange,
    )
    NumericField(
        label = "Steam flow rate",
        value = state.steamFlowRate,
        error = state.fieldErrors[WellheadLogField.STEAM_FLOW_RATE],
        onChange = viewModel::onSteamFlowRateChange,
    )
}

/**
 * A labelled numeric text field that shows a per-field validation message below
 * it when [error] is non-null. The field always echoes [value] verbatim so a
 * rejected entry is retained for correction (Req 3.2-3.5, 4.2-4.4).
 */
@Composable
private fun NumericField(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(text = label) },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = error?.let { { Text(text = it, color = MaterialTheme.colorScheme.error) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Renders the outcome of the latest save: a success indicator (Req 1.1), a
 * storage-full message (Req 1.5), or a generic failure message (Req 1.4).
 * Shows nothing while idle or saving.
 */
@Composable
private fun SaveStatusBanner(status: SaveStatus) {
    when (status) {
        SaveStatus.Idle, SaveStatus.Saving -> Unit
        SaveStatus.Saved ->
            Text(
                text = "Saved ✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = "Log saved" },
            )
        SaveStatus.StorageFull ->
            Text(
                text = "Storage is full. Free up space and try again; your entries are kept.",
                color = MaterialTheme.colorScheme.error,
            )
        is SaveStatus.Failure ->
            Text(
                text = "Could not save the log (${status.message}). Your entries are kept.",
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** Operator-facing label for a well type. */
private fun WellType.displayLabel(): String = when (this) {
    WellType.PRODUCTION -> "Production"
    WellType.INJECTION -> "Injection"
}
