/*
 * FusedLocationProviderClient-backed single-fix source (production).
 *
 * The only file that touches play-services location types, keeping them out of
 * the testable core exactly as the sync layer keeps ConnectivityManager inside
 * ConnectivityManagerConnectivityProvider. It implements the raw
 * [SingleFixLocationSource] seam; the 30-second bounding, UTC-second truncation,
 * and locationUnavailable fallback all live in [BoundedLocationProvider], so
 * this class is a thin bridge from the play-services callback API to a
 * suspending call. Mirrors the iOS CLLocationManager-backed provider.
 *
 * Request shape: a single high-accuracy current location. The enclosing
 * [BoundedLocationProvider.currentFix] wraps the call in withTimeout(30s); when
 * that fires, the suspending call is cancelled and we cancel the play-services
 * task via the passed CancellationToken so no listener leaks (Req 7.5).
 *
 * Requirements: 7.1, 7.5.
 */
package com.sagd.welltest.integrations

import android.annotation.SuppressLint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Builds a [LocationProvider] backed by the fused location provider, bounded at
 * 30 seconds. This is the production entry point the app graph wires; callers
 * obtain fixes through the returned [LocationProvider] and never see the
 * play-services client.
 *
 * @param client the FusedLocationProviderClient, created at the composition
 *   root via `LocationServices.getFusedLocationProviderClient(context)`.
 */
object FusedLocationProvider {

    /**
     * Wraps [client] in a [BoundedLocationProvider] with the design-fixed
     * 30-second budget (Req 7.1, 7.5).
     */
    fun create(client: FusedLocationProviderClient): LocationProvider =
        BoundedLocationProvider(source = Source(client))

    /**
     * The raw one-shot source over FusedLocationProviderClient. Location
     * permission (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION) must be granted
     * before use; the call site is responsible for the runtime permission
     * prompt. If permission is absent at call time the underlying task fails and
     * [BoundedLocationProvider] maps it to locationUnavailable.
     */
    @Suppress("unused")
    internal class Source(
        private val client: FusedLocationProviderClient,
    ) : SingleFixLocationSource {

        @SuppressLint("MissingPermission")
        override suspend fun requestSingleFix(): RawLocation? =
            suspendCancellableCoroutine { continuation ->
                // A fresh cancellation token per request so the enclosing
                // withTimeout(30s) can tear the task down on timeout (Req 7.5).
                val cancellationSource = CancellationTokenSource()

                client
                    .getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cancellationSource.token,
                    )
                    .addOnSuccessListener { location ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        // A null location means the platform had no fix to give;
                        // surface it as "no fix" so the budget logic records
                        // locationUnavailable (Req 7.5).
                        continuation.resume(
                            location?.let { RawLocation(it.latitude, it.longitude) },
                        )
                    }
                    .addOnFailureListener { error ->
                        if (!continuation.isActive) return@addOnFailureListener
                        continuation.resumeWithException(error)
                    }

                // When the coroutine is cancelled (timeout or screen dismissal),
                // cancel the play-services request so no listener leaks.
                continuation.invokeOnCancellation { cancellationSource.cancel() }
            }
    }
}
