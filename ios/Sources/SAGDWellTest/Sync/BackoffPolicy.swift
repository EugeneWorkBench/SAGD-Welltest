//
//  Exponential backoff policy
//
//  After a failed push a record stays `pending` and is retried on a later
//  connectivity event, but not immediately: retries are spaced by an
//  exponential backoff keyed on the record's `lastAttemptAt` and its
//  `retryCount` (design "Retry and backoff": e.g. 1s, 4s, 16s, 64s, 256s). The
//  engine asks this policy, given a record's `SyncMetadata` and "now", whether
//  the record's backoff window has elapsed; a record still inside its window is
//  skipped this cycle and revisited on the next one.
//
//  Keying the delay on `lastAttemptAt` (persisted on the record) rather than on
//  in-memory timers means the schedule survives app restarts and background
//  relaunches: a record relaunched mid-backoff resumes its wait from where it
//  left off.
//
//  Introduced by task 15.1.

import Foundation

/// Decides, for a given record, whether enough time has passed since its last
/// attempt to try it again.
public protocol BackoffPolicy: Sendable {
    /// The earliest instant at which a record with the given sync metadata is
    /// eligible for another push attempt. `nil` means "eligible now" (there is
    /// no recorded prior attempt to back off from).
    func nextEligibleAttempt(for sync: SyncMetadata) -> Date?

    /// Whether the record is eligible for a push attempt at `now`.
    func isEligible(_ sync: SyncMetadata, now: Date) -> Bool
}

/// Exponential backoff with a configurable base and exponent.
///
/// The delay after the *n*-th failure (1-based `retryCount`) is
/// `base * multiplier^(retryCount - 1)`, matching the design's 1s, 4s, 16s, …
/// progression with `base = 1`, `multiplier = 4`. A record that has never been
/// attempted (`lastAttemptAt == nil` or `retryCount == 0`) is always eligible.
public struct ExponentialBackoffPolicy: BackoffPolicy {
    /// Delay in seconds after the first failure.
    public let baseSeconds: Double
    /// Growth factor applied per additional consecutive failure.
    public let multiplier: Double
    /// Upper bound on the computed delay so backoff never grows unbounded.
    public let maxSeconds: Double

    public init(
        baseSeconds: Double = 1,
        multiplier: Double = 4,
        maxSeconds: Double = 256
    ) {
        self.baseSeconds = baseSeconds
        self.multiplier = multiplier
        self.maxSeconds = maxSeconds
    }

    /// The backoff delay, in seconds, that must elapse after `lastAttemptAt`
    /// before the record may be retried, given its current `retryCount`.
    public func delaySeconds(forRetryCount retryCount: Int) -> Double {
        guard retryCount > 0 else { return 0 }
        let exponent = Double(retryCount - 1)
        let delay = baseSeconds * pow(multiplier, exponent)
        return min(delay, maxSeconds)
    }

    public func nextEligibleAttempt(for sync: SyncMetadata) -> Date? {
        guard let last = sync.lastAttemptAt, sync.retryCount > 0 else {
            // Never attempted (or no recorded attempt): eligible immediately.
            return nil
        }
        return last.addingTimeInterval(delaySeconds(forRetryCount: sync.retryCount))
    }

    public func isEligible(_ sync: SyncMetadata, now: Date) -> Bool {
        guard let eligibleAt = nextEligibleAttempt(for: sync) else { return true }
        return now >= eligibleAt
    }
}
