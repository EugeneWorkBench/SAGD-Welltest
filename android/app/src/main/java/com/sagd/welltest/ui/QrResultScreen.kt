/*
 * UI layer — QR scan result composable.
 *
 * Thin Jetpack Compose screen that renders [QrResultUiState] and forwards the
 * rescan intent to [QrResultViewModel]. It holds no business rules: a Found
 * result lists the well's tests (most-recent-first, as ordered by the
 * repository) and open tasks (Req 6.2), annotated as sourced from local storage
 * when offline (Req 6.5); not-found (Req 6.3), unavailable-offline (Req 6.6),
 * and unreadable (Req 6.4) branches each show their message plus a rescan
 * control.
 *
 * Mirrors the iOS task 20.2 `QRResultScreen`.
 *
 * Implemented by task 21.2.
 * Requirements: 6.2, 6.3, 6.5, 6.6.
 */
package com.sagd.welltest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellTest

/**
 * Root of the QR result flow. Renders the current scan-result branch; the
 * caller wires the scanner controller's `onOutcome` callback to
 * [QrResultViewModel.onScanOutcome] and calls [QrResultViewModel.begin] when
 * the screen appears.
 */
@Composable
fun QrResultScreen(
    viewModel: QrResultViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    when (val display = state.displayState) {
        QrResultDisplayState.Scanning -> ScanningView(modifier)

        is QrResultDisplayState.Found ->
            FoundView(
                well = display.well,
                tests = display.wellTests,
                tasks = display.openTasks,
                fromLocalStorage = display.fromLocalStorage,
                modifier = modifier,
            )

        is QrResultDisplayState.NotFound ->
            MessageView(
                title = "Well not found",
                message = "No well matching \"${display.decodedId}\" is in local storage.",
                onRescan = viewModel::rescan,
                modifier = modifier,
            )

        is QrResultDisplayState.UnavailableOffline ->
            MessageView(
                title = "Unavailable offline",
                message = "\"${display.decodedId}\" is not in local storage and the device is offline.",
                onRescan = viewModel::rescan,
                modifier = modifier,
            )

        QrResultDisplayState.Unreadable ->
            MessageView(
                title = "Scan unreadable",
                message = "The code could not be read. Try again.",
                onRescan = viewModel::rescan,
                modifier = modifier,
            )
    }
}

@Composable
private fun ScanningView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Scanning…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun FoundView(
    well: Well,
    tests: List<WellTest>,
    tasks: List<MaintenanceTask>,
    fromLocalStorage: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = well.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${well.id} · ${well.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fromLocalStorage) {
                    // Local-storage annotation shown on an offline hit (Req 6.5).
                    Text(
                        text = "Showing data from local storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "Showing data from local storage"
                        },
                    )
                }
            }
        }

        item {
            Text(
                text = "Well tests (most recent first)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (tests.isEmpty()) {
            item {
                Text(
                    text = "No well tests recorded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(tests, key = { it.id }) { test ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = QrResultViewModel.ratioSummary(test))
                    Text(
                        text = QrResultViewModel.formattedTimestamp(test.capturedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text(
                text = "Open maintenance tasks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (tasks.isEmpty()) {
            item {
                Text(
                    text = "No open tasks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                Text(text = task.description)
            }
        }
    }
}

@Composable
private fun MessageView(
    title: String,
    message: String,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onRescan) {
            Text(text = "Rescan")
        }
    }
}
