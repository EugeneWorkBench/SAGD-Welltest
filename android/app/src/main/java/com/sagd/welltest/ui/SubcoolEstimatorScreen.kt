/*
 * UI layer — subcool estimator composable.
 *
 * Thin Jetpack Compose screen that renders [SubcoolEstimatorUiState] and
 * forwards raw intents to [SubcoolEstimatorViewModel]. It holds no business
 * rules: the computed value appears effectively immediately (the estimate is
 * pure, well inside the 1-second budget, Req 5.2); a value at or below the
 * flashing threshold is rendered in a visually distinguished flashing-zone
 * alert state (Req 5.3); out-of-range pressure and empty/non-numeric input are
 * surfaced as messages with the entered values retained (Req 5.4, 5.5).
 *
 * Mirrors the iOS task 20.2 `SubcoolEstimatorScreen`.
 *
 * Implemented by task 21.2.
 * Requirements: 5.2, 5.3, 5.4, 5.5.
 */
package com.sagd.welltest.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Root of the subcool estimator flow. Binds the two input fields, invokes the
 * view model on Estimate, and renders the result branch.
 */
@Composable
fun SubcoolEstimatorScreen(
    viewModel: SubcoolEstimatorViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Subcool Estimator", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = state.casingPressure,
            onValueChange = viewModel::onCasingPressureChange,
            label = { Text(text = "Casing pressure (kPa)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.pumpTemperature,
            onValueChange = viewModel::onPumpTemperatureChange,
            label = { Text(text = "Pump temperature (°C)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = viewModel::estimate, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Estimate subcool")
        }

        SubcoolResultView(state.displayState)
    }
}

/** Renders the subcool result branch. */
@Composable
private fun SubcoolResultView(displayState: SubcoolDisplayState) {
    when (displayState) {
        SubcoolDisplayState.Idle ->
            Text(
                text = "Enter a casing pressure and pump temperature, then tap Estimate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        is SubcoolDisplayState.Value ->
            if (displayState.isFlashing) {
                FlashingAlert(displayState.subcool)
            } else {
                NormalValue(displayState.subcool)
            }

        is SubcoolDisplayState.OutOfRange ->
            Text(
                text = "${SubcoolEstimatorViewModel.fieldLabel(displayState.field)} is out of range for the steam table.",
                color = MaterialTheme.colorScheme.error,
            )

        is SubcoolDisplayState.InvalidInput ->
            Text(
                text = "${SubcoolEstimatorViewModel.fieldLabel(displayState.field)} must be a number.",
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** Normal (non-flashing) subcool value. */
@Composable
private fun NormalValue(subcool: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Subcool",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = SubcoolEstimatorViewModel.formattedSubcool(subcool),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Distinguished flashing-zone alert state (Req 5.3): a filled, high-contrast
 * banner with a warning marker so the value reads as a hazard at a glance.
 */
@Composable
private fun FlashingAlert(subcool: Double) {
    val alert = MaterialTheme.colorScheme.error
    Surface(
        color = alert.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, alert, RoundedCornerShape(10.dp))
            .semantics {
                contentDescription =
                    "Flashing zone alert, subcool ${SubcoolEstimatorViewModel.formattedSubcool(subcool)}"
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "⚠ Flashing zone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = alert,
            )
            Text(
                text = SubcoolEstimatorViewModel.formattedSubcool(subcool),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = alert,
            )
            Text(
                text = "Subcool is at or below the flashing threshold.",
                style = MaterialTheme.typography.bodySmall,
                color = alert,
            )
        }
    }
}
