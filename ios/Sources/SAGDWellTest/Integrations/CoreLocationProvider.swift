//
//  CoreLocation-backed location provider
//
//  The production `LocationProviding` implementation. Wraps a
//  `CLLocationManager`, requests a single fix, and bounds the wait at 30
//  seconds against an injected clock (Req 7.5). On the first acceptable
//  reading it resolves `.fix` with decimal-degree coordinates and a UTC
//  one-second timestamp (Req 7.1); if the bound elapses first — including when
//  the system never delivers a reading or authorization is denied — it
//  resolves `.timedOut` so the operator may proceed.
//
//  Guarded by `#if canImport(CoreLocation)` so the module still builds on
//  platforms without CoreLocation; the escalation-capture coordinator depends
//  only on the `LocationProviding` seam, never on this type directly.
//
//  Implemented by task 18.2.
//
//  Requirements: 7.1, 7.5.

#if canImport(CoreLocation)

import Foundation
import CoreLocation

/// A `CLLocationManager`-backed location provider.
///
/// Each `requestFix()` call starts a one-shot request and races the manager's
/// first acceptable reading against a 30-second timeout. Whichever resolves
/// first wins; the loser is ignored. The manager is driven on the main actor
/// (CoreLocation delivers delegate callbacks on the thread its run loop lives
/// on), while the timeout and continuation bookkeeping are funneled through a
/// private actor so a late reading can never resume the continuation twice.
public final class CoreLocationProvider: LocationProviding {

    /// Serializes continuation handoff so exactly one of "first fix" and
    /// "timeout" resolves the request.
    private actor Gate {
        private var resumed = false
        private var continuation: CheckedContinuation<LocationResult, Never>?
        private var pending: LocationResult?

        /// Attach the awaiting continuation. If an outcome already arrived
        /// (resolve raced ahead of attach), resume immediately with it.
        func attach(_ continuation: CheckedContinuation<LocationResult, Never>) {
            if let pending {
                continuation.resume(returning: pending)
                self.pending = nil
            } else {
                self.continuation = continuation
            }
        }

        /// Resolve the request once; subsequent calls are no-ops. Returns
        /// `true` only for the call that actually resolved it, so the caller
        /// can tear the manager down exactly once. If the continuation is not
        /// yet attached, the first outcome is buffered for `attach`.
        @discardableResult
        func resolve(_ result: LocationResult) -> Bool {
            guard !resumed else { return false }
            resumed = true
            if let continuation {
                continuation.resume(returning: result)
                self.continuation = nil
            } else {
                pending = result
            }
            return true
        }
    }

    private let timeout: Double
    private let clock: SyncClock
    private let now: @Sendable () -> Date
    private let makeManager: @Sendable () -> CLLocationManager

    /// - Parameters:
    ///   - timeout: The fix bound in seconds; defaults to 30 (Req 7.5).
    ///   - clock: Drives the timeout; injectable so the bound is testable
    ///     without real waiting.
    ///   - now: Supplies the capture instant stamped onto the fix.
    ///   - makeManager: Factory for the underlying `CLLocationManager`,
    ///     created on the main actor.
    public init(
        timeout: Double = locationFixTimeoutSeconds,
        clock: SyncClock = SystemSyncClock(),
        now: @escaping @Sendable () -> Date = { Date() },
        makeManager: @escaping @Sendable () -> CLLocationManager = { CLLocationManager() }
    ) {
        self.timeout = timeout
        self.clock = clock
        self.now = now
        self.makeManager = makeManager
    }

    public func requestFix() async -> LocationResult {
        let gate = Gate()
        let now = self.now

        // Build and retain the delegate + manager on the main actor for the
        // lifetime of the request. The delegate forwards the first acceptable
        // reading (or a hard failure) to the gate.
        let holder = await MainActor.run { () -> ManagerHolder in
            let manager = self.makeManager()
            let delegate = Delegate { reading in
                Task {
                    let fix = LocationFix(
                        latitude: reading.coordinate.latitude,
                        longitude: reading.coordinate.longitude,
                        capturedAt: utcSecondTruncated(now())
                    )
                    await gate.resolve(.fix(fix))
                }
            } onFailure: {
                // A hard CoreLocation failure (e.g. authorization denied) is
                // treated as "no fix"; the 30-second contract still applies, so
                // we simply let the request resolve as a timeout.
                Task { await gate.resolve(.timedOut) }
            }
            manager.delegate = delegate
            manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
            manager.requestWhenInUseAuthorization()
            manager.requestLocation()
            return ManagerHolder(manager: manager, delegate: delegate)
        }

        // Race the first reading against the 30-second bound. The gate serializes
        // the two outcomes: whichever of "first fix" and "timeout elapsed"
        // arrives first resolves the single continuation, and the other is a
        // no-op. The continuation's returned value is the winner.
        let timeoutTask = Task {
            await self.clock.sleep(seconds: self.timeout)
            await gate.resolve(.timedOut)
        }
        let result = await withCheckedContinuation { continuation in
            Task { await gate.attach(continuation) }
        }
        timeoutTask.cancel()

        // Tear the manager down on the main actor; releasing the holder also
        // releases the strong delegate reference.
        await MainActor.run {
            holder.manager.delegate = nil
            holder.manager.stopUpdatingLocation()
        }

        return result
    }

    /// Keeps the manager and its delegate alive for the duration of a request.
    private struct ManagerHolder: @unchecked Sendable {
        let manager: CLLocationManager
        let delegate: Delegate
    }

    /// Bridges `CLLocationManagerDelegate` callbacks to the gate. Retained by
    /// the manager's `delegate` property via the holder for the request's life.
    private final class Delegate: NSObject, CLLocationManagerDelegate {
        private let onFix: @Sendable (CLLocation) -> Void
        private let onFailure: @Sendable () -> Void

        init(
            onFix: @escaping @Sendable (CLLocation) -> Void,
            onFailure: @escaping @Sendable () -> Void
        ) {
            self.onFix = onFix
            self.onFailure = onFailure
        }

        func locationManager(
            _ manager: CLLocationManager,
            didUpdateLocations locations: [CLLocation]
        ) {
            guard let location = locations.last else { return }
            onFix(location)
        }

        func locationManager(
            _ manager: CLLocationManager,
            didFailWithError error: Error
        ) {
            onFailure()
        }
    }
}

#endif
