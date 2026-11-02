//
//  FiveRetryBoundaryAndManualRetryResetPropertyTests.swift
//
//  Property 7: Five-retry boundary and manual-retry reset (iOS) — task 15.5.
//
//  Validates: Requirements 2.5, 7.7
//
//  Design "Property 7: Five-retry boundary and manual-retry reset":
//
//      For any locally created record whose every synchronization attempt
//      fails, the record stays `pending` through its first four failures and
//      transitions to `failed` on the fifth; once `failed`, it is excluded from
//      `nextPendingBatch` (so automatic retries stop); and a
//      `SyncRepository.manualRetry` resets it to `pending`, clearing the retry
//      budget, so the engine pushes it again.
//
//  This exercises the real failure path end to end through production code:
//
//    * The store is a fresh, migrated in-memory `AppDatabase` per generated
//      case, with the real `GRDBSyncRepository` over it — so `nextPendingBatch`
//      ordering/exclusion and the `recordFailure` / `manualRetry` state
//      transitions are the production ones, not a reimplementation.
//    * A single `pending` well test is seeded (its parent well first, for the
//      foreign key). `wellTest` records push through the injected
//      `SyncPushClient`, so a stub client that always returns a retryable
//      failure drives the record to `failed` without touching any network.
//    * Time is virtual. A `ControllableClock` is shared as the repository's
//      `now` (so each `recordFailure` stamps `lastAttemptAt` with virtual time)
//      and as the engine's `SyncClock`. Between cycles the clock is advanced
//      well past the exponential backoff window keyed on `lastAttemptAt`, so
//      the record is eligible every cycle and each cycle lands exactly one
//      failure. This makes the five-failure boundary deterministic.
//    * The engine is driven by calling `runSyncCycle()` directly (connectivity
//      is a constant `.satisfied` stub); no real `NWPathMonitor`, background
//      scheduler, or sleeps are involved.
//
//  Assertions per generated case:
//    1. After each of the first four cycles the record is still `pending` and
//       appears in `nextPendingBatch` (automatic retries continue).
//    2. After the fifth cycle the record is `failed` and is absent from
//       `nextPendingBatch` (automatic retries stopped — Req 2.5 exclusion).
//    3. `manualRetry` returns it to `pending` with `retryCount == 0`, and it is
//       back in `nextPendingBatch` (Req 2.5, 7.7).
//    4. A subsequent cycle with an acknowledging client pushes it again and it
//       becomes `synchronized` — confirming the reset genuinely re-enables
//       automatic sync.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 7: Five-retry boundary and manual-retry reset`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class FiveRetryBoundaryAndManualRetryResetPropertyTests: XCTestCase {

    func testFiveRetryBoundaryAndManualRetryReset() {
        property(
            propertyName(7, "Five-retry boundary and manual-retry reset"),
            arguments: pbtArguments()
        ) <- forAll(retryCaseGenerator()) { (testCase: RetryCase) in
            do {
                return try self.boundaryHolds(for: testCase)
            } catch {
                return false <?> "retry-boundary check threw: \(error)"
            }
        }
    }

    // MARK: - Property check

    private func boundaryHolds(for testCase: RetryCase) throws -> Testable {
        // Virtual clock shared by the repository (for lastAttemptAt stamping)
        // and the engine (for backoff eligibility), so advancing it past each
        // backoff window keeps the record eligible every cycle.
        let clock = ControllableClock(start: testCase.startInstant)

        let appDB = try AppDatabase.makeInMemory()
        let repository = GRDBSyncRepository(
            appDatabase: appDB,
            now: { clock.now() }
        )

        // Seed the parent well, then the single pending well test under test.
        try Self.seed(well: testCase.well, test: testCase.test, into: appDB)

        // The push client fails retryably while driving to the failed state,
        // then acknowledges once we switch it, to prove the manual retry truly
        // re-enables automatic sync.
        let pushClient = SwitchablePushClient(
            result: .retryableFailure(reason: "stubbed offline failure")
        )

        let backoff = ExponentialBackoffPolicy()
        let engine = DefaultSyncEngine(
            repository: repository,
            pushClient: pushClient,
            blobUploadClient: UnusedBlobUploadClient(),
            escalationBlobSource: UnusedEscalationBlobSource(),
            backoff: backoff,
            clock: clock,
            connectivity: AlwaysSatisfiedConnectivity(),
            offlineIndicator: NoopOfflineIndicator(),
            backgroundScheduler: NoopBackgroundSyncScheduler()
        )

        let recordId = testCase.test.id

        // --- Phase 1: drive four failing cycles; must stay pending & batched.
        var failures: [Bool] = []
        for _ in 0..<(syncMaxRetryCount - 1) {
            Self.runBlocking { await engine.runSyncCycle() }
            let state = Self.runBlocking { await Self.state(of: recordId, in: appDB) }
            let batched = Self.runBlocking { await Self.isBatched(recordId, repository: repository) }
            failures.append(state == .pending && batched)
            // Advance well past this attempt's backoff window so the next cycle
            // finds the record eligible again.
            clock.advance(by: backoff.maxSeconds + 1)
        }
        let stayedPendingThroughFirstFour = failures.allSatisfy { $0 }

        // --- Phase 2: the fifth failing cycle flips the record to failed and
        // excludes it from the batch (Req 2.5).
        Self.runBlocking { await engine.runSyncCycle() }
        let stateAfterFifth = Self.runBlocking { await Self.state(of: recordId, in: appDB) }
        let batchedAfterFifth = Self.runBlocking {
            await Self.isBatched(recordId, repository: repository)
        }
        let failedAfterFifth = stateAfterFifth == .failed
        let excludedAfterFailed = !batchedAfterFifth

        // A further cycle while failed must not push it or change its state —
        // automatic retries have stopped.
        clock.advance(by: backoff.maxSeconds + 1)
        let pushesBeforeIdleCycle = pushClient.pushCount
        Self.runBlocking { await engine.runSyncCycle() }
        let stateWhileFailed = Self.runBlocking { await Self.state(of: recordId, in: appDB) }
        let noAutoRetryWhileFailed =
            stateWhileFailed == .failed && pushClient.pushCount == pushesBeforeIdleCycle

        // --- Phase 3: manualRetry resets to pending with a fresh retry budget
        // and returns the record to the batch (Req 2.5, 7.7).
        Self.runBlocking { await repository.manualRetry(recordId: recordId) }
        let stateAfterManual = Self.runBlocking { await Self.state(of: recordId, in: appDB) }
        let retryCountAfterManual = Self.runBlocking {
            await Self.retryCount(of: recordId, in: appDB)
        }
        let batchedAfterManual = Self.runBlocking {
            await Self.isBatched(recordId, repository: repository)
        }
        let resetToPending = stateAfterManual == .pending
        let retryBudgetCleared = retryCountAfterManual == 0
        let backInQueue = batchedAfterManual

        // --- Phase 4: with the client now acknowledging, the next cycle pushes
        // the reset record again and it becomes synchronized — the reset truly
        // re-enabled automatic sync.
        pushClient.result = .acknowledged(remoteId: testCase.remoteId)
        clock.advance(by: backoff.maxSeconds + 1)
        Self.runBlocking { await engine.runSyncCycle() }
        let finalState = Self.runBlocking { await Self.state(of: recordId, in: appDB) }
        let pushedAgainAndSynced = finalState == .synchronized

        let holds = stayedPendingThroughFirstFour
            && failedAfterFifth
            && excludedAfterFailed
            && noAutoRetryWhileFailed
            && resetToPending
            && retryBudgetCleared
            && backInQueue
            && pushedAgainAndSynced

        return holds <?> """
            retry-boundary mismatch for record \(recordId):
              stayedPendingThroughFirstFour=\(stayedPendingThroughFirstFour) (perCycle=\(failures))
              failedAfterFifth=\(failedAfterFifth) (state=\(stateAfterFifth))
              excludedAfterFailed=\(excludedAfterFailed) (batched=\(batchedAfterFifth))
              noAutoRetryWhileFailed=\(noAutoRetryWhileFailed) (state=\(stateWhileFailed))
              resetToPending=\(resetToPending) (state=\(stateAfterManual))
              retryBudgetCleared=\(retryBudgetCleared) (retryCount=\(retryCountAfterManual))
              backInQueue=\(backInQueue)
              pushedAgainAndSynced=\(pushedAgainAndSynced) (finalState=\(finalState))
            """
    }

    // MARK: - Store helpers

    private static func seed(well: Well, test: WellTest, into appDB: AppDatabase) throws {
        try appDB.dbWriter.write { db in
            try WellRecord(well).insert(db)
            try WellTestRecord(test).insert(db)
        }
    }

    /// The current sync state of `recordId`, read straight from the well-test
    /// row so the assertion observes exactly what the repository persisted.
    private static func state(of recordId: UUID, in appDB: AppDatabase) async -> SyncState? {
        try? await appDB.dbWriter.read { db in
            try WellTestRecord.fetchOne(db, key: recordId.uuidString)?.domain?.sync.syncState
        } ?? nil
    }

    private static func retryCount(of recordId: UUID, in appDB: AppDatabase) async -> Int? {
        try? await appDB.dbWriter.read { db in
            try WellTestRecord.fetchOne(db, key: recordId.uuidString)?.domain?.sync.retryCount
        } ?? nil
    }

    /// Whether the record currently appears in the pending batch (a generous
    /// limit so a single record is always returned when eligible).
    private static func isBatched(
        _ recordId: UUID,
        repository: GRDBSyncRepository
    ) async -> Bool {
        let batch = await repository.nextPendingBatch(limit: 100)
        return batch.contains { $0.id == recordId }
    }

    /// Runs an async operation to completion from the synchronous SwiftCheck
    /// predicate (the engine and repository are `async`).
    private static func runBlocking<T: Sendable>(
        _ operation: @escaping @Sendable () async -> T
    ) -> T {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            let value = await operation()
            box.value = value
            semaphore.signal()
        }
        semaphore.wait()
        return box.value!
    }

    private final class ResultBox<T>: @unchecked Sendable {
        var value: T?
    }

    // MARK: - Generated case

    /// A well plus one pending well test for it, a start instant for the
    /// virtual clock, and the remote id the acknowledging push echoes back.
    private struct RetryCase {
        let well: Well
        let test: WellTest
        let startInstant: Date
        let remoteId: String
    }

    // MARK: - Generators

    private func retryCaseGenerator() -> Gen<RetryCase> {
        Gen<RetryCase>.compose { c in
            let wellId = "WELL-\(c.generate(using: Gen<Int>.choose((1, 1_000_000))))"
            let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
            let well = Well(
                id: wellId,
                name: "Well \(wellId)",
                type: isProduction ? .production : .injection,
                padId: nil,
                updatedAt: Self.date(c.generate(using: Gen<Int>.choose((0, 2_000_000_000))))
            )

            // A freshly created, never-attempted pending record (retryCount 0,
            // no lastAttemptAt) so it is eligible for the first cycle.
            let test = WellTest(
                id: UUID(),
                wellId: wellId,
                oilRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
                waterRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
                gasRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
                capturedAt: Self.date(c.generate(using: Gen<Int>.choose((0, 2_000_000_000)))),
                sync: SyncMetadata(
                    syncState: .pending,
                    retryCount: 0,
                    createdAt: Self.date(c.generate(using: Gen<Int>.choose((0, 2_000_000_000)))),
                    lastAttemptAt: nil,
                    remoteId: nil
                )
            )

            let startInstant = Self.date(c.generate(using: Gen<Int>.choose((1_600_000_000, 1_800_000_000))))
            let remoteId = "REMOTE-\(c.generate(using: Gen<Int>.choose((1, 1_000_000))))"

            return RetryCase(
                well: well,
                test: test,
                startInstant: startInstant,
                remoteId: remoteId
            )
        }
    }

    private static func date(_ seconds: Int) -> Date {
        Date(timeIntervalSince1970: TimeInterval(seconds))
    }
}

// MARK: - Test doubles

/// A deterministic, advanceable clock. Shared by the repository (for
/// `lastAttemptAt` stamping) and the engine (for backoff eligibility) so the
/// backoff window can be stepped past on demand, with no real waiting.
private final class ControllableClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(start: Date) {
        self.current = start
    }

    func now() -> Date {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    func advance(by seconds: Double) {
        lock.lock()
        defer { lock.unlock() }
        current = current.addingTimeInterval(seconds)
    }

    func sleep(seconds: Double) async {
        // Virtual time: advance rather than wait so cycles run instantly.
        advance(by: seconds)
    }
}

/// A push client whose single result can be switched between calls. Counts
/// pushes so the test can assert no push happens while a record is `failed`.
private final class SwitchablePushClient: SyncPushClient, @unchecked Sendable {
    private let lock = NSLock()
    private var _result: SyncPushResult
    private var _pushCount = 0

    init(result: SyncPushResult) {
        self._result = result
    }

    var result: SyncPushResult {
        get { lock.lock(); defer { lock.unlock() }; return _result }
        set { lock.lock(); _result = newValue; lock.unlock() }
    }

    var pushCount: Int {
        lock.lock(); defer { lock.unlock() }; return _pushCount
    }

    func push(_ record: SyncableRecord) async -> SyncPushResult {
        lock.lock()
        _pushCount += 1
        let r = _result
        lock.unlock()
        return r
    }
}

/// Connectivity that is always satisfied; the test drives `runSyncCycle()`
/// directly rather than through the connectivity stream.
private struct AlwaysSatisfiedConnectivity: ConnectivityProvider {
    var currentStatus: ConnectivityStatus { .satisfied }
    func statusStream() -> AsyncStream<ConnectivityStatus> {
        AsyncStream { continuation in
            continuation.yield(.satisfied)
            continuation.finish()
        }
    }
}

/// An offline indicator that ignores updates — this property is not about the
/// offline banner.
private struct NoopOfflineIndicator: OfflineIndicatorPublishing {
    func setOffline(_ isOffline: Bool) async {}
}

/// Blob clients that must never be reached: the record under test is a
/// `wellTest`, which pushes through `SyncPushClient`, not the blob path.
private struct UnusedBlobUploadClient: BlobUploadClient {
    func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult {
        .nonRetryableFailure(reason: "blob upload should not be invoked in Property 7")
    }
}

private struct UnusedEscalationBlobSource: EscalationBlobSource {
    func escalationWithBlobURL(id: UUID) async -> (escalation: Escalation, blobURL: URL)? {
        nil
    }
}
