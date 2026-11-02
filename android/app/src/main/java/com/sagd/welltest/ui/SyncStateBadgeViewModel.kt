/*
 * UI layer — per-record sync-state badge view model.
 *
 * Holds all logic for a single record's sync-state badge so the composable
 * stays thin: it collects [SyncRepository.observeSyncState] into a [StateFlow]
 * the view renders, and forwards the manual-retry intent. Mirrors the iOS task
 * 20.3 `SyncStateBadgeViewModel`, adapted to Kotlin coroutines — the iOS view
 * model subscribes to an `AsyncStream<SyncState>` in a `Task`; here the
 * equivalent is collecting the repository's `Flow<SyncState>` in
 * [viewModelScope].
 *
 * Responsibilities (design.md "Sync-state indicators", Req 2.6/2.7/2.5/7.7):
 *   - Seed the badge with [SyncState.PENDING] — the state of a freshly created,
 *     not-yet-acknowledged record (Req 2.6) — and then reflect every change the
 *     repository observation emits. Because the observation yields within the
 *     repository's latency, a transition to pending/synchronized/failed is
 *     displayed well inside the 5-second budget (Req 2.7).
 *   - Expose whether the manual-retry control should be shown: exactly when the
 *     record has reached [SyncState.FAILED].
 *   - Forward a manual retry to [SyncRepository.manualRetry], which resets a
 *     failed record to pending so automatic sync picks it up again (Req 2.5,
 *     7.7). The repository ignores non-failed records, so the call is safe
 *     unconditionally; the resulting transition arrives back through the
 *     observation stream.
 *
 * State is exposed as a single immutable [StateFlow]; the view model never
 * exposes mutable fields. The only I/O is the fire-and-forget manual retry.
 *
 * Implemented by task 21.3.
 * Requirements: 2.6, 2.7.
 */
package com.sagd.welltest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.SyncState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Immutable snapshot the badge renders.
 *
 * @property state the latest observed sync state; drives the badge label and
 *   styling and whether the retry control shows. Starts [SyncState.PENDING]
 *   (Req 2.6) and updates as the record's state changes (Req 2.7).
 */
data class SyncStateBadgeUiState(
    val state: SyncState = SyncState.PENDING,
) {
    /**
     * True only when the record has permanently failed; the view shows the
     * manual-retry control exactly in this case (Req 2.5, 7.7).
     */
    val showsRetryControl: Boolean get() = state == SyncState.FAILED
}

/**
 * Drives a single record's sync-state badge.
 *
 * @param recordId the record to observe.
 * @param repository provides the observation stream and the manual-retry
 *   mutation.
 * @param initialState the state shown before the first stream value arrives;
 *   defaults to [SyncState.PENDING], the state of a freshly created record
 *   (Req 2.6).
 */
class SyncStateBadgeViewModel(
    val recordId: UUID,
    private val repository: SyncRepository,
    initialState: SyncState = SyncState.PENDING,
) : ViewModel() {

    /**
     * The single source of UI truth for the badge. Collecting the repository's
     * per-record observation into a hot [StateFlow] means the composable only
     * reads the current state; the observation is shared and started while
     * there is a subscriber, and the seed value shows the authoritative initial
     * state until the first emission arrives.
     */
    val uiState: StateFlow<SyncStateBadgeUiState> =
        repository.observeSyncState(recordId)
            .map { SyncStateBadgeUiState(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SyncStateBadgeUiState(initialState),
            )

    /**
     * Trigger a manual retry for a failed record, resetting it to pending so
     * automatic sync picks it up again (Req 2.5, 7.7). Safe to call regardless
     * of the current state — the repository ignores records that are not
     * failed; the observation stream reports any resulting transition.
     */
    fun retry() {
        viewModelScope.launch { repository.manualRetry(recordId) }
    }

    companion object {
        /**
         * Keep the shared observation alive briefly after the last subscriber
         * goes away so a transient recomposition (e.g. in a scrolling list)
         * does not tear down and rebuild the observation.
         */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Short human-readable label shown in the badge. */
        fun displayLabel(state: SyncState): String = when (state) {
            SyncState.PENDING -> "Pending"
            SyncState.SYNCHRONIZED -> "Synced"
            SyncState.FAILED -> "Failed"
        }

        /** Fuller description for assistive technology. */
        fun accessibilityLabel(state: SyncState): String = when (state) {
            SyncState.PENDING -> "Sync pending"
            SyncState.SYNCHRONIZED -> "Synchronized"
            SyncState.FAILED -> "Sync failed"
        }
    }
}
