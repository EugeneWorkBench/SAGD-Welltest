/*
 * Location provider seam and single-fix request logic.
 *
 * Escalation capture needs one GPS fix, bounded at 30 seconds (design.md
 * "Geotagged Photo Escalation Flow", Req 7.1, 7.5). The sync/connectivity layer
 * already established the house pattern for a device primitive: a pure interface
 * seam the domain logic depends on, a production implementation that holds the
 * platform types (here play-services FusedLocationProviderClient), and an
 * injectable clock so timing is deterministic in tests (see
 * sync.ConnectivityProvider / sync.SyncClock). This file is the seam plus the
 * platform-agnostic 30-second bounding logic; the FusedLocationProviderClient
 * wiring lives in [FusedLocationProvider].
 *
 * Two outcomes, mirroring the iOS location provider:
 *   - a fix within 30s     -> [LocationFix] with decimal-degree lat/long and a
 *                             UTC timestamp truncated to the second (Req 7.1).
 *   - no fix within 30s     -> [LocationResult.Unavailable], which the
 *                             coordinator records as locationUnavailable so the
 *                             operator may proceed (Req 7.5).
 *
 * The seam ([SingleFixLocationSource]) exposes the raw one-shot request; the
 * bounding, truncation, and timeout policy live in [BoundedLocationProvider]
 * which an injectable fake source + clock can drive deterministically in tests.
 *
 * Requirements: 7.1, 7.5.
 */
package com.sagd.welltest.integrations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.temporal.ChronoUnit

// region Result types

/**
 * A single location fix: decimal-degree coordinates and the capture instant
 * truncated to the second in UTC (Req 7.1). [BoundedLocationProvider] performs
 * the truncation so every attached timestamp has 1-second precision regardless
 * of the underlying source's resolution.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Instant,
)

/**
 * The outcome of a bounded location request (design "GPS fix within 30s?").
 *
 * - [Available] carries the [LocationFix] obtained inside the 30-second budget.
 * - [Unavailable] means the budget elapsed (or the source yielded no fix)
 *   without a usable coordinate; the escalation records locationUnavailable and
 *   the operator proceeds (Req 7.5).
 */
sealed interface LocationResult {
    data class Available(val fix: LocationFix) : LocationResult

    data object Unavailable : LocationResult
}

// endregion

// region Seam

/**
 * The raw one-shot location source: request a current fix, suspending until the
 * platform returns one (or the enclosing timeout cancels the call). Returns null
 * when the platform reports no location is available (e.g. provider disabled)
 * rather than ever blocking past cancellation.
 *
 * Production wires a FusedLocationProviderClient-backed source
 * ([FusedLocationProvider.Source]); tests inject a fake that resolves after a
 * controllable delay, or never resolves, to exercise both outcomes.
 */
interface SingleFixLocationSource {
    /**
     * Requests a single current fix. Implementations must be cancellation-aware:
     * when the caller's [withTimeout] fires, the in-flight request is cancelled
     * and any underlying platform listener is torn down. Returns null if the
     * platform actively reports no fix.
     */
    suspend fun requestSingleFix(): RawLocation?
}

/**
 * A platform-neutral location sample handed up by a [SingleFixLocationSource].
 * Kept separate from [LocationFix] because the source does not own the capture
 * timestamp policy — [BoundedLocationProvider] stamps and truncates the time so
 * the 1-second UTC precision rule lives in one place (Req 7.1).
 */
data class RawLocation(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Obtains a location fix bounded by a deadline. The escalation coordinator
 * depends on this interface, not on any platform type, so the capture flow is
 * fully testable with a fake.
 */
interface LocationProvider {
    /**
     * Attempts to obtain a single fix within the 30-second budget (Req 7.1). On
     * timeout or no-fix, returns [LocationResult.Unavailable] so the caller can
     * proceed with locationUnavailable (Req 7.5). Never throws for a timeout;
     * only genuine cancellation of the calling coroutine propagates.
     */
    suspend fun currentFix(): LocationResult
}

// endregion

// region Bounded provider

/**
 * Bounds a [SingleFixLocationSource] at [timeoutSeconds] (30 by default,
 * design) and stamps the fix with a UTC timestamp truncated to the second
 * (Req 7.1). A fix that does not arrive in time, or a source that reports no
 * location, both resolve to [LocationResult.Unavailable] (Req 7.5).
 *
 * @param source the raw one-shot location source (play-services in production,
 *   a fake in tests).
 * @param clock supplies the capture instant; injectable so tests pin the
 *   timestamp. Note the *timeout* is enforced with kotlinx coroutines
 *   [withTimeout] on the calling dispatcher — in tests a virtual-time
 *   dispatcher advances it without real waiting, matching how the sync layer
 *   tests drive [SyncClock]-based deadlines.
 * @param timeoutSeconds the fix budget; the design fixes 30 and the production
 *   call site uses the default.
 */
class BoundedLocationProvider(
    private val source: SingleFixLocationSource,
    private val clock: LocationClock = SystemLocationClock,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : LocationProvider {

    override suspend fun currentFix(): LocationResult {
        val raw =
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    source.requestSingleFix()
                }
            } catch (_: TimeoutCancellationException) {
                // Budget elapsed with no fix: proceed with locationUnavailable
                // (Req 7.5). This is a timeout, not a cancellation of the caller.
                return LocationResult.Unavailable
            } catch (e: CancellationException) {
                // The caller's coroutine was cancelled (e.g. the capture screen
                // was dismissed); propagate so cancellation stays cooperative.
                throw e
            } catch (_: Throwable) {
                // Any provider error (permission revoked mid-request, provider
                // disabled) is treated as "no fix available" — never block the
                // operator (Req 7.5).
                return LocationResult.Unavailable
            }

        raw ?: return LocationResult.Unavailable

        // Attach the capture timestamp truncated to the second in UTC (Req 7.1).
        val timestamp = clock.now().truncatedTo(ChronoUnit.SECONDS)
        return LocationResult.Available(
            LocationFix(
                latitude = raw.latitude,
                longitude = raw.longitude,
                timestamp = timestamp,
            ),
        )
    }

    companion object {
        /** The design-fixed GPS fix budget: 30 seconds (Req 7.1, 7.5). */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 30L
    }
}

// endregion

// region Clock seam

/**
 * Source of the capture instant for a location fix. Separate tiny seam (rather
 * than reusing sync.SyncClock) so the integrations layer does not depend on the
 * sync layer; the shape matches so both read a UTC [Instant]. Injectable for
 * deterministic timestamp assertions.
 */
interface LocationClock {
    /** The current instant (UTC). */
    fun now(): Instant
}

/** The production clock: wall-clock UTC [Instant]. */
object SystemLocationClock : LocationClock {
    override fun now(): Instant = Instant.now()
}

// endregion
