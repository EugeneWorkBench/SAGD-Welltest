//
//  NoDataLossOnFailedSyncPropertyTests.swift
//
//  Property 6: No data loss on failed sync (iOS) — task 15.4.
//
//  Validates: Requirements 2.4
//
//  Requirement 2.4 says that if a synchronization attempt for a record fails,
//  the Sync_Service must retain the record in the Local_Store without data
//  loss and retry it on the next connectivity event.
//
//  Design "Property 6: No data loss on failed sync":
//
//      For any pending record and any sync attempt that fails, the record
//      remains in the Local_Store with its captured payload unchanged and
//      remains eligible for a later retry.
//
//  This property drives the real engine end to end against the real store:
//
//    * `DefaultSyncEngine` is the actual production engine, wired to the real
//      `GRDBSyncRepository` over an in-memory `AppDatabase` (foreign keys
//      enforced). Nothing about the failure path is stubbed on the engine or
//      repository side.
//    * The push client is a stub whose every attempt is a transient
//      (`.retryableFailure`) failure, so the engine records a failed attempt
//      for each record without ever acknowledging.
//    * Connectivity is fixed to `.satisfied` (`FixedConnectivityProvider`), so
//      the cycle actually runs — `runSyncCycle` no-ops while offline.
//    * A controllable clock (`FixedSyncClock`) supplies a deterministic "now";
//      seeded records have `lastAttemptAt == nil` / `retryCount == 0`, so every
//      record is immediately backoff-eligible and pushed in the one cycle.
//
//  After one failing cycle the property asserts, for every seeded record:
//    1. the row still exists in the store (no record dropped — Req 2.4);
//    2. its captured payload (id, well association, ratios, timestamp) is
//       byte-for-byte unchanged (no data mutation — Req 2.4);
//    3. its `retryCount` incremented to exactly 1 (the failure was recorded);
//    4. its sync state is still `pending` — below the 5-failure boundary — and
//       it is still returned by `nextPendingBatch`, so it remains eligible for
//       a later retry (Req 2.4).
//
//  Only well-test records are seeded: they route through `SyncPushClient`
//  (the push path Req 2.4 concerns). Escalations take the separate multipart
//  blob-upload path, covered by other properties; the escalation seams here
//  are inert and fail loudly if ever reached.
//
//  One failed cycle (not five) is used deliberately: Property 6 is about the
//  no-loss / still-eligible guarantee of a *failed* attempt, keeping every
//  record strictly below the `failed` boundary that Property 7 covers.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 6: No data loss on failed sync`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class NoDataLossOnFailedSyncPropertyTests: XCTestCase {

    func testNoDataLossOnFailedSync() {
        property(
            propertyName(6, "No data loss on failed sync"),
            arguments: pbtArguments()
        ) <- forAll(seededCycleGenerator()) { (seed: SeededCycle) in
            do {
                return try self.failedSyncLosesNothing(seed)
            } catch {
                return false <?> "cycle threw: \(error)"
            }
        }
    }

    // MARK: - The property

    /// Seeds the generated pending well tests, runs a single sync cycle in
    /// which every push fails retryably, then asserts each record survived
    /// intact, recorded exactly one failed attempt, and is still a pending,
    /// retry-eligible record.
    private func failedSyncLosesNothing(_ seed: SeededCycle) throws -> Testable {
        let db = try AppDatabase.makeInMemory()
        try Self.seed(seed, into: db)

        let repository = GRDBSyncRepository(appDatabase: db, now: { Self.fixedNow })
        let pushClient = AlwaysRetryableFailurePushClient()

        let engine = DefaultSyncEngine(
            repository: repository,
            pushClient: pushClient,
            blobUploadClient: UnusedBlobUploadClient(),
            escalationBlobSource: UnusedEscalationBlobSource(),
            backoff: ExponentialBackoffPolicy(),
            clock: FixedSyncClock(now: Self.fixedNow),
            connectivity: FixedConnectivityProvider(status: .satisfied),
            offlineIndicator: OfflineIndicator(),
            backgroundScheduler: NoopBackgroundSyncScheduler(),
            // A batch limit comfortably above the seeded count so every record
            // is pushed in the single cycle.
            batchLimit: max(seed.tests.count, 1)
        )

        Self.runBlocking { await engine.runSyncCycle() }

        // Every seeded record must have been attempted (none silently skipped).
        guard pushClient.pushCount == seed.tests.count else {
            return false
                <?> "expected \(seed.tests.count) push attempts, saw \(pushClient.pushCount)"
        }

        for saved in seed.tests {
            let reloaded: WellTest? = try db.dbWriter.read { db in
                try WellTestRecord.fetchOne(db, key: saved.id.uuidString)?.domain
            }
            // 1. The record still exists — nothing was dropped.
            guard let reloaded else {
                return false <?> "well test \(saved.id) was lost from the store after a failed sync"
            }

            // 2. Captured payload unchanged (no data loss / mutation).
            let payloadIntact = reloaded.id == saved.id
                && reloaded.wellId == saved.wellId
                && reloaded.oilRatio == saved.oilRatio
                && reloaded.waterRatio == saved.waterRatio
                && reloaded.gasRatio == saved.gasRatio
                && reloaded.capturedAt == saved.capturedAt
            guard payloadIntact else {
                return false
                    <?> "captured fields changed after failed sync: saved \(saved) vs reloaded \(reloaded)"
            }

            // 3. The failed attempt was recorded: retryCount incremented to 1.
            guard reloaded.sync.retryCount == 1 else {
                return false
                    <?> "retryCount expected 1 after one failed attempt, got \(reloaded.sync.retryCount) for \(saved.id)"
            }

            // 4. Below the 5-failure boundary -> still pending.
            guard reloaded.sync.syncState == .pending else {
                return false
                    <?> "state expected pending after one failed attempt, got \(reloaded.sync.syncState) for \(saved.id)"
            }
        }

        // 4 (cont.). Each record is still offered by the next pending batch, so
        // it remains eligible for a later retry — it was not excluded.
        let stillPending = Self.runBlocking {
            await repository.nextPendingBatch(limit: seed.tests.count + 1)
        }
        let pendingIds = Set(stillPending.map { $0.id })
        let expectedIds = Set(seed.tests.map { $0.id })
        return expectedIds.isSubset(of: pendingIds)
            <?> "records not eligible for retry after a failed sync: pending=\(pendingIds), expected=\(expectedIds)"
    }

    // MARK: - Deterministic time

    /// A fixed instant used by both the clock and the repository so the
    /// `lastAttemptAt` stamp recorded on failure is deterministic.
    private static let fixedNow = Date(timeIntervalSince1970: 1_700_000_000)

    // MARK: - Seeding

    private static func seed(_ seed: SeededCycle, into db: AppDatabase) throws {
        try db.dbWriter.write { db in
            // Parent wells first (foreign keys are enforced).
            for well in seed.wells {
                try WellRecord(well).insert(db)
            }
            for test in seed.tests {
                try WellTestRecord(test).insert(db)
            }
        }
    }

    // MARK: - Async bridge

    /// Drives an async operation to completion from the synchronous SwiftCheck
    /// predicate.
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

    // MARK: - Test doubles

    /// A push client whose every attempt is a transient (retryable) failure, so
    /// the engine records a failure for each record without acknowledging.
    private final class AlwaysRetryableFailurePushClient: SyncPushClient, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var pushCount: Int {
            lock.lock(); defer { lock.unlock() }
            return count
        }
        func push(_ record: SyncableRecord) async -> SyncPushResult {
            lock.lock(); count += 1; lock.unlock()
            return .retryableFailure(reason: "simulated transient failure")
        }
    }

    /// Fixed-status connectivity, always emitting a single `.satisfied` value.
    private struct FixedConnectivityProvider: ConnectivityProvider {
        let status: ConnectivityStatus
        var currentStatus: ConnectivityStatus { status }
        func statusStream() -> AsyncStream<ConnectivityStatus> {
            AsyncStream { continuation in
                continuation.yield(status)
                continuation.finish()
            }
        }
    }

    /// A controllable clock pinned at a fixed instant; `sleep` returns at once.
    private struct FixedSyncClock: SyncClock {
        let instant: Date
        init(now: Date) { self.instant = now }
        func now() -> Date { instant }
        func sleep(seconds: Double) async {}
    }

    /// Escalation seams that must never be reached: this property seeds only
    /// records routed through `SyncPushClient`. Reaching either would mean the
    /// engine misrouted a non-escalation record.
    private struct UnusedBlobUploadClient: BlobUploadClient {
        func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult {
            XCTFail("blob upload client reached for a non-escalation property")
            return .retryableFailure(reason: "unexpected blob upload")
        }
    }

    private struct UnusedEscalationBlobSource: EscalationBlobSource {
        func escalationWithBlobURL(id: UUID) async -> (escalation: Escalation, blobURL: URL)? {
            XCTFail("escalation blob source reached for a non-escalation property")
            return nil
        }
    }

    // MARK: - Generated case

    /// A seeded sync cycle: the parent wells plus the pending well tests.
    private struct SeededCycle {
        let wells: [Well]
        let tests: [WellTest]
    }

    // MARK: - Generators

    /// Draws a batch of 1...6 pending well tests (so the "set of records"
    /// clause is exercised with more than one), each associated with its own
    /// freshly seeded well. Every record is seeded `pending` with
    /// `retryCount == 0` and no prior attempt, so all are backoff-eligible in
    /// the single cycle.
    private func seededCycleGenerator() -> Gen<SeededCycle> {
        Gen<SeededCycle>.compose { c in
            let count: Int = c.generate(using: Gen<Int>.choose((1, 6)))
            var wells: [Well] = []
            var tests: [WellTest] = []

            for i in 0..<count {
                // Unique well id per record (the base draw could otherwise
                // collide, which would fail the second FK insert).
                let well = Self.makeWell(c, suffix: i)
                wells.append(well)
                tests.append(Self.makeWellTest(wellId: well.id, c: c))
            }

            return SeededCycle(wells: wells, tests: tests)
        }
    }

    private static let wellIdGen: Gen<String> =
        Gen<Int>.choose((1, 1_000_000)).map { "WELL-\($0)" }

    /// Capture / creation timestamps at 1-second precision.
    private static let timestampGen: Gen<Date> =
        Gen<Int>.choose((1_600_000_000, 1_800_000_000))
            .map { Date(timeIntervalSince1970: TimeInterval($0)) }

    private static func makeWell(_ c: GenComposer, suffix: Int) -> Well {
        let base: String = c.generate(using: wellIdGen)
        let id = "\(base)-\(suffix)"
        let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
        return Well(
            id: id,
            name: "Well \(id)",
            type: isProduction ? .production : .injection,
            padId: nil,
            updatedAt: c.generate(using: timestampGen)
        )
    }

    /// A pending well test with no prior attempt (backoff-eligible) and known
    /// captured fields the property asserts survive the failed cycle unchanged.
    private static func makeWellTest(wellId: String, c: GenComposer) -> WellTest {
        let capturedAt: Date = c.generate(using: timestampGen)
        let sync = SyncMetadata(
            syncState: .pending,
            retryCount: 0,
            createdAt: capturedAt,
            lastAttemptAt: nil,
            remoteId: nil
        )
        return WellTest(
            id: UUID(),
            wellId: wellId,
            oilRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
            waterRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
            gasRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
            capturedAt: capturedAt,
            sync: sync
        )
    }
}
