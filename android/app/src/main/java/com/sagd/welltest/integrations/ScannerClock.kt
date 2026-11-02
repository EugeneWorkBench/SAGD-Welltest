/*
 * Scanner clock.
 *
 * The QR scanner's two deadlines — the 2-second decode budget from a code
 * entering the camera view (Req 6.1) and the 10-second unreadable timeout
 * (Req 6.4) — are measured against a single injected [ScannerClock]. Production
 * uses a monotonic elapsed-time source (unaffected by wall-clock jumps, NTP
 * corrections, or time-zone changes, which a camera session must not be subject
 * to); tests inject a controllable clock so the scanner-timing assertions
 * (task 19.5) are deterministic and do not actually wait. This mirrors the
 * `SyncClock` seam used by the sync engine.
 *
 * Unlike `SyncClock`, this clock exposes only "now" as a monotonic millisecond
 * reading and no sleep: the scanner is event-driven (it reacts to decoded
 * frames and to a timeout tick) rather than polling, and the core logic
 * ([QrScanController]) never blocks a thread. The surrounding camera/scheduler
 * shell is responsible for delivering the timeout tick (see
 * [QrScanController.onTimeoutCheck]).
 *
 * Introduced by task 19.1.
 */
package com.sagd.welltest.integrations

/**
 * A monotonic source of elapsed time in milliseconds. Injected into the scanner
 * so timing can be driven deterministically in tests.
 *
 * Implementations MUST be monotonic non-decreasing: successive calls to
 * [elapsedMillis] never return a smaller value than a prior call. The absolute
 * value has no meaning; only differences between readings matter.
 */
interface ScannerClock {
    /** A monotonic elapsed-time reading in milliseconds. */
    fun elapsedMillis(): Long
}

/**
 * The production clock: wraps the platform monotonic uptime source.
 *
 * Uses [android.os.SystemClock.elapsedRealtime], which counts since boot
 * including deep sleep and is immune to the user or network changing the wall
 * clock. Kept here (not in the core logic) so [QrScanController] stays a plain,
 * Android-free class.
 */
class SystemScannerClock : ScannerClock {
    override fun elapsedMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
