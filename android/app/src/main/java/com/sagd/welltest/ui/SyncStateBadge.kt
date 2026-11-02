/*
 * UI layer — per-record sync-state badge composable.
 *
 * Thin Jetpack Compose badge that renders a record's synchronization state
 * (pending / synchronized / failed) and, for a failed record only, a manual
 * retry control. It holds no business rules: it reads
 * [SyncStateBadgeViewModel.uiState] and forwards the retry tap. The view model
 * collects the repository's per-record observation, so the displayed state
 * reflects a transition well inside the 5-second budget (Req 2.7); a freshly
 * created record shows as pending (Req 2.6).
 *
 * Mirrors the iOS task 20.3 `SyncStateBadgeView`. Rendered with material3
 * primitives only (a small colored status dot plus a label) so no
 * material-icons artifact is required.
 *
 * Implemented by task 21.3.
 * Requirements: 2.6, 2.7.
 */
package com.sagd.welltest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sagd.welltest.domain.SyncState

/**
 * Renders a record's sync-state badge and, for failed records, a retry button.
 * All state and the retry action come from the view model.
 */
@Composable
fun SyncStateBadge(
    viewModel: SyncStateBadgeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    SyncStateBadgeContent(
        state = state.state,
        showsRetryControl = state.showsRetryControl,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * Stateless badge body. Split out from [SyncStateBadge] so it renders from a
 * plain [SyncState] without a view model — useful for previews and UI tests
 * (task 21.4) that assert badge rendering per state (Req 2.7).
 */
@Composable
fun SyncStateBadgeContent(
    state: SyncState,
    showsRetryControl: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = syncStateTint(state)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = SyncStateBadgeViewModel.accessibilityLabel(state)
            },
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, CircleShape),
            )
            Text(
                text = SyncStateBadgeViewModel.displayLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }

        if (showsRetryControl) {
            TextButton(onClick = onRetry) {
                Text(text = "Retry", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The color used to distinguish each sync state. */
@Composable
private fun syncStateTint(state: SyncState): Color = when (state) {
    SyncState.PENDING -> Color(0xFFF57C00) // orange
    SyncState.SYNCHRONIZED -> Color(0xFF2E7D32) // green
    SyncState.FAILED -> MaterialTheme.colorScheme.error
}
