/*
 * UI layer — escalation capture composable.
 *
 * Thin Jetpack Compose screen that renders [EscalationCaptureUiState] and
 * forwards raw intents to [EscalationCaptureViewModel]. It holds no business
 * rules: priority / well selection and photo attach are bound to the view
 * model; a missing selection is marked inline from
 * [EscalationCaptureUiState.missingFields] while the entered data is retained
 * (Req 7.2); the saved confirmation shows the geotag / location-unavailable
 * state (Req 7.1). Photo acquisition is a platform concern injected as
 * [capturePhoto], so the screen stays free of camera plumbing.
 *
 * Mirrors the iOS task 20.2 `EscalationCaptureScreen`.
 *
 * Implemented by task 21.2.
 * Requirements: 7.1, 7.2.
 */
package com.sagd.welltest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sagd.welltest.domain.EscalationField
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.Well
import kotlinx.coroutines.launch

/**
 * Root of the escalation capture flow. Binds priority, well, and photo, and
 * renders the submission state including inline missing-selection marks
 * (Req 7.2) and the saved confirmation with the geotag state (Req 7.1).
 *
 * @param capturePhoto supplies photo bytes when the operator taps "Attach
 *   photo". Injected so the platform camera/picker stays outside this screen;
 *   returns null if the operator cancels.
 */
@Composable
fun EscalationCaptureScreen(
    viewModel: EscalationCaptureViewModel,
    capturePhoto: suspend () -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Escalation", style = MaterialTheme.typography.titleLarge)

        PrioritySection(
            selected = state.priority,
            isMissing = state.missingFields.contains(EscalationField.PRIORITY),
            onSelect = viewModel::selectPriority,
        )

        WellSection(
            wells = viewModel.availableWells,
            selectedWellId = state.selectedWellId,
            isMissing = state.missingFields.contains(EscalationField.WELL_ID),
            onSelect = viewModel::selectWell,
        )

        PhotoSection(
            hasPhoto = state.hasPhoto,
            capturePhoto = capturePhoto,
            onAttach = viewModel::attachPhoto,
        )

        Button(
            onClick = viewModel::save,
            enabled = state.hasPhoto && !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = if (state.isSaving) "Saving…" else "Save escalation")
        }

        SubmissionStatus(state.submissionState)
    }
}

/** Priority picker (High/Medium/Low). A missing priority is marked inline (Req 7.2). */
@Composable
private fun PrioritySection(
    selected: EscalationPriority?,
    isMissing: Boolean,
    onSelect: (EscalationPriority) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Priority",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EscalationPriority.entries.forEach { priority ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .selectable(
                            selected = selected == priority,
                            onClick = { onSelect(priority) },
                        )
                        .semantics {
                            contentDescription =
                                "Priority ${EscalationCaptureViewModel.priorityLabel(priority)}"
                        },
                ) {
                    RadioButton(
                        selected = selected == priority,
                        onClick = { onSelect(priority) },
                    )
                    Text(text = EscalationCaptureViewModel.priorityLabel(priority))
                }
            }
        }
        if (isMissing) {
            ValidationMessage("Select a priority.")
        }
    }
}

/** Well picker. A missing well is marked inline (Req 7.2). */
@Composable
private fun WellSection(
    wells: List<Well>,
    selectedWellId: String?,
    isMissing: Boolean,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Well",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        wells.forEach { well ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedWellId == well.id,
                        onClick = { onSelect(well.id) },
                    )
                    .semantics { contentDescription = "Well ${well.name}" },
            ) {
                RadioButton(
                    selected = selectedWellId == well.id,
                    onClick = { onSelect(well.id) },
                )
                Text(text = "${well.name} (${well.id})")
            }
        }
        if (isMissing) {
            ValidationMessage("Select a well.")
        }
    }
}

/** Photo attach control. A photo is required before Save is enabled. */
@Composable
private fun PhotoSection(
    hasPhoto: Boolean,
    capturePhoto: suspend () -> ByteArray?,
    onAttach: (ByteArray) -> Unit,
) {
    // The screen drives the platform picker on tap; the capture suspends and
    // its result is forwarded to the view model.
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Photo",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (hasPhoto) {
            Text(
                text = "Photo attached ✓",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "Photo attached" },
            )
        }
        TextButton(onClick = { scope.launch { capturePhoto()?.let(onAttach) } }) {
            Text(text = if (hasPhoto) "Replace photo" else "Attach photo")
        }
    }
}

/**
 * Renders the outcome of the latest save: a saved confirmation with the geotag
 * / location-unavailable state (Req 7.1), a storage-full message, or a failure
 * message. Missing-selection marks are rendered inline within the sections, so
 * the invalid branch shows nothing extra here.
 */
@Composable
private fun SubmissionStatus(status: EscalationSubmissionState) {
    when (status) {
        EscalationSubmissionState.Editing,
        EscalationSubmissionState.Saving,
        is EscalationSubmissionState.Invalid,
        -> Unit

        is EscalationSubmissionState.Saved ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Escalation saved ✓",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = "Escalation saved" },
                )
                if (status.locationUnavailable) {
                    Text(
                        text = "Location unavailable — saved without GPS coordinates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Geotagged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        EscalationSubmissionState.StorageFull ->
            Text(
                text = "Storage full — escalation not saved. Your entries are kept.",
                color = MaterialTheme.colorScheme.error,
            )

        is EscalationSubmissionState.Failed ->
            Text(
                text = "Save failed (${status.reason}). Your entries are kept.",
                color = MaterialTheme.colorScheme.error,
            )
    }
}

@Composable
private fun ValidationMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
