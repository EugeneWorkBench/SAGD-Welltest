//
//  QR / barcode scanner
//
//  Implements the design's "QR Scanning Flow" for iOS. The camera + decode
//  primitive is AVFoundation's `AVCaptureSession` feeding an
//  `AVCaptureMetadataOutput` configured for `.qr` and common 1-D barcode object
//  types. That primitive is wrapped behind a small protocol seam
//  (`QRCodeSource`) so the *timing* and *decode pipeline* of the scan — the
//  2-second decode budget and the 10-second unreadable timeout with a rescan
//  control — live in a deterministic, platform-agnostic coordinator
//  (`QRScannerCoordinator`) that the integration test (task 18.5) can drive with
//  a controllable clock and a fake source, without a real camera.
//
//  Flow (design "QR Scanning Flow"):
//    * Camera streams frames; the source emits decoded identifier strings.
//    * A decode is expected within 2 s of the code entering view (Req 6.1); the
//      coordinator records whether the first decode met that budget.
//    * If nothing decodes within 10 s, the scan is reported `unreadable` and a
//      rescan control is offered (Req 6.4); `rescan()` restarts the budget.
//    * On a decode, the string is handed to the injected `WellLookupUseCase`,
//      whose `Found` / `NotFound` / `UnavailableOffline` result is surfaced to
//      the caller (Req 6.2, 6.3, 6.5, 6.6).
//
//  All time-sensitive behavior reads "now" and sleeps through the injected
//  `SyncClock` seam (shared with the sync engine), so the budgets are driven
//  deterministically under test.
//
//  Requirements: 6.1, 6.3, 6.4, 6.5, 6.6.

import Foundation

// MARK: - Scan outcome

/// The terminal outcome of a single scan attempt, surfaced to the caller once
/// the coordinator either decodes-and-looks-up a code or exhausts the
/// unreadable timeout. Mirrors the branches of the design's QR Scanning Flow.
public enum QRScanOutcome: Sendable, Equatable {
    /// A code decoded and the well was found in the local store. Carries the
    /// lookup result (`.found`), the decoded identifier, and whether the first
    /// decode met the 2-second budget (Req 6.1) — the latter lets the UI and
    /// the timing integration test assert the budget without a real camera.
    case found(lookup: WellLookupResult, decodedId: String, metDecodeBudget: Bool)
    /// A code decoded but no matching well exists locally and the device is
    /// online: "not found" + rescan (Req 6.3). Carries the decoded identifier.
    case notFound(decodedId: String)
    /// A code decoded but no matching well exists locally and the device is
    /// offline: "unavailable offline" (Req 6.6). Carries the decoded identifier.
    case unavailableOffline(decodedId: String)
    /// No code decoded within the 10-second unreadable budget (Req 6.4). The
    /// caller presents an "unreadable" message and a rescan control.
    case unreadable
}

// MARK: - Code source seam

/// The camera + decode primitive behind a protocol seam. A concrete source
/// starts a capture session and emits each decoded identifier string as frames
/// are processed; the coordinator owns the timing and lookup around it.
///
/// Production is the AVFoundation-backed `AVFoundationQRCodeSource` (guarded by
/// `#if canImport(AVFoundation)`). Tests supply a fake that emits decoded
/// strings (or never emits, to exercise the unreadable timeout) on command,
/// keeping the whole decode pipeline deterministic.
public protocol QRCodeSource: Sendable {
    /// Begin streaming decoded identifier strings. The returned stream yields
    /// one element per successfully decoded code; it may yield nothing at all
    /// (an undecodable code / blank frames), which drives the unreadable
    /// timeout. The coordinator stops consuming after the first decode.
    func decodedStrings() -> AsyncStream<String>

    /// Start the underlying capture session (idempotent). Called when a scan —
    /// or a rescan — begins so the camera is streaming while the budget runs.
    func start() async

    /// Stop the underlying capture session (idempotent). Called once a scan
    /// reaches a terminal outcome so the camera is released.
    func stop() async
}

// MARK: - Scanner coordinator

/// Drives a single scan to a terminal `QRScanOutcome`, owning the 2-second
/// decode budget and the 10-second unreadable timeout and performing the
/// well lookup on a decode. Platform-agnostic: it depends only on the
/// `QRCodeSource` seam, the injected `WellLookupUseCase`, and the injected
/// `SyncClock`, so the integration test drives it deterministically.
///
/// Concurrency: an `actor` so the decode race (first decode vs. unreadable
/// deadline) and the `rescan()` entry point are serialized without manual
/// locking.
public actor QRScannerCoordinator {

    /// Decode budget: a code is expected to decode within this many seconds of
    /// entering the camera view (Req 6.1).
    public static let decodeBudgetSeconds: Double = 2

    /// Unreadable timeout: if nothing decodes within this many seconds the scan
    /// is reported `unreadable` with a rescan control (Req 6.4).
    public static let unreadableTimeoutSeconds: Double = 10

    private let source: QRCodeSource
    private let lookup: WellLookupUseCase
    private let clock: SyncClock

    /// - Parameters:
    ///   - source: Camera + decode primitive behind the seam.
    ///   - lookup: Domain use case a decoded identifier is handed to (Req 6.2,
    ///     6.5). Looks up the local store only — never the network.
    ///   - clock: Injectable time source for the decode and unreadable budgets;
    ///     production uses the wall clock, tests a controllable clock.
    public init(
        source: QRCodeSource,
        lookup: WellLookupUseCase,
        clock: SyncClock = SystemSyncClock()
    ) {
        self.source = source
        self.lookup = lookup
        self.clock = clock
    }

    /// Run one scan attempt to a terminal outcome.
    ///
    /// Starts the source, then races the first decode against the 10-second
    /// unreadable deadline. On a decode within the window the identifier is
    /// looked up and a `found` / `notFound` / `unavailableOffline` outcome is
    /// returned, tagging whether the first decode met the 2-second budget. If
    /// the deadline wins, `unreadable` is returned. The source is stopped
    /// before returning in every case.
    public func scan() async -> QRScanOutcome {
        await source.start()
        defer { Task { await source.stop() } }

        let startedAt = clock.now()

        guard let decoded = await firstDecode(startedAt: startedAt) else {
            // Nothing decoded within the unreadable timeout (Req 6.4).
            return .unreadable
        }

        // A decode arrived; record whether it met the 2-second budget (Req 6.1).
        let elapsed = clock.now().timeIntervalSince(startedAt)
        let metDecodeBudget = elapsed <= Self.decodeBudgetSeconds

        // Hand the decoded identifier to the domain lookup (Req 6.2, 6.5).
        let result = await lookup.lookup(wellId: decoded)
        switch result {
        case .found:
            return .found(lookup: result, decodedId: decoded, metDecodeBudget: metDecodeBudget)
        case .notFound:
            return .notFound(decodedId: decoded)
        case .unavailableOffline:
            return .unavailableOffline(decodedId: decoded)
        }
    }

    /// Restart a scan after an `unreadable` (or any) outcome — the rescan
    /// control (Req 6.4). Functionally identical to `scan()`: the 2-second and
    /// 10-second budgets both restart from the moment this is called.
    public func rescan() async -> QRScanOutcome {
        await scan()
    }

    // MARK: - Decode race

    /// Await the first decoded string or the unreadable deadline, whichever
    /// comes first. Returns the decoded identifier, or `nil` when the deadline
    /// elapses with no decode.
    ///
    /// Implemented as a two-task race over the clock and the source so the
    /// timeout is driven by the injected clock (deterministic under test) rather
    /// than wall-clock waiting. The two tasks report distinct outcomes so a
    /// source that *completes without emitting* (an empty fake, or a camera that
    /// yielded no code) is not mistaken for the deadline: only the deadline task
    /// ends the race with "unreadable".
    private func firstDecode(startedAt: Date) async -> String? {
        /// Which task produced a result, so the race can tell a real decode and
        /// the unreadable deadline apart from a source that simply finished.
        enum RaceResult: Sendable {
            case decoded(String)
            case sourceEnded
            case deadlineReached
        }

        return await withTaskGroup(of: RaceResult.self) { group in
            // Decode task: the first element the source yields, or `sourceEnded`
            // if the stream finishes without emitting.
            group.addTask { [source] in
                for await decoded in source.decodedStrings() {
                    return .decoded(decoded)
                }
                return .sourceEnded
            }

            // Deadline task: sleep out the unreadable budget, then report that
            // the unreadable timeout was reached (Req 6.4).
            group.addTask { [clock] in
                await clock.sleep(seconds: Self.unreadableTimeoutSeconds)
                return .deadlineReached
            }

            // Consume results until a decode arrives or the deadline is reached.
            // A `sourceEnded` on its own does not end the scan: a camera that
            // momentarily yields no code keeps the budget running until the
            // deadline task fires.
            defer { group.cancelAll() }
            for await result in group {
                switch result {
                case let .decoded(id):
                    return id
                case .deadlineReached:
                    return nil
                case .sourceEnded:
                    continue
                }
            }
            // Both tasks finished with no decode (e.g. the deadline was reached
            // in the same turn the source ended): report unreadable.
            return nil
        }
    }
}
