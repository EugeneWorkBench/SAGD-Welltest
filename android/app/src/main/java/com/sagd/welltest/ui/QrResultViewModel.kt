/*
 * UI layer — QR scan result view model.
 *
 * Holds all logic for the QR result screen so the composable stays thin: it
 * renders [QrResultUiState] and forwards the rescan intent. Mirrors the iOS
 * task 20.2 `QRResultViewModel`, adapted to the Android scanner's push model —
 * the platform-agnostic [QrScanController] delivers a terminal [ScanOutcome]
 * via its `onOutcome` callback rather than returning it from a suspend call, so
 * this view model receives outcomes through [onScanOutcome] and starts/rescans
 * via an injected [startScan] lambda that drives the camera shell.
 *
 * Responsibilities (design.md "QR Scanning Flow"):
 *   - Map a terminal [ScanOutcome] onto a renderable [QrResultDisplayState]:
 *     a decoded [WellLookupResult.Found] renders the well with its tests
 *     (most-recent-first, as ordered by the repository) and open tasks (Req
 *     6.2); a [WellLookupResult.NotFound] / [WellLookupResult.UnavailableOffline]
 *     miss shows the matching message with a rescan control (Req 6.3, 6.6); an
 *     unreadable scan shows the unreadable message with a rescan control (Req
 *     6.4).
 *   - Consult an injected connectivity signal only to annotate a Found result
 *     as sourced from local storage when offline (Req 6.5); the lookup itself
 *     is connectivity-independent and already done inside the controller.
 *   - The rescan action (Req 6.3, 6.4) resets to the scanning state and asks the
 *     caller to restart the scan session.
 *
 * State is exposed as a single immutable [StateFlow]; no I/O of its own.
 *
 * Implemented by task 21.2.
 * Requirements: 6.2, 6.3, 6.5, 6.6.
 */
package com.sagd.welltest.ui

import androidx.lifecycle.ViewModel
import com.sagd.welltest.domain.ConnectivityProviding
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellLookupResult
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.integrations.ScanOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What the QR result screen renders. Derived from [ScanOutcome] so the
 * composable only lays out the branch it is handed.
 */
sealed interface QrResultDisplayState {
    /** No scan has completed yet (initial, or while a rescan is running). */
    data object Scanning : QrResultDisplayState

    /**
     * A matching well with its tests (most-recent-first) and open tasks.
     * [fromLocalStorage] drives the local-storage annotation shown when the
     * result was assembled offline (Req 6.5).
     */
    data class Found(
        val well: Well,
        val wellTests: List<WellTest>,
        val openTasks: List<MaintenanceTask>,
        val fromLocalStorage: Boolean,
    ) : QrResultDisplayState

    /** The decoded well was not found locally while online (Req 6.3). */
    data class NotFound(val decodedId: String) : QrResultDisplayState

    /** The decoded well was not found locally while offline (Req 6.6). */
    data class UnavailableOffline(val decodedId: String) : QrResultDisplayState

    /** Nothing decoded within the unreadable budget (Req 6.4). */
    data object Unreadable : QrResultDisplayState
}

/**
 * Immutable snapshot the QR result screen renders.
 *
 * @property displayState the current scan-result branch; starts
 *   [QrResultDisplayState.Scanning].
 */
data class QrResultUiState(
    val displayState: QrResultDisplayState = QrResultDisplayState.Scanning,
)

/**
 * Drives the QR result screen.
 *
 * The Android scanner pushes a terminal [ScanOutcome] through the controller's
 * `onOutcome` callback; wire that callback to [onScanOutcome]. The view model
 * does not own the camera — it asks the caller to (re)start a session via
 * [startScan], so the composable/shell keeps the CameraX plumbing.
 *
 * @param connectivity online/offline signal, consulted only to annotate a Found
 *   result as local-storage-sourced when offline (Req 6.5).
 * @param startScan restarts a scan session (binds/rebinds the camera analysis);
 *   invoked on construction-time [begin] and on [rescan]. Optional so tests can
 *   drive outcomes directly without a camera.
 */
class QrResultViewModel(
    private val connectivity: ConnectivityProviding,
    private val startScan: (() -> Unit)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QrResultUiState())

    /** The single source of UI truth for the screen. */
    val uiState: StateFlow<QrResultUiState> = _uiState.asStateFlow()

    /**
     * Enter the scanning state and ask the caller to start the camera session.
     * Call once when the screen appears.
     */
    fun begin() {
        _uiState.value = QrResultUiState(QrResultDisplayState.Scanning)
        startScan?.invoke()
    }

    /**
     * Receive a terminal scan outcome from the controller's `onOutcome`
     * callback and map it onto the render state.
     */
    fun onScanOutcome(outcome: ScanOutcome) {
        val display = when (outcome) {
            is ScanOutcome.Decoded -> mapLookup(outcome.wellId, outcome.lookup)
            ScanOutcome.Unreadable -> QrResultDisplayState.Unreadable
        }
        _uiState.value = QrResultUiState(display)
    }

    /**
     * Restart the scan — the rescan control for the not-found / unreadable
     * branches (Req 6.3, 6.4). Resets to scanning and asks the caller to rebind
     * the camera session.
     */
    fun rescan() {
        _uiState.value = QrResultUiState(QrResultDisplayState.Scanning)
        startScan?.invoke()
    }

    /** Map a decoded well's lookup result onto the render state. */
    private fun mapLookup(decodedId: String, lookup: WellLookupResult): QrResultDisplayState =
        when (lookup) {
            is WellLookupResult.Found ->
                QrResultDisplayState.Found(
                    well = lookup.well,
                    wellTests = lookup.wellTests,
                    openTasks = lookup.openMaintenanceTasks,
                    // Annotate as local-storage-sourced on an offline hit (Req 6.5).
                    fromLocalStorage = !connectivity.isOnline,
                )
            WellLookupResult.NotFound -> QrResultDisplayState.NotFound(decodedId)
            WellLookupResult.UnavailableOffline -> QrResultDisplayState.UnavailableOffline(decodedId)
        }

    companion object {
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

        /** Oil/water/gas ratios formatted for a test row. */
        fun ratioSummary(test: WellTest): String =
            "Oil %.2f · Water %.2f · Gas %.2f".format(test.oilRatio, test.waterRatio, test.gasRatio)

        /** A UTC timestamp formatted for display (one-second precision). */
        fun formattedTimestamp(instant: Instant): String = TIMESTAMP_FORMAT.format(instant)
    }
}
