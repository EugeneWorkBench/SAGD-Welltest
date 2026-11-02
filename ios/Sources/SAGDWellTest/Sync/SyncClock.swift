//
//  Sync clock
//
//  All time-sensitive sync behavior — the 60-second connectivity-restore
//  trigger (Req 2.2), the 5-second offline-indicator deadline (Req 2.8), and
//  the exponential backoff between retries — reads "now" and sleeps through a
//  single injected `SyncClock`. Production uses the wall clock and real sleeps;
//  tests inject a controllable clock so timing assertions are deterministic and
//  do not actually wait (design "Integration testing": controllable clock +
//  fake `NWPathMonitor`).
//
//  Introduced by task 15.1.

import Foundation

/// A source of the current time and a way to suspend for a duration. Injected
/// everywhere the sync engine reasons about time so tests can drive timing
/// without real delays.
public protocol SyncClock: Sendable {
    /// The current instant (UTC).
    func now() -> Date
    /// Suspend the current task for `seconds`. A controllable clock may advance
    /// virtual time instead of actually waiting.
    func sleep(seconds: Double) async
}

/// The production clock: wall-clock `Date` and a real cooperative sleep.
public struct SystemSyncClock: SyncClock {
    public init() {}

    public func now() -> Date { Date() }

    public func sleep(seconds: Double) async {
        guard seconds > 0 else { return }
        // Cooperative sleep; cancellation simply ends the wait early, which is
        // fine — the next connectivity event re-drives the cycle.
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
}
