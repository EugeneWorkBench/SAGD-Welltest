//
//  Connectivity provider and offline indicator
//
//  The sync engine reacts to connectivity transitions but must not depend on
//  `NWPathMonitor` directly, or it could not be driven deterministically in
//  tests. `ConnectivityProvider` is the seam: production wires an
//  `NWPathMonitor`-backed provider (below); the connectivity-timing integration
//  test (task 15.8) wires a fake that flips connectivity on command with a
//  controllable clock.
//
//  Two transitions matter:
//    * becomes satisfied  -> the engine schedules a sync cycle so syncing
//                            begins within 60 s of restoration (Req 2.2).
//    * becomes unsatisfied -> the global offline indicator is published within
//                            5 s of loss (Req 2.8).
//
//  Introduced by task 15.1.

import Foundation

/// A connectivity transition observed by the provider.
public enum ConnectivityStatus: Sendable, Equatable {
    /// The device has a usable network path.
    case satisfied
    /// The device has no usable network path.
    case unsatisfied
}

/// Publishes connectivity transitions. The engine starts monitoring, then
/// receives a stream of statuses; the first value is the current status at the
/// time monitoring starts.
public protocol ConnectivityProvider: Sendable {
    /// The most recently observed status (the current connectivity).
    var currentStatus: ConnectivityStatus { get }
    /// A stream of connectivity transitions, beginning with the current status.
    /// The engine consumes this to trigger sync cycles and to drive the offline
    /// indicator.
    func statusStream() -> AsyncStream<ConnectivityStatus>
}

/// Publishes the global offline indicator the UI observes. Kept separate from
/// the provider so the engine owns *when* the indicator flips (within 5 s of a
/// loss transition — Req 2.8) while the UI only observes the published value.
public protocol OfflineIndicatorPublishing: Sendable {
    /// Publish whether the device is currently offline. The UI reflects this.
    func setOffline(_ isOffline: Bool) async
}

/// An in-memory offline indicator backed by an `AsyncStream` the UI can
/// observe. Thread-safe via an actor-isolated store.
public actor OfflineIndicator: OfflineIndicatorPublishing {
    private var isOffline: Bool
    private var continuations: [UUID: AsyncStream<Bool>.Continuation] = [:]

    public init(initiallyOffline: Bool = false) {
        self.isOffline = initiallyOffline
    }

    /// The current offline value.
    public var offline: Bool { isOffline }

    public func setOffline(_ newValue: Bool) {
        guard newValue != isOffline else { return }
        isOffline = newValue
        for continuation in continuations.values {
            continuation.yield(newValue)
        }
    }

    /// Observe the offline indicator. Emits the current value immediately, then
    /// each change. The UI binds to this for the global offline banner.
    public func observe() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            let token = UUID()
            continuations[token] = continuation
            continuation.yield(isOffline)
            continuation.onTermination = { [weak self] _ in
                Task { await self?.removeContinuation(token) }
            }
        }
    }

    private func removeContinuation(_ token: UUID) {
        continuations[token] = nil
    }
}
