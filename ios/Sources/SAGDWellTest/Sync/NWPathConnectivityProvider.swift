//
//  NWPathMonitor-backed connectivity provider
//
//  The production `ConnectivityProvider`. An `NWPathMonitor` on a dedicated
//  background queue reports path updates; a `.satisfied` path maps to
//  `.satisfied`, anything else to `.unsatisfied`. The design calls for this
//  exact wiring (design "Connectivity detection"): `.satisfied` paths trigger
//  the engine's `onConnectivityAvailable()`, and loss flips the offline
//  indicator within 5 s.
//
//  This type is compiled only where the Network framework is available (iOS,
//  macOS). Tests on any platform use an in-memory fake instead of this type, so
//  the engine's logic stays fully testable without a network stack.
//
//  Introduced by task 15.1.

import Foundation

#if canImport(Network)
import Network

/// `NWPathMonitor`-backed connectivity provider used in the running app.
public final class NWPathConnectivityProvider: ConnectivityProvider, @unchecked Sendable {

    private let monitor: NWPathMonitor
    private let queue: DispatchQueue
    private let lock = NSLock()
    private var _currentStatus: ConnectivityStatus
    private var continuations: [UUID: AsyncStream<ConnectivityStatus>.Continuation] = [:]
    private var started = false

    public init(queue: DispatchQueue = DispatchQueue(label: "com.sagd.welltest.connectivity")) {
        self.monitor = NWPathMonitor()
        self.queue = queue
        // Assume unsatisfied until the first path update arrives; the monitor
        // delivers the real current path immediately on start.
        self._currentStatus = .unsatisfied
    }

    public var currentStatus: ConnectivityStatus {
        lock.lock(); defer { lock.unlock() }
        return _currentStatus
    }

    public func statusStream() -> AsyncStream<ConnectivityStatus> {
        AsyncStream { continuation in
            let token = UUID()
            lock.lock()
            continuations[token] = continuation
            // Emit the current status immediately so consumers have a baseline.
            continuation.yield(_currentStatus)
            let needsStart = !started
            started = true
            lock.unlock()

            if needsStart {
                monitor.pathUpdateHandler = { [weak self] path in
                    self?.handle(path: path)
                }
                monitor.start(queue: queue)
            }

            continuation.onTermination = { [weak self] _ in
                self?.removeContinuation(token)
            }
        }
    }

    private func handle(path: NWPath) {
        let status: ConnectivityStatus = path.status == .satisfied ? .satisfied : .unsatisfied
        lock.lock()
        guard status != _currentStatus else { lock.unlock(); return }
        _currentStatus = status
        let targets = Array(continuations.values)
        lock.unlock()
        for continuation in targets {
            continuation.yield(status)
        }
    }

    private func removeContinuation(_ token: UUID) {
        lock.lock()
        continuations[token] = nil
        lock.unlock()
    }

    deinit {
        monitor.cancel()
    }
}
#endif
