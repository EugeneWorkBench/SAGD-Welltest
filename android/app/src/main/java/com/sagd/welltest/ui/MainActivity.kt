/*
 * UI layer entry point.
 *
 * MainActivity reads the process [AppGraph] (built by [SagdWellTestApp]) and
 * composes the implemented screens into a single scaffold. It is the UI half of
 * the composition root (task 23.2): every view model is constructed here from
 * graph-provided collaborators (repositories, validators, the pure subcool
 * estimator, the QR lookup use case, the escalation coordinator), and the global
 * offline banner is bound to the sync engine's [com.sagd.welltest.sync.OfflineIndicator].
 *
 * Navigation is a lightweight in-memory destination switch rather than a full
 * nav library — the project declares no navigation dependency, and this keeps
 * the wiring self-contained while making each screen reachable. The log-capture
 * and escalation screens read the live well list from the graph's
 * WellListProvider so they reflect pulled reference data.
 *
 * Finalized by task 23.2.
 */
package com.sagd.welltest.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sagd.welltest.AppGraph
import com.sagd.welltest.SagdWellTestApp
import com.sagd.welltest.domain.Well

/**
 * The reachable top-level destinations. Kept minimal: the screens implemented in
 * tasks 21.1-21.3, each driven by a view model built from the [AppGraph].
 */
private enum class Destination(val label: String) {
    LOG("Log"),
    SUBCOOL("Subcool"),
    ESCALATION("Escalate"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as SagdWellTestApp).graph
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(graph)
                }
            }
        }
    }
}

/**
 * Composition root for the UI tree: the global offline banner above a simple
 * destination switch. View models are remembered so they survive recomposition;
 * they are constructed from the graph's collaborators.
 */
@Composable
private fun AppRoot(graph: AppGraph) {
    var destination by remember { mutableStateOf(Destination.LOG) }

    // Live well list from the pulled reference data, for the forms that pick a well.
    val wells by graph.wellListProvider.observeAll()
        .collectAsState(initial = emptyList())

    // View models built once from graph collaborators (manual DI, no framework).
    val logViewModel = remember {
        WellheadLogViewModel(
            logRepository = graph.logRepository,
            productionValidator = graph.productionValidator,
            injectionValidator = graph.injectionValidator,
        )
    }
    val subcoolViewModel = remember { SubcoolEstimatorViewModel(graph.subcoolEstimator) }

    Scaffold(
        topBar = {
            // The engine flips this within 5s of a connectivity loss (Req 2.8).
            OfflineIndicatorBanner(offlineIndicator = graph.offlineIndicator)
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = destination == dest,
                        onClick = { destination = dest },
                        icon = {},
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (destination) {
                Destination.LOG ->
                    WellheadLogScreen(wells = wells, viewModel = logViewModel)

                Destination.SUBCOOL ->
                    SubcoolEstimatorScreen(viewModel = subcoolViewModel)

                Destination.ESCALATION ->
                    EscalationDestination(graph = graph, wells = wells)
            }
        }
    }
}

/**
 * Hosts the escalation capture screen. Its view model depends on the set of
 * wells to associate with, so it is rebuilt when that set changes (the
 * [EscalationCaptureViewModel] takes the list in its constructor). Photo
 * acquisition is a platform concern wired as a no-op placeholder here; the
 * camera/picker integration supplies real bytes in a full build.
 */
@Composable
private fun EscalationDestination(graph: AppGraph, wells: List<Well>) {
    val viewModel = remember(wells) {
        EscalationCaptureViewModel(
            availableWells = wells,
            validator = graph.escalationValidator,
            coordinator = graph.escalationCaptureCoordinator,
        )
    }
    if (wells.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Escalation", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "No wells available yet. Connect to sync reference data.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        EscalationCaptureScreen(
            viewModel = viewModel,
            // Photo acquisition is wired by the camera/picker integration; until
            // then this placeholder returns no bytes so Save stays disabled.
            capturePhoto = { null },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}
