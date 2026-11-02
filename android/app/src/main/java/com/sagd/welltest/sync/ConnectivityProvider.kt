/*
 * Connectivity provider and offline indicator.
 *
 * The sync engine reacts to connectivity transitions but must not depend on
 * ConnectivityManager.NetworkCallback directly, or it could not be driven
 * deterministically in tests. [ConnectivityProvider] is the seam: production
 * wires a NetworkCallback-backed provider (ConnectivityManagerConnectivityProvider);
 * the connectivity-timing integration test (task 16.8) wires a fake that flips
 * connectivity on command with a controllable clock. Mirrors the iOS
 * `ConnectivityProvider` / `OfflineIndicator` design exactly.
 *
 * Two transitions matter:
 *   - becomes SATISFIED    -> the engine schedules a sync cycle so syncing
 *                             begins within 60s of restoration (Req 2.2).
 *   - becomes UNSATISFIED  -> the global offline indicator is published within
 *                             5s of loss (Req 2.8).
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A connectivity transition observed by the provider. */
enum class ConnectivityStatus {
    /** The device has a usable network path. */
    SATISFIED,

    /** The device has no usable network path. */
    UNSATISFIED,
}

/**
 * Publishes connectivity transitions. The engine starts observing, then
 * receives a stream of statuses; the first value is the current status at the
 * time observation starts.
 */
interface ConnectivityProvider {
    /** The most recently observed status (the current connectivity). */
    val currentStatus: ConnectivityStatus

    /**
     * A stream of connectivity transitions, beginning with the current status.
     * The engine consumes this to trigger sync cycles and to drive the offline
     * indicator.
     */
    fun statusStream(): Flow<ConnectivityStatus>
}

/**
 * Publishes the global offline indicator the UI observes. Kept separate from
 * the provider so the engine owns *when* the indicator flips (within 5s of a
 * loss transition — Req 2.8) while the UI only observes the published value.
 */
interface OfflineIndicatorPublishing {
    /** Publish whether the device is currently offline. The UI reflects this. */
    suspend fun setOffline(isOffline: Boolean)
}

/**
 * An in-memory offline indicator backed by a [StateFlow] the UI can observe.
 * Thread-safe via the atomic [MutableStateFlow]. Mirrors the iOS
 * `OfflineIndicator` actor.
 */
class OfflineIndicator(initiallyOffline: Boolean = false) : OfflineIndicatorPublishing {
    private val _offline = MutableStateFlow(initiallyOffline)

    /** The current offline value. */
    val offline: Boolean get() = _offline.value

    override suspend fun setOffline(isOffline: Boolean) {
        _offline.value = isOffline
    }

    /**
     * Observe the offline indicator. Emits the current value immediately, then
     * each change. The UI binds to this for the global offline banner (Req 2.8).
     */
    fun observe(): StateFlow<Boolean> = _offline.asStateFlow()
}
