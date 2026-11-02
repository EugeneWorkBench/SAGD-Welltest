/*
 * ConnectivityManager.NetworkCallback-backed connectivity provider.
 *
 * The production [ConnectivityProvider]. A ConnectivityManager.NetworkCallback
 * reports network transitions; onAvailable maps to SATISFIED, onLost/onUnavailable
 * to UNSATISFIED. The design calls for this exact wiring (design.md "Connectivity
 * detection"): onAvailable drives the engine's onConnectivityAvailable() which
 * enqueues the unique sync work (Req 2.2), and onLost flips the offline indicator
 * within 5s (Req 2.8). Mirrors the iOS NWPathConnectivityProvider.
 *
 * Tests use the in-memory fake [ConnectivityProvider] (not this type) so the
 * engine's logic stays fully testable without the Android network stack.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicReference

/**
 * @param connectivityManager the system ConnectivityManager (from
 *   `context.getSystemService(ConnectivityManager::class.java)`).
 */
class ConnectivityManagerConnectivityProvider(
    private val connectivityManager: ConnectivityManager,
) : ConnectivityProvider {

    // Assume unsatisfied until the first callback arrives; the system delivers
    // the current network state shortly after registration.
    private val status = AtomicReference(ConnectivityStatus.UNSATISFIED)

    override val currentStatus: ConnectivityStatus
        get() = status.get()

    @SuppressLint("MissingPermission")
    override fun statusStream(): Flow<ConnectivityStatus> = callbackFlow {
        // Emit the current baseline so consumers have a starting value.
        trySend(status.get())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A usable network path appeared: SATISFIED drives the engine's
                // onConnectivityAvailable() within 60s (Req 2.2).
                status.set(ConnectivityStatus.SATISFIED)
                trySend(ConnectivityStatus.SATISFIED)
            }

            override fun onLost(network: Network) {
                // Connectivity lost: publish the offline indicator within 5s
                // (Req 2.8).
                status.set(ConnectivityStatus.UNSATISFIED)
                trySend(ConnectivityStatus.UNSATISFIED)
            }

            override fun onUnavailable() {
                status.set(ConnectivityStatus.UNSATISFIED)
                trySend(ConnectivityStatus.UNSATISFIED)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
