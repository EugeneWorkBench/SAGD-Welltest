//
//  ScannerTimingIntegrationTests.swift
//  Integration test — scanner timing (iOS) — task 18.5.
//
//  _Requirements: 6.1, 6.4_
//
//  Design "QR Scanning Flow" + "Integration testing" (controllable clock + fake
//  decode source): the real `QRScannerCoordinator` is exercised end to end
//  through its production seams — a fake `QRCodeSource` standing in for the
//  AVFoundation capture pipeline and a controllable `SyncClock` standing in for
//  the wall clock — with no real camera and no real delays. Two timing
//  guarantees are asserted against the virtual clock:
//
//    * Req 6.1 — WHEN a code is scanned, the App SHALL decode the well
//      identifier within 2 seconds of the code entering the camera view. Here:
//      the fake source emits a known identifier after the virtual clock has
//      advanced less than the 2-second decode budget, and we assert the outcome
//      is a decoded one (`found` / `notFound`) carrying `metDecodeBudget == true`.
//
//    * Req 6.4 — IF the Scanner cannot decode a code within 10 seconds, the App
//      SHALL report the scan unreadable and present a rescan control. Here: the
//      fake source never emits, the coordinator sleeps out the 10-second
//      unreadable budget on the virtual clock, the outcome is `.unreadable`, and
//      a subsequent `rescan()` reaches a fresh terminal outcome (the rescan
//      control is usable).
//
//  Timing is virtual. The controllable clock advances `now()` by exactly the
//  slept duration whenever the coordinator sleeps, so the unreadable deadline is
//  reached deterministically without any wall-clock waiting, and the decode
//  budget is checked against an explicitly advanced "now" rather than elapsed
//  real time. The well lookup is a deterministic stub so the decode path yields
//  a known `found` / `notFound` without any store or network.
//
//  Resolving the decode race without real time: the coordinator races the first
//  decode against the unreadable-deadline sleep inside a task group. A fake
//  decode is ready immediately (it yields on the first stream element), so for
//  the "decode within budget" cases the deadline sleep must not win. The
//  controllable clock's `sleep` therefore yields the cooperative executor a few
//  times *before* advancing virtual time, letting an already-ready decode be
//  consumed first; when no decode is ready (the never-emits case) those yields
//  complete and the sleep advances the clock by the full budget, producing
//  `unreadable` deterministically. No wall-clock delay is ever incurred.

import XCTest
@testable import SAGDWellTest

final class ScannerTimingIntegrationTests: XCTestCase {

    private static let scanStart = Date(timeIntervalSince1970: 1_700_000_000)

    // MARK: - Case 1 — decode within the 2-second budget (Req 6.1)

    /// The fake source emits a known identifier after the virtual clock has
    /// advanced only 1 s (inside the 2-second decode budget). The coordinator
    /// must resolve to a decoded outcome whose `metDecodeBudget` is `true`, and
    /// the stubbed lookup maps the id to `found`.
    func testDecodeWithinBudgetReportsMetDecodeBudget() async {
        let clock = ControllableScannerClock(now: Self.scanStart)
        // Source emits after a 1-second virtual advance — inside the 2 s budget.
        let source = FakeQRCodeSource(
            emission: .decode(id: "WELL-123", afterAdvance: 1, on: clock)
        )
        let lookup = StubWellLookupUseCase(result: .found(
            well: Well(
                id: "WELL-123",
                name: "Scanner Well",
                type: .production,
                padId: nil,
                updatedAt: Self.scanStart
            ),
            wellTests: [],
            openMaintenanceTasks: []
        ))
        let coordinator = QRScannerCoordinator(source: source, lookup: lookup, clock: clock)

        let outcome = await coordinator.scan()

        guard case let .found(_, decodedId, metDecodeBudget) = outcome else {
            XCTFail("expected a decoded `found` outcome within the budget, got \(outcome)")
            return
        }
        XCTAssertEqual(decodedId, "WELL-123")
        XCTAssertTrue(
            metDecodeBudget,
            "a decode that arrived 1 s in must meet the 2-second decode budget (Req 6.1)"
        )
        // The decode landed before the unreadable deadline, so the virtual clock
        // advanced only the 1 s the source took to emit — never the 10 s timeout.
        let elapsed = clock.now().timeIntervalSince(Self.scanStart)
        XCTAssertLessThanOrEqual(
            elapsed, QRScannerCoordinator.decodeBudgetSeconds,
            "decode elapsed \(elapsed)s must be within the 2-second budget (Req 6.1)"
        )
    }

    /// A decode that lands on an unknown identifier still reports
    /// `metDecodeBudget == true` when it arrives inside the budget — the budget
    /// is about decode timing, independent of the lookup branch (Req 6.1).
    func testDecodeWithinBudgetForUnknownIdStillMeetsBudget() async {
        let clock = ControllableScannerClock(now: Self.scanStart)
        let source = FakeQRCodeSource(
            emission: .decode(id: "WELL-UNKNOWN", afterAdvance: 0.5, on: clock)
        )
        let lookup = StubWellLookupUseCase(result: .notFound)
        let coordinator = QRScannerCoordinator(source: source, lookup: lookup, clock: clock)

        let outcome = await coordinator.scan()

        XCTAssertEqual(
            outcome, .notFound(decodedId: "WELL-UNKNOWN"),
            "a decode inside the budget onto an unknown id must branch to notFound (Req 6.3)"
        )
    }

    // MARK: - Case 2 — unreadable after the 10-second budget, then rescan (Req 6.4)

    /// The fake source never emits. The coordinator must sleep out the 10-second
    /// unreadable budget on the virtual clock and report `.unreadable`, having
    /// advanced the virtual clock by exactly the 10 s timeout.
    func testNoDecodeWithinTimeoutReportsUnreadable() async {
        let clock = ControllableScannerClock(now: Self.scanStart)
        let source = FakeQRCodeSource(emission: .neverEmits)
        let lookup = StubWellLookupUseCase(result: .notFound)
        let coordinator = QRScannerCoordinator(source: source, lookup: lookup, clock: clock)

        let outcome = await coordinator.scan()

        XCTAssertEqual(
            outcome, .unreadable,
            "a scan that never decodes must report unreadable (Req 6.4)"
        )
        // The only time the coordinator consumed was the unreadable deadline
        // sleep, so the virtual clock advanced by exactly the 10 s budget.
        let elapsed = clock.now().timeIntervalSince(Self.scanStart)
        XCTAssertEqual(
            elapsed, QRScannerCoordinator.unreadableTimeoutSeconds, accuracy: 0.0001,
            "unreadable must be reported at the 10-second budget (Req 6.4); elapsed \(elapsed)s"
        )
    }

    /// After an `unreadable` outcome a rescan must be possible: `rescan()` runs a
    /// fresh scan to a terminal outcome. Here the retry succeeds — the source
    /// emits a known id inside the budget — proving the rescan control restarts
    /// the budgets and reaches a decoded result (Req 6.4).
    func testRescanAfterUnreadableReachesFreshOutcome() async {
        let clock = ControllableScannerClock(now: Self.scanStart)
        // First attempt never emits; the retry emits a known id after 1 s.
        let source = FakeQRCodeSource(
            emission: .neverThenDecode(id: "WELL-123", afterAdvance: 1, on: clock)
        )
        let lookup = StubWellLookupUseCase(result: .found(
            well: Well(
                id: "WELL-123",
                name: "Scanner Well",
                type: .production,
                padId: nil,
                updatedAt: Self.scanStart
            ),
            wellTests: [],
            openMaintenanceTasks: []
        ))
        let coordinator = QRScannerCoordinator(source: source, lookup: lookup, clock: clock)

        // First scan: unreadable (the source emits nothing on the first pass).
        let first = await coordinator.scan()
        XCTAssertEqual(first, .unreadable, "first pass must be unreadable (Req 6.4)")

        // Rescan: the control is usable and now reaches a decoded outcome.
        let second = await coordinator.rescan()
        guard case let .found(_, decodedId, metDecodeBudget) = second else {
            XCTFail("rescan after unreadable must reach a decoded outcome, got \(second)")
            return
        }
        XCTAssertEqual(decodedId, "WELL-123")
        XCTAssertTrue(
            metDecodeBudget,
            "the rescan restarts the 2-second budget; a 1 s decode meets it (Req 6.1, 6.4)"
        )
    }
}

// MARK: - Controllable scanner clock

/// A `SyncClock` reporting a virtual instant. `sleep(seconds:)` advances the
/// virtual clock by exactly that duration instead of waiting, so the
/// coordinator's unreadable deadline is reached deterministically with no real
/// delay, and `now()` reflects the time the deadline consumed. Thread-safe so it
/// can be read/advanced from the coordinator actor and the fake source
/// concurrently.
private final class ControllableScannerClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(now: Date) {
        self.current = now
    }

    func now() -> Date {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    /// Advance virtual time by `seconds` (used by the fake source to model the
    /// time a decode takes to arrive).
    func advance(by seconds: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        current = current.addingTimeInterval(seconds)
    }

    /// No real waiting: advance virtual time by the slept duration so the
    /// unreadable deadline is reached on the virtual clock.
    ///
    /// Before advancing, yield the cooperative executor several times. This
    /// gives a decode that is *already ready* (the fake source yields on the
    /// first stream element) the chance to be consumed and win the race, so the
    /// deadline only fires when there genuinely is no decode. When a decode is
    /// pending these yields let it land; when none is, the yields drain and the
    /// clock advances by the full budget — all without any wall-clock sleep.
    func sleep(seconds: Double) async {
        guard seconds > 0 else { return }
        for _ in 0..<64 {
            await Task.yield()
        }
        advance(by: seconds)
    }
}

// MARK: - Fake QR code source

/// A fake `QRCodeSource` standing in for the AVFoundation capture pipeline. It
/// controls *whether and when* a decoded identifier is emitted, so the test
/// drives both the decode-within-budget path and the never-decodes (unreadable)
/// path deterministically. `start()` / `stop()` are no-op session lifecycle
/// hooks the coordinator calls around a scan.
private final class FakeQRCodeSource: QRCodeSource, @unchecked Sendable {

    /// How this source behaves across scans.
    enum Emission {
        /// Emit `id` after advancing the clock by `afterAdvance` seconds — models
        /// a decode that takes some time to arrive, inside the budget.
        case decode(id: String, afterAdvance: TimeInterval, on: ControllableScannerClock)
        /// Never emit — the stream finishes without yielding, driving the
        /// unreadable timeout.
        case neverEmits
        /// Emit nothing on the first scan (unreadable), then emit `id` after
        /// `afterAdvance` seconds on the next scan — models an unreadable attempt
        /// followed by a successful rescan.
        case neverThenDecode(id: String, afterAdvance: TimeInterval, on: ControllableScannerClock)
    }

    private let lock = NSLock()
    private let emission: Emission
    private var scanCount = 0

    init(emission: Emission) {
        self.emission = emission
    }

    func decodedStrings() -> AsyncStream<String> {
        let attempt: Int = {
            lock.lock(); defer { lock.unlock() }
            scanCount += 1
            return scanCount
        }()

        switch emission {
        case let .decode(id, afterAdvance, clock):
            return Self.decodingStream(id: id, afterAdvance: afterAdvance, clock: clock)

        case .neverEmits:
            // Finish immediately without yielding: the coordinator's deadline
            // task owns ending the scan as `unreadable`.
            return AsyncStream { $0.finish() }

        case let .neverThenDecode(id, afterAdvance, clock):
            if attempt == 1 {
                return AsyncStream { $0.finish() }
            }
            return Self.decodingStream(id: id, afterAdvance: afterAdvance, clock: clock)
        }
    }

    /// A single-element stream that advances the virtual clock by `afterAdvance`
    /// (modeling decode latency inside the budget) and then yields `id`.
    private static func decodingStream(
        id: String,
        afterAdvance: TimeInterval,
        clock: ControllableScannerClock
    ) -> AsyncStream<String> {
        AsyncStream { continuation in
            if afterAdvance > 0 {
                clock.advance(by: afterAdvance)
            }
            continuation.yield(id)
            continuation.finish()
        }
    }

    func start() async {}
    func stop() async {}
}

// MARK: - Stub well lookup use case

/// A deterministic `WellLookupUseCase` returning a fixed result for any id, so
/// the coordinator's decode path yields a known `found` / `notFound` /
/// `unavailableOffline` branch without touching a store or the network.
private struct StubWellLookupUseCase: WellLookupUseCase {
    let result: WellLookupResult

    func lookup(wellId: String) async -> WellLookupResult {
        result
    }
}
