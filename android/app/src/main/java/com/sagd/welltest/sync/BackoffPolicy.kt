/*
 * Exponential backoff policy.
 *
 * After a failed push a record stays PENDING and is retried on a later
 * connectivity event, but not immediately: retries are spaced by an exponential
 * backoff keyed on the record's `lastAttemptAt` and its `retryCount` (design.md
 * "Retry and backoff": e.g. 1s, 4s, 16s, 64s, 256s). The engine asks this
 * policy, given a record's [SyncMetadata] and "now", whether the record's
 * backoff window has elapsed; a record still inside its window is skipped this
 * cycle and revisited on the next one. Mirrors the iOS `BackoffPolicy` protocol
 * and `ExponentialBackoffPolicy` exactly.
 *
 * Keying the delay on `lastAttemptAt` (persisted on the record) rather than on
 * in-memory timers means the schedule survives app restarts and background
 * relaunches: a record relaunched mid-backoff resumes its wait from where it
 * left off — which matters because WorkManager may run the worker across
 * process death.
 *
 * Introduced by task 16.1.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.SyncMetadata
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/**
 * Decides, for a given record, whether enough time has passed since its last
 * attempt to try it again.
 */
interface BackoffPolicy {
    /**
     * The earliest instant at which a record with the given sync metadata is
     * eligible for another push attempt. `null` means "eligible now" (there is
     * no recorded prior attempt to back off from).
     */
    fun nextEligibleAttempt(sync: SyncMetadata): Instant?

    /** Whether the record is eligible for a push attempt at [now]. */
    fun isEligible(sync: SyncMetadata, now: Instant): Boolean
}

/**
 * Exponential backoff with a configurable base and multiplier.
 *
 * The delay after the n-th failure (1-based [SyncMetadata.retryCount]) is
 * `baseSeconds * multiplier^(retryCount - 1)`, matching the design's 1s, 4s,
 * 16s, … progression with `baseSeconds = 1`, `multiplier = 4`. A record that
 * has never been attempted (`lastAttemptAt == null` or `retryCount == 0`) is
 * always eligible.
 *
 * @param baseSeconds delay in seconds after the first failure.
 * @param multiplier growth factor applied per additional consecutive failure.
 * @param maxSeconds upper bound on the computed delay so backoff never grows
 *   unbounded.
 */
class ExponentialBackoffPolicy(
    private val baseSeconds: Double = 1.0,
    private val multiplier: Double = 4.0,
    private val maxSeconds: Double = 256.0,
) : BackoffPolicy {

    /**
     * The backoff delay, in seconds, that must elapse after `lastAttemptAt`
     * before the record may be retried, given its current `retryCount`.
     */
    fun delaySeconds(retryCount: Int): Double {
        if (retryCount <= 0) return 0.0
        val exponent = (retryCount - 1).toDouble()
        val delay = baseSeconds * multiplier.pow(exponent)
        return min(delay, maxSeconds)
    }

    override fun nextEligibleAttempt(sync: SyncMetadata): Instant? {
        val last = sync.lastAttemptAt
        if (last == null || sync.retryCount <= 0) {
            // Never attempted (or no recorded attempt): eligible immediately.
            return null
        }
        return last.plusMillis((delaySeconds(sync.retryCount) * 1000).toLong())
    }

    override fun isEligible(sync: SyncMetadata, now: Instant): Boolean {
        val eligibleAt = nextEligibleAttempt(sync) ?: return true
        return !now.isBefore(eligibleAt)
    }
}
