/*
 * Sync clock.
 *
 * All time-sensitive sync behavior — the 60-second connectivity-restore trigger
 * (Req 2.2), the 5-second offline-indicator deadline (Req 2.8), and the
 * exponential backoff between retries — reads "now" and sleeps through a single
 * injected [SyncClock]. Production uses the wall clock and a real coroutine
 * delay; tests inject a controllable clock so timing assertions are
 * deterministic and do not actually wait (design.md "Integration testing":
 * controllable clock + fake NetworkCallback). Mirrors the iOS `SyncClock`
 * protocol exactly.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import kotlinx.coroutines.delay
import java.time.Instant

/**
 * A source of the current time and a way to suspend for a duration. Injected
 * everywhere the sync engine reasons about time so tests can drive timing
 * without real delays.
 */
interface SyncClock {
    /** The current instant (UTC). */
    fun now(): Instant

    /**
     * Suspend the current coroutine for [seconds]. A controllable clock may
     * advance virtual time instead of actually waiting.
     */
    suspend fun sleep(seconds: Double)
}

/** The production clock: wall-clock [Instant] and a real cooperative delay. */
class SystemSyncClock : SyncClock {
    override fun now(): Instant = Instant.now()

    override suspend fun sleep(seconds: Double) {
        if (seconds <= 0) return
        // Cooperative delay; cancellation simply ends the wait early, which is
        // fine — the next connectivity event re-drives the cycle.
        delay((seconds * 1000).toLong())
    }
}
