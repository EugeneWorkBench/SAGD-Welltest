//
//  Sync-work signal
//
//  A minimal hook the repository layer uses to tell the sync engine that a new
//  pending record exists, without the Repository layer depending on the Sync
//  layer concretely (the dependency rule keeps Repository below Sync). Task 15
//  wires a real signal that schedules a sync cycle; until then the default
//  no-op signal lets repositories be constructed and tested in isolation.
//
//  Implemented by task 5.1.

import Foundation

/// Notified by the repositories after a record is committed in `pending`
/// state so the sync engine can schedule a push. Kept intentionally tiny — a
/// single fire-and-forget method — so it imposes no ordering or back-pressure
/// on the save path.
public protocol SyncWorkSignaling: Sendable {
    /// A new pending record was committed and is eligible for push.
    func enqueuePendingWork() async
}

/// A signal that does nothing. Used as the default so repositories work before
/// the sync engine is wired in (task 15) and in tests that do not exercise sync.
public struct NoopSyncWorkSignal: SyncWorkSignaling {
    public init() {}
    public func enqueuePendingWork() async {}
}
