//
//  Engine-backed sync-work signal
//
//  The repository layer tells the sync engine that a new `pending` record
//  exists through the tiny `SyncWorkSignaling` seam (see `SyncWorkSignal.swift`
//  in the Repository layer) — a single fire-and-forget `enqueuePendingWork()`.
//  The Repository layer owns that protocol and must not depend on the Sync
//  layer concretely (the dependency rule keeps Repository below Sync), so the
//  concrete signal that actually drives a cycle lives here, in the Sync layer,
//  where depending on the engine is allowed.
//
//  `EngineSyncWorkSignal` forwards each `enqueuePendingWork()` to the engine's
//  `onConnectivityAvailable()`, which schedules (and coalesces) a sync cycle.
//  Using `onConnectivityAvailable()` rather than `runSyncCycle()` directly
//  means a save made while offline simply schedules a cycle that no-ops until
//  connectivity returns — identical to the connectivity-restore path — so a new
//  record never forces a push on a dead link, and overlapping saves coalesce
//  onto one in-flight cycle rather than racing the same records.
//
//  The engine is injected after construction (via `attach(_:)`) because the
//  repositories, the signal, and the engine form a cycle at assembly time: the
//  repositories need a signal to be constructed, and the engine needs the
//  repositories' `SyncRepository`. The composition root breaks the cycle by
//  creating the signal first, building the repositories and engine with it, and
//  then attaching the engine to the signal. Calls that arrive before attach are
//  dropped (there is nothing to drive yet); the engine's own connectivity
//  monitor and background scheduler still cover any records committed in that
//  brief window on the next cycle.
//
//  Wired by task 22.2.

import Foundation

/// A `SyncWorkSignaling` that drives a `SyncEngine` when the repository layer
/// reports new pending work. Thread-safe: the engine reference is guarded so it
/// can be attached once at assembly time and read from any isolation domain.
public final class EngineSyncWorkSignal: SyncWorkSignaling, @unchecked Sendable {

    private let lock = NSLock()
    private var engine: SyncEngine?

    public init() {}

    /// Attach the engine the signal should drive. Called once by the
    /// composition root after the engine is built. A second attach replaces the
    /// reference (harmless; the graph attaches exactly once).
    public func attach(_ engine: SyncEngine) {
        lock.lock()
        self.engine = engine
        lock.unlock()
    }

    /// A new pending record was committed. Schedule a sync cycle via the
    /// engine's connectivity-available entry point so the cycle coalesces with
    /// any in-flight run and no-ops cleanly if the device is currently offline.
    public func enqueuePendingWork() async {
        lock.lock()
        let engine = self.engine
        lock.unlock()
        await engine?.onConnectivityAvailable()
    }
}
