//
//  SyncStateDisplayUITests.swift
//
//  Task 20.4 — UI tests: sync-state display (iOS).
//
//  Requirements: 2.7 (and the 2.6 / 2.8 display obligations the same screens
//  carry).
//
//  Req 2.7: "WHEN a record's synchronization state changes to pending,
//  synchronized, or failed, THE App SHALL update the displayed synchronization
//  state for that record within 5 seconds."
//
//  The per-record badge is a thin view over `SyncStateBadgeViewModel`, which
//  subscribes to `SyncRepository.observeSyncState(recordId:)` and republishes
//  each emitted `SyncState` on the main actor; the badge's label/symbol/tint and
//  whether the manual-retry control appears are all derived from that published
//  `state`. These tests drive a controllable fake `SyncRepository` whose
//  observation stream we can advance on command, and assert the view model
//  republishes every transition (pending -> synchronized, pending -> failed) and
//  exposes the retry control exactly for `failed`. A manual retry is verified to
//  reach the repository, which (as in production) yields the resulting
//  `pending` transition back through the same stream. SwiftUI tree inspection is
//  impractical here (opaque `some View`, no ViewInspector), so the assertions
//  sit at the view-model boundary that fully determines the rendered badge, plus
//  the `SyncState` -> presentation mapping the badge renders directly.
//
//  The global offline indicator (Req 2.8 display) is covered against the real
//  `OfflineIndicator` actor the production view model observes.

import XCTest
@testable import SAGDWellTest

@MainActor
final class SyncStateDisplayUITests: XCTestCase {

    // MARK: - Controllable fake sync repository

    /// A `SyncRepository` that exposes a single observable sync-state stream per
    /// record whose values the test advances by calling `emit(_:)`, and that
    /// records manual-retry calls. Only the two members the badge view model
    /// uses (`observeSyncState`, `manualRetry`) carry behavior; the queue
    /// mutators are unused by the badge and are no-ops.
    private final class FakeSyncRepository: SyncRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: AsyncStream<SyncState>.Continuation?
        private let initialState: SyncState
        private(set) var manualRetryCount = 0
        private(set) var lastObservedRecordId: UUID?

        init(initialState: SyncState) {
            self.initialState = initialState
        }

        func observeSyncState(recordId: UUID) -> AsyncStream<SyncState> {
            AsyncStream { continuation in
                self.lock.lock()
                self.lastObservedRecordId = recordId
                self.continuation = continuation
                self.lock.unlock()
                // Emit the current state immediately, mirroring the real
                // repository's GRDB observation (initial value then changes).
                continuation.yield(self.initialState)
            }
        }

        /// Push a new state through the observation stream.
        func emit(_ state: SyncState) {
            lock.lock()
            let c = continuation
            lock.unlock()
            c?.yield(state)
        }

        func manualRetry(recordId: UUID) async {
            lock.lock()
            manualRetryCount += 1
            lock.unlock()
            // Production behavior: a manual retry resets a failed record to
            // pending, which the observation stream reports.
            emit(.pending)
        }

        // Unused by the badge view model.
        func nextPendingBatch(limit: Int) async -> [SyncableRecord] { [] }
        func markSynchronized(recordId: UUID, remoteId: String) async {}
        func recordFailure(recordId: UUID) async {}
    }

    // MARK: - Async wait helper

    /// Polls `condition` on the main actor until it holds or the timeout elapses.
    /// Used to await the view model republishing an observed state transition
    /// (well inside the 5 s display budget; the stream yields effectively
    /// immediately in these fakes).
    private func waitUntil(
        timeout: TimeInterval = 2.0,
        _ condition: @MainActor () -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            // Yield so the view model's observation task can run and deliver
            // the next stream value on the main actor.
            try? await Task.sleep(nanoseconds: 2_000_000) // 2 ms
        }
        XCTAssertTrue(condition(), "Condition not met within \(timeout)s", file: file, line: line)
    }

    private let recordId = UUID()

    // MARK: - Initial / pending display (Req 2.6)

    /// A freshly created, not-yet-acknowledged record shows `pending`, and no
    /// retry control is offered in that state.
    func testBadgeStartsPendingWithoutRetryControl() async {
        let repo = FakeSyncRepository(initialState: .pending)
        let vm = SyncStateBadgeViewModel(recordId: recordId, repository: repo)

        vm.start()
        await waitUntil { vm.state == .pending }

        XCTAssertEqual(vm.state, .pending)
        XCTAssertFalse(vm.showsRetryControl, "Pending records offer no retry control.")
        vm.stop()
    }

    // MARK: - Transition to synchronized (Req 2.7)

    /// When the observed state changes to `synchronized`, the view model
    /// republishes it (the badge then renders the synced styling).
    func testBadgeReflectsSynchronizedTransition() async {
        let repo = FakeSyncRepository(initialState: .pending)
        let vm = SyncStateBadgeViewModel(recordId: recordId, repository: repo)
        vm.start()
        await waitUntil { vm.state == .pending }

        repo.emit(.synchronized)
        await waitUntil { vm.state == .synchronized }

        XCTAssertEqual(vm.state, .synchronized)
        XCTAssertFalse(vm.showsRetryControl, "Synchronized records offer no retry control.")
        vm.stop()
    }

    // MARK: - Transition to failed + manual retry (Req 2.7, 2.5/7.7)

    /// When the observed state changes to `failed`, the view model republishes it
    /// and now exposes the manual-retry control.
    func testBadgeReflectsFailedTransitionAndShowsRetry() async {
        let repo = FakeSyncRepository(initialState: .pending)
        let vm = SyncStateBadgeViewModel(recordId: recordId, repository: repo)
        vm.start()
        await waitUntil { vm.state == .pending }

        repo.emit(.failed)
        await waitUntil { vm.state == .failed }

        XCTAssertEqual(vm.state, .failed)
        XCTAssertTrue(vm.showsRetryControl, "Failed records must offer a manual-retry control.")
        vm.stop()
    }

    /// Tapping retry on a failed record reaches the repository and the resulting
    /// reset-to-pending transition flows back through the observation stream and
    /// is republished, so the badge updates (Req 2.7).
    func testManualRetryResetsFailedBadgeToPending() async {
        let repo = FakeSyncRepository(initialState: .pending)
        let vm = SyncStateBadgeViewModel(recordId: recordId, repository: repo)
        vm.start()
        await waitUntil { vm.state == .pending }

        repo.emit(.failed)
        await waitUntil { vm.state == .failed }
        XCTAssertTrue(vm.showsRetryControl)

        await vm.retry()
        await waitUntil { vm.state == .pending }

        XCTAssertEqual(repo.manualRetryCount, 1, "Retry must reach the repository exactly once.")
        XCTAssertEqual(vm.state, .pending, "A manual retry resets the badge to pending.")
        XCTAssertFalse(vm.showsRetryControl)
        vm.stop()
    }

    /// The view model observes the record it was constructed with.
    func testBadgeObservesTheGivenRecord() async {
        let repo = FakeSyncRepository(initialState: .pending)
        let vm = SyncStateBadgeViewModel(recordId: recordId, repository: repo)
        vm.start()
        await waitUntil { repo.lastObservedRecordId == recordId }
        XCTAssertEqual(repo.lastObservedRecordId, recordId)
        vm.stop()
    }

    // MARK: - Presentation mapping (what the badge renders per state)

    /// Each sync state maps to a distinct label, symbol, tint, and accessibility
    /// description, so the three badges are visually and semantically
    /// distinguishable (the rendering contract the view relies on).
    func testSyncStatePresentationIsDistinctPerState() {
        let states = SyncState.allCases
        XCTAssertEqual(states.count, 3)

        // Labels are the expected user-facing words and all distinct.
        XCTAssertEqual(SyncState.pending.displayLabel, "Pending")
        XCTAssertEqual(SyncState.synchronized.displayLabel, "Synced")
        XCTAssertEqual(SyncState.failed.displayLabel, "Failed")
        XCTAssertEqual(Set(states.map(\.displayLabel)).count, 3,
                       "Each state must render a distinct label.")

        // Symbols are distinct so the states differ at a glance.
        XCTAssertEqual(Set(states.map(\.symbolName)).count, 3,
                       "Each state must render a distinct SF Symbol.")
        for state in states {
            XCTAssertFalse(state.symbolName.isEmpty)
        }

        // Accessibility labels are distinct and non-empty.
        XCTAssertEqual(Set(states.map(\.accessibilityLabel)).count, 3,
                       "Each state must expose a distinct accessibility label.")
        for state in states {
            XCTAssertFalse(state.accessibilityLabel.isEmpty)
        }
    }

    // MARK: - Global offline indicator (Req 2.8 display)

    /// The offline indicator view model seeds its `isOffline` flag from the first
    /// value the real `OfflineIndicator` publishes and then reflects each change,
    /// which is the signal the global banner renders on/off.
    func testOfflineIndicatorReflectsPublishedTransitions() async {
        let indicator = OfflineIndicator(initiallyOffline: false)
        let vm = OfflineIndicatorViewModel(indicator: indicator, initiallyOffline: false)

        vm.start()
        await waitUntil { vm.isOffline == false }

        // Connectivity lost -> banner shown.
        await indicator.setOffline(true)
        await waitUntil { vm.isOffline == true }
        XCTAssertTrue(vm.isOffline, "Loss of connectivity must drive the offline banner on.")

        // Connectivity restored -> banner hidden.
        await indicator.setOffline(false)
        await waitUntil { vm.isOffline == false }
        XCTAssertFalse(vm.isOffline, "Restored connectivity must hide the offline banner.")

        vm.stop()
    }

    /// When observation starts while already offline, the first published value
    /// seeds the flag so the banner is correct immediately.
    func testOfflineIndicatorSeedsFromCurrentValueOnStart() async {
        let indicator = OfflineIndicator(initiallyOffline: true)
        let vm = OfflineIndicatorViewModel(indicator: indicator, initiallyOffline: false)

        vm.start()
        await waitUntil { vm.isOffline == true }

        XCTAssertTrue(vm.isOffline, "The banner must reflect the current offline state as soon as observation starts.")
        vm.stop()
    }
}
