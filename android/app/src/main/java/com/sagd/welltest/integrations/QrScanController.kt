/*
 * QR scan controller — the core, testable scan-session logic.
 *
 * This is the platform-agnostic heart of the Android QR scanner: a plain class
 * with no CameraX, ML Kit, or Android-framework dependency (beyond the injected
 * [ScannerClock], whose production impl is the only Android touch-point). The
 * CameraX `ImageAnalysis` + ML Kit barcode pipeline ([CameraXQrScanner]) is a
 * thin shell that feeds this controller decoded strings and a periodic timeout
 * tick; all timing and dispatch logic lives here so it is unit- and
 * property-testable deterministically (task 19.5), exactly like [SyncCycleRunner]
 * is for the sync engine.
 *
 * It implements the decode/timeout portion of the design's QR Scanning Flow
 * (design.md "QR Scanning Flow"):
 *
 *   - A session begins at [start]; the clock reading at that moment is the
 *     reference point ("code enters the camera view") for both budgets.
 *   - Frames decoded by the camera shell are delivered via [onDecoded]. The
 *     FIRST decode that lands within the 2-second decode budget (Req 6.1) wins:
 *     the controller stops accepting further frames, invokes the injected
 *     `WellLookupUseCase` with the decoded string, and emits
 *     [ScanOutcome.Decoded] carrying the lookup result. A decode that arrives
 *     after the budget has elapsed but before the session has terminated is
 *     still honored rather than discarded — the 2s budget is the *target*
 *     (Req 6.1), while the 10s timeout (Req 6.4) is the hard failure deadline;
 *     honoring a late-but-pre-timeout decode is strictly better for the operator
 *     than forcing a rescan.
 *   - If no frame decodes before 10 seconds elapse, the session ends as
 *     [ScanOutcome.Unreadable] with a rescan control (Req 6.4). The camera shell
 *     drives this by calling [onTimeoutCheck] (e.g. on each frame and/or a timer
 *     tick); the controller compares elapsed time against the timeout itself so
 *     the deadline is clock-driven and testable.
 *   - [rescan] restarts a fresh session after either terminal outcome, backing
 *     the rescan control required by Req 6.3/6.4.
 *
 * Connectivity branching (Found / NotFound / UnavailableOffline, Req 6.2/6.3/
 * 6.5/6.6) is NOT decided here: the controller delegates to the injected
 * `WellLookupUseCase`, which reads the local store and consults connectivity to
 * classify a miss. The controller only distinguishes "decoded vs never decoded"
 * and passes the decoded identifier through.
 *
 * Threading: the controller is NOT internally synchronized. The camera shell
 * must call [start], [onDecoded], [onTimeoutCheck], and [rescan] from a single
 * sequence (ML Kit's analyzer callback plus a timeout tick are naturally
 * serialized onto one executor), which keeps the session state a simple,
 * race-free field. Tests drive the methods directly on one thread.
 *
 * Introduced by task 19.1.
 */
package com.sagd.welltest.integrations

import com.sagd.welltest.domain.WellLookupUseCase

/**
 * Drives a single QR scan session: decode-budget timing, unreadable timeout,
 * one-shot dispatch to [WellLookupUseCase], and rescan.
 *
 * @param lookupUseCase resolves a decoded well identifier against the local
 *   store and decides the Found / NotFound / UnavailableOffline branch. Invoked
 *   at most once per session, on the winning decode.
 * @param onOutcome invoked exactly once per session with its terminal
 *   [ScanOutcome] (either [ScanOutcome.Decoded] or [ScanOutcome.Unreadable]).
 *   The camera shell uses this to tear down analysis and surface the result UI.
 * @param clock monotonic time source for both deadlines; injectable so tests
 *   advance time without waiting.
 * @param decodeBudgetMillis the target decode budget — the time within which a
 *   code entering view is expected to decode (Req 6.1). Default 2000 ms.
 * @param unreadableTimeoutMillis the hard deadline after which a session with no
 *   decode is reported unreadable (Req 6.4). Default 10000 ms.
 */
class QrScanController(
    private val lookupUseCase: WellLookupUseCase,
    private val onOutcome: suspend (ScanOutcome) -> Unit,
    private val clock: ScannerClock,
    private val decodeBudgetMillis: Long = DEFAULT_DECODE_BUDGET_MILLIS,
    private val unreadableTimeoutMillis: Long = DEFAULT_UNREADABLE_TIMEOUT_MILLIS,
) {

    /**
     * The lifecycle of one scan session. A controller moves
     * SCANNING -> (DECODED | UNREADABLE) and, via [rescan], back to SCANNING.
     */
    private enum class State { IDLE, SCANNING, DECODED, UNREADABLE }

    private var state: State = State.IDLE

    /** Clock reading captured at [start]; the reference for both budgets. */
    private var sessionStartMillis: Long = 0L

    /** Whether the controller is currently accepting decoded frames. */
    val isScanning: Boolean
        get() = state == State.SCANNING

    /**
     * Begin a scan session. Captures the start time against which the 2-second
     * decode budget and 10-second unreadable timeout are measured. Safe to call
     * only from IDLE or a terminal state; calling it while already SCANNING is a
     * no-op so a redundant start does not reset the deadlines mid-session.
     */
    fun start() {
        if (state == State.SCANNING) return
        sessionStartMillis = clock.elapsedMillis()
        state = State.SCANNING
    }

    /**
     * Offer a decoded barcode string to the session. Honored only while
     * SCANNING and only if the session has not already passed its unreadable
     * timeout (a decode landing after the 10s deadline is rejected in favor of
     * the [ScanOutcome.Unreadable] result the timeout will produce). The first
     * accepted decode wins: the controller transitions to DECODED, looks the
     * identifier up via [WellLookupUseCase], and emits [ScanOutcome.Decoded].
     * Subsequent decodes are ignored.
     *
     * Note the decode budget ([decodeBudgetMillis], Req 6.1) is a target, not a
     * rejection threshold: a decode arriving after 2s but before the 10s timeout
     * is still accepted. Only the timeout terminates a session unsuccessfully.
     *
     * @return true if this decode was accepted and terminated the session;
     *   false if it was ignored (not scanning, already terminated, or past the
     *   unreadable timeout).
     */
    suspend fun onDecoded(wellId: String): Boolean {
        if (state != State.SCANNING) return false
        if (hasTimedOut(clock.elapsedMillis())) {
            // Past the hard deadline: let the timeout path own the outcome.
            return false
        }

        state = State.DECODED
        val lookup = lookupUseCase.lookup(wellId)
        onOutcome(ScanOutcome.Decoded(wellId = wellId, lookup = lookup))
        return true
    }

    /**
     * Check whether the unreadable timeout has elapsed and, if so, end the
     * session as [ScanOutcome.Unreadable]. The camera shell calls this
     * periodically (on frames and/or a timer tick) so the 10-second deadline
     * (Req 6.4) is enforced even when no frame ever decodes. A no-op unless the
     * session is SCANNING and the elapsed time has reached the timeout.
     *
     * @return true if this call terminated the session as unreadable; false
     *   otherwise.
     */
    suspend fun onTimeoutCheck(): Boolean {
        if (state != State.SCANNING) return false
        if (!hasTimedOut(clock.elapsedMillis())) return false

        state = State.UNREADABLE
        onOutcome(ScanOutcome.Unreadable)
        return true
    }

    /**
     * Whether the current session's decode is still within the 2-second decode
     * budget (Req 6.1). Exposed for the camera shell and tests to report decode
     * latency; the controller itself does not reject late decodes on this basis
     * (see [onDecoded]).
     */
    fun isWithinDecodeBudget(): Boolean {
        if (state != State.SCANNING) return false
        return elapsedSinceStart(clock.elapsedMillis()) <= decodeBudgetMillis
    }

    /**
     * Restart a fresh session after a terminal outcome, backing the rescan
     * control required after a not-found or unreadable result (Req 6.3, 6.4).
     * Equivalent to a new [start]: the deadlines are measured from this moment.
     */
    fun rescan() {
        sessionStartMillis = clock.elapsedMillis()
        state = State.SCANNING
    }

    private fun elapsedSinceStart(nowMillis: Long): Long = nowMillis - sessionStartMillis

    private fun hasTimedOut(nowMillis: Long): Boolean =
        elapsedSinceStart(nowMillis) >= unreadableTimeoutMillis

    companion object {
        /** Target decode budget: 2 seconds (Req 6.1). */
        const val DEFAULT_DECODE_BUDGET_MILLIS: Long = 2_000L

        /** Hard unreadable timeout: 10 seconds (Req 6.4). */
        const val DEFAULT_UNREADABLE_TIMEOUT_MILLIS: Long = 10_000L
    }
}
