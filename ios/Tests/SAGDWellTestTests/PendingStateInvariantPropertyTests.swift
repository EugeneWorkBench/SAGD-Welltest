//
//  PendingStateInvariantPropertyTests.swift
//
//  Property 8: Pending-state invariant (iOS) — task 15.6.
//
//  Validates: Requirements 2.6
//
//  Requirement 2.6 says: WHILE a record has been created locally and is not yet
//  acknowledged and has not reached the failed state, THE App SHALL display
//  that record's synchronization state as pending.
//
//  This property asserts that invariant directly against the real save and sync
//  paths. "Displayed sync state" is the `syncState` column the UI observes via
//  `SyncRepository.observeSyncState`; this test reads the same column straight
//  from the store so the assertion is about the authoritative persisted value.
//
//  The property, for any generated set of captured records:
//
//    1. Immediately after a record is saved through its repository
//       (`GRDBLogRepository.saveWellTest` / `saveWellheadLog`,
//       `GRDBEscalationRepository.saveEscalation`), that record's persisted
//       `syncState` is exactly `pending` — it has just been created locally and
//       has neither been acknowledged nor failed.
//
//    2. After each of `k` failing push cycles (with `1 <= k <= 4`, so the
//       consecutive-failure count stays strictly below the 5-failure limit at
//       which a record flips to `failed`), every record is STILL `pending`. A
//       failing push is "not acknowledged" and, below the retry limit, "not
//       failed" — so Req 2.6 requires the state to remain `pending`.
//
//  How the failing push is driven (and why it is faithful). A real
//  `DefaultSyncEngine` is constructed over the real `GRDBSyncRepository` and a
//  real `GRDBEscalationBlobSource`, with its push client and blob-upload client
//  replaced by stubs that always return `.retryableFailure`. That is exactly
//  the engine's genuine failure branch: `apply(_:to:)` routes a failure to
//  `SyncRepository.recordFailure`, which increments `retryCount` and keeps the
//  record `pending` until the 5th failure. Nothing about the state machine is
//  mocked — only the network boundary (which the design explicitly abstracts
//  behind `SyncPushClient` / `BlobUploadClient`) returns a failure.
//
//  Two seams are driven deterministically so each cycle actually pushes every
//  record rather than skipping it:
//    * Connectivity is a fake reporting `.satisfied`, so `runSyncCycle` runs.
//    * The clock is a fake that jumps far forward before every cycle, so the
//      per-record exponential backoff window has always elapsed and every
//      pending record is eligible and pushed each cycle.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 8: Pending-state invariant`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class PendingStateInvariantPropertyTests: XCTestCase {

    func testPendingStateInvariant() {
        property(
            propertyName(8, "Pending-state invariant"),
            arguments: pbtArguments()
        ) <- forAll(scenarioGenerator()) { (scenario: Scenario) in
            do {
                return try self.pendingStateInvariantHolds(for: scenario)
            } catch {
                return false <?> "harness threw: \(error)"
            }
        }
    }

    // MARK: - The property check for one generated scenario

    /// Saves the generated records, asserts each is immediately `pending`, then
    /// runs `scenario.failingCycles` failing push cycles and asserts every
    /// record remains `pending` after each cycle.
    private func pendingStateInvariantHolds(for scenario: Scenario) throws -> Testable {
        let harness = try Harness()

        // --- Parent well (cached reference data) so captured records satisfy
        // the foreign key; a pure local write, not a sync event. ---
        try harness.seedWell(scenario.well)

        // --- Save each record through its repository and assert it is
        // immediately pending (created locally, not acknowledged, not failed). ---
        let savedTestOK = Self.run {
            await harness.logs.saveWellTest(scenario.wellTest)
        } == .success
        let savedLogOK = Self.run {
            await harness.logs.saveWellheadLog(scenario.wellheadLog)
        } == .success
        let savedEscOK = Self.run {
            await harness.escalations.saveEscalation(
                scenario.escalation,
                photoData: scenario.photoData
            )
        } == .success

        guard savedTestOK, savedLogOK, savedEscOK else {
            return false <?> """
            a record did not save successfully \
            (wellTest=\(savedTestOK), wellheadLog=\(savedLogOK), escalation=\(savedEscOK))
            """
        }

        let recordIds = [
            scenario.wellTest.id,
            scenario.wellheadLog.id,
            scenario.escalation.id,
        ]

        // Invariant at creation time: every freshly saved record is `pending`.
        let pendingAtCreation = try harness.allStates(of: recordIds)
            .allSatisfy { $0 == .pending }
        guard pendingAtCreation else {
            let states = try harness.statesByKind(of: scenario)
            return false <?> "a record was not pending immediately after save: \(states)"
        }

        // --- Run k failing push cycles; k < 5 so no record reaches `failed`. ---
        // Each cycle: advance the clock past any backoff window, then run one
        // cycle whose every push fails (retryable). After each cycle, every
        // record must still be `pending` (not acknowledged, not failed).
        for cycle in 1...scenario.failingCycles {
            harness.advanceClockPastBackoff()
            Self.run { await harness.engine.runSyncCycle() }

            let states = try harness.allStates(of: recordIds)
            let allPending = states.allSatisfy { $0 == .pending }
            guard allPending else {
                let byKind = try harness.statesByKind(of: scenario)
                return false <?> """
                Property 8 violated after failing cycle \(cycle) of \(scenario.failingCycles): \
                expected all pending, got \(byKind)
                """
            }
        }

        return true <?> "pending-state invariant held across \(scenario.failingCycles) failing cycles"
    }

    // MARK: - Harness

    /// One isolated in-memory store, the real repositories over it, and a real
    /// `DefaultSyncEngine` whose only non-production parts are the network
    /// boundary (always fails), a satisfied connectivity fake, and a
    /// jump-forward clock.
    private final class Harness {
        let db: AppDatabase
        let logs: GRDBLogRepository
        let wells: GRDBWellRepository
        let escalations: GRDBEscalationRepository
        let syncRepository: GRDBSyncRepository
        let engine: DefaultSyncEngine
        private let clock: JumpClock

        init() throws {
            let db = try AppDatabase.makeInMemory()
            self.db = db

            let blobStore = try BlobStore(directory: Self.makeTempBlobDirectory())

            self.logs = GRDBLogRepository(
                appDatabase: db,
                storageProbe: AmpleStorageProbe()
            )
            self.wells = GRDBWellRepository(appDatabase: db)
            self.escalations = GRDBEscalationRepository(
                appDatabase: db,
                blobStore: blobStore,
                storageProbe: AmpleStorageProbe()
            )

            let clock = JumpClock()
            self.clock = clock

            // The sync repository stamps `lastAttemptAt` through the SAME clock
            // the engine reads for backoff eligibility, so the two live on one
            // timeline: advancing the clock past the backoff window before each
            // cycle reliably makes every pending record eligible to push again.
            let syncRepository = GRDBSyncRepository(
                appDatabase: db,
                now: { clock.now() }
            )
            self.syncRepository = syncRepository

            // A real engine wired to the real queue; only the network boundary
            // fails and the connectivity/clock seams are fakes.
            self.engine = DefaultSyncEngine(
                repository: syncRepository,
                pushClient: AlwaysFailingPushClient(),
                blobUploadClient: AlwaysFailingBlobUploadClient(),
                escalationBlobSource: GRDBEscalationBlobSource(
                    appDatabase: db,
                    blobStore: blobStore
                ),
                backoff: ExponentialBackoffPolicy(),
                clock: clock,
                connectivity: SatisfiedConnectivityProvider(),
                offlineIndicator: NoopOfflineIndicator(),
                backgroundScheduler: NoopBackgroundSyncScheduler()
            )
        }

        /// Seed the parent well row directly (cached reference data).
        func seedWell(_ well: Well) throws {
            try db.dbWriter.write { db in
                try WellRecord(well).insert(db)
            }
        }

        /// Jump the clock far past the largest possible backoff window so every
        /// pending record is eligible on the next cycle.
        func advanceClockPastBackoff() {
            clock.advance(by: 10_000)
        }

        /// The persisted `syncState` of each id, scanning every syncable table.
        func allStates(of ids: [UUID]) throws -> [SyncState] {
            try ids.map { try state(of: $0) }
        }

        /// A labeled snapshot of each record's state, for readable failures.
        func statesByKind(of scenario: Scenario) throws -> [String: String] {
            [
                "wellTest": try state(of: scenario.wellTest.id).rawValue,
                "wellheadLog": try state(of: scenario.wellheadLog.id).rawValue,
                "escalation": try state(of: scenario.escalation.id).rawValue,
            ]
        }

        /// Reads the `syncState` column for `id` from whichever syncable table
        /// holds it — the same authoritative value the UI observes.
        private func state(of id: UUID) throws -> SyncState {
            let tables = [
                Schema.Table.wellTest,
                Schema.Table.wellheadLog,
                Schema.Table.escalation,
            ]
            let raw: String? = try db.dbWriter.read { db in
                for table in tables {
                    if let value = try String.fetchOne(db, sql: """
                        SELECT \(Schema.SyncColumn.syncState)
                        FROM \(table) WHERE id = ?
                        """, arguments: [id.uuidString]) {
                        return value
                    }
                }
                return nil
            }
            guard let raw, let parsed = SyncState(rawValue: raw) else {
                throw HarnessError.recordMissing(id)
            }
            return parsed
        }

        private static func makeTempBlobDirectory() -> URL {
            FileManager.default.temporaryDirectory
                .appendingPathComponent("sagd-prop8-\(UUID().uuidString)", isDirectory: true)
        }
    }

    private enum HarnessError: Error {
        case recordMissing(UUID)
    }

    // MARK: - Test doubles

    /// A push client that always reports a transient failure, driving the
    /// engine's genuine failure branch (record stays pending, retryCount++).
    private struct AlwaysFailingPushClient: SyncPushClient {
        func push(_ record: SyncableRecord) async -> SyncPushResult {
            .retryableFailure(reason: "forced failure for pending-state invariant test")
        }
    }

    /// A blob-upload client that always reports a transient failure, so
    /// escalation pushes fail the same way.
    private struct AlwaysFailingBlobUploadClient: BlobUploadClient {
        func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult {
            .retryableFailure(reason: "forced failure for pending-state invariant test")
        }
    }

    /// Connectivity that is always satisfied so `runSyncCycle` proceeds.
    private struct SatisfiedConnectivityProvider: ConnectivityProvider {
        var currentStatus: ConnectivityStatus { .satisfied }
        func statusStream() -> AsyncStream<ConnectivityStatus> {
            AsyncStream { continuation in
                continuation.yield(.satisfied)
                continuation.finish()
            }
        }
    }

    /// An offline indicator that records nothing; this property does not assert
    /// on the offline banner.
    private struct NoopOfflineIndicator: OfflineIndicatorPublishing {
        func setOffline(_ isOffline: Bool) async {}
    }

    /// A controllable clock whose `now()` only moves when the test advances it,
    /// so each cycle can be placed well past every record's backoff window.
    private final class JumpClock: SyncClock, @unchecked Sendable {
        private let lock = NSLock()
        private var current: Date

        init(start: Date = Date(timeIntervalSince1970: 1_700_000_000)) {
            self.current = start
        }

        func now() -> Date {
            lock.lock(); defer { lock.unlock() }
            return current
        }

        func advance(by seconds: TimeInterval) {
            lock.lock(); defer { lock.unlock() }
            current = current.addingTimeInterval(seconds)
        }

        func sleep(seconds: Double) async {
            // No real waiting in tests; backoff eligibility is driven by `now()`.
        }
    }

    /// Always reports ample space so the pre-write free-space check never
    /// short-circuits a save into `storageFull`.
    private struct AmpleStorageProbe: StorageProbe {
        func availableCapacityBytes() -> Int64? { Int64.max }
    }

    // MARK: - Async bridge for SwiftCheck

    /// Bridges an `async` call to SwiftCheck's synchronous predicate.
    private static func run<T: Sendable>(
        _ operation: @escaping @Sendable () async -> T
    ) -> T {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            box.value = await operation()
            semaphore.signal()
        }
        semaphore.wait()
        return box.value!
    }

    private final class ResultBox<T>: @unchecked Sendable {
        var value: T?
    }

    // MARK: - Generated scenario

    /// One generated scenario: a parent well, one record of each syncable kind
    /// for it, the photo bytes for the escalation, and the number of failing
    /// push cycles to run (1...4, strictly below the 5-failure limit).
    private struct Scenario {
        var well: Well
        var wellTest: WellTest
        var wellheadLog: WellheadLog
        var escalation: Escalation
        var photoData: Data
        var failingCycles: Int
    }

    // MARK: - Generators

    private func scenarioGenerator() -> Gen<Scenario> {
        let ratioGen = Gen<Double>.choose((0.0, 1.0))
        let capturedAtGen = Gen<Int>.choose((1_600_000_000, 1_800_000_000))
            .map { Date(timeIntervalSince1970: Double($0)) }

        return Gen<Scenario>.compose { c in
            let wellId: String = c.generate(using:
                Gen<Int>.choose((1, 1_000_000)).map { "WELL-\($0)" }
            )
            let capturedAt: Date = c.generate(using: capturedAtGen)
            let wellType: WellType = c.generate(
                using: Gen<WellType>.fromElements(of: WellType.allCases)
            )

            let well = Well(
                id: wellId,
                name: "Well \(wellId)",
                type: wellType,
                padId: nil,
                updatedAt: capturedAt
            )

            let wellTest = WellTest(
                id: UUID(),
                wellId: wellId,
                oilRatio: c.generate(using: ratioGen),
                waterRatio: c.generate(using: ratioGen),
                gasRatio: c.generate(using: ratioGen),
                capturedAt: capturedAt
            )

            let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
            let wellheadLog = Self.makeLog(
                id: UUID(), wellId: wellId, capturedAt: capturedAt,
                isProduction: isProduction, c: c
            )

            let priority: EscalationPriority = c.generate(
                using: Gen<EscalationPriority>.fromElements(of: EscalationPriority.allCases)
            )
            let escalation = Escalation(
                id: UUID(),
                wellId: wellId,
                priority: priority,
                photoRef: "placeholder",
                latitude: c.generate(using: Gen<Double>.choose((-90.0, 90.0))),
                longitude: c.generate(using: Gen<Double>.choose((-180.0, 180.0))),
                locationUnavailable: false,
                capturedAt: capturedAt
            )

            let photoLength: Int = c.generate(using: Gen<Int>.choose((1, 256)))
            let photoData = Data((0..<photoLength).map { _ in
                UInt8(c.generate(using: Gen<Int>.choose((0, 255))))
            })

            // 1...4 failing cycles: strictly below the 5-failure limit, so no
            // record ever transitions to `failed`.
            let failingCycles: Int = c.generate(
                using: Gen<Int>.choose((1, syncMaxRetryCount - 1))
            )

            return Scenario(
                well: well,
                wellTest: wellTest,
                wellheadLog: wellheadLog,
                escalation: escalation,
                photoData: photoData,
                failingCycles: failingCycles
            )
        }
    }

    /// Builds a valid, in-range wellhead log of the requested kind.
    private static func makeLog(
        id: UUID,
        wellId: String,
        capturedAt: Date,
        isProduction: Bool,
        c: GenComposer
    ) -> WellheadLog {
        if isProduction {
            return WellheadLog(
                id: id,
                wellId: wellId,
                logType: .production,
                capturedAt: capturedAt,
                emulsionFlowRate: c.generate(using: Gen<Double>.choose((0.0, 999_999.99))),
                casingGasPressure: c.generate(using: Gen<Double>.choose((0.0, 999_999.99))),
                espMotorTemperature: c.generate(using: Gen<Double>.choose((0.0, 999_999.99))),
                waterCutPercent: c.generate(using: Gen<Double>.choose((0.0, 100.0)))
            )
        } else {
            return WellheadLog(
                id: id,
                wellId: wellId,
                logType: .injection,
                capturedAt: capturedAt,
                steamInjectionPressure: c.generate(using: Gen<Double>.choose((0.0, 25_000.0))),
                steamTemperature: c.generate(using: Gen<Double>.choose((0.0, 400.0))),
                steamFlowRate: c.generate(using: Gen<Double>.choose((0.0, 2_000.0)))
            )
        }
    }
}
