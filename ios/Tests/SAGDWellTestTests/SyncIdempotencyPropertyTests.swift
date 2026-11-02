//
//  SyncIdempotencyPropertyTests.swift
//
//  Property 9: Sync idempotency (iOS) — task 15.7.
//
//  Validates: Requirements 2.3, 2.4
//
//  Design "Property 9: Sync idempotency":
//
//      For any record, pushing it to the Cloud_Backend and then pushing the
//      same record (same client `id`) again results in a single stored record
//      on the backend with the same `remoteId`, and the record's final local
//      state is `synchronized` (duplicate pushes cause no duplicate and no
//      state regression).
//
//  The property drives the real engine end to end against the real store,
//  running a *second* cycle after the first so the "push the same record
//  twice" clause is exercised exactly as production would re-attempt a queue:
//
//    * `DefaultSyncEngine` is the actual production engine, wired to the real
//      `GRDBSyncRepository` over an in-memory `AppDatabase` (foreign keys
//      enforced). Nothing on the engine or repository side is stubbed.
//    * The push client is an *idempotent backend stub* (`IdempotentPushClient`)
//      that models the Cloud_Backend's by-`id` dedup contract: it keeps a
//      per-`id` ledger of acknowledged client ids, mints one stable `remoteId`
//      the first time it sees an id, and returns that *same* `remoteId` on
//      every subsequent push of the id — never creating a second backend entry.
//      It also records the full push log so the test can assert what the engine
//      actually pushed on the second cycle.
//    * Connectivity is fixed `.satisfied` so cycles actually run (`runSyncCycle`
//      no-ops while offline), and a `FixedSyncClock` supplies a deterministic
//      "now" with seeded records at `lastAttemptAt == nil` / `retryCount == 0`,
//      so every record is backoff-eligible in the first cycle.
//
//  After two cycles the property asserts, per seeded record:
//
//    1. The local record ends `synchronized` exactly once, carrying the stable
//       `remoteId` the backend minted (no state regression — Req 2.3, 2.4).
//    2. The backend holds exactly one entry for that client `id` (no duplicate
//       row created by the re-push).
//    3. The second cycle did *not* re-push the record: once a record is
//       `synchronized`, `nextPendingBatch` excludes it, so the engine addresses
//       each id on the backend exactly once across both cycles.
//
//  Only well-test, wellhead-log, and subcool-reading records are seeded: those
//  are the kinds the engine routes through `SyncPushClient`. Escalations take
//  the separate blob-upload path (a different property); their seams here are
//  inert and never exercised.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 9: Sync idempotency`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class SyncIdempotencyPropertyTests: XCTestCase {

    func testSyncIdempotency() {
        property(
            propertyName(9, "Sync idempotency"),
            arguments: pbtArguments()
        ) <- forAll(seededCycleGenerator()) { (seed: SeededCycle) in
            do {
                return try self.duplicatePushIsIdempotent(seed)
            } catch {
                return false <?> "cycle threw: \(error)"
            }
        }
    }

    // MARK: - The property

    /// Seeds the generated pending records, runs the sync cycle twice against an
    /// idempotent backend stub, then asserts each record ended `synchronized`
    /// with a single stable `remoteId`, the backend holds exactly one entry per
    /// client `id`, and the second cycle re-pushed nothing.
    private func duplicatePushIsIdempotent(_ seed: SeededCycle) throws -> Testable {
        let db = try AppDatabase.makeInMemory()
        try Self.seed(seed, into: db)

        let repository = GRDBSyncRepository(appDatabase: db, now: { Self.fixedNow })

        // A backend stub that dedups by client `id`: one stable remoteId per id,
        // and a push log so we can see what the second cycle actually pushed.
        let backend = IdempotentBackend()
        let pushClient = IdempotentPushClient(backend: backend)

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
            // is pushed in a single cycle.
            batchLimit: max(seed.records.count, 1)
        )

        // Push the same records twice: a full re-run of the cycle. The first
        // cycle acknowledges and synchronizes; the second must be a no-op with
        // respect to the backend — the records are already `synchronized` and
        // so excluded from `nextPendingBatch`.
        Self.runBlocking { await engine.runSyncCycle() }
        Self.runBlocking { await engine.runSyncCycle() }

        let pushLog = Self.runReturning { await backend.pushLog() }

        for record in seed.records {
            let expectedRemoteId = Self.runReturning { await backend.remoteId(for: record.id) }

            // 1. Final local state is `synchronized` with the stable remoteId.
            let observed = try Self.loadSyncMetadata(id: record.id, kind: record.kind, from: db)
            guard let observed else {
                return false <?> "record \(record.id) (\(record.kind)) vanished after the cycles"
            }
            guard observed.syncState == .synchronized else {
                return false <?> "record \(record.id) (\(record.kind)) is \(observed.syncState), expected synchronized"
            }
            guard let expectedRemoteId else {
                return false <?> "backend never acknowledged record \(record.id); nothing was pushed"
            }
            guard observed.remoteId == expectedRemoteId else {
                return false <?> "record \(record.id) stored remoteId \(observed.remoteId ?? "nil"), expected stable \(expectedRemoteId)"
            }

            // 2. The backend holds exactly one entry for this client id (the
            //    re-push created no duplicate).
            let backendEntries = Self.runReturning { await backend.entryCount(for: record.id) }
            guard backendEntries == 1 else {
                return false <?> "backend holds \(backendEntries) entries for \(record.id), expected exactly 1 (duplicate created)"
            }

            // 3. The engine pushed this id exactly once across both cycles: once
            //    synchronized, `nextPendingBatch` excludes it, so the second
            //    cycle must not re-push an already-synchronized record.
            let pushes = pushLog.filter { $0 == record.id }.count
            guard pushes == 1 else {
                return false <?> "record \(record.id) was pushed \(pushes) times across two cycles, expected exactly 1 (already-synchronized record re-pushed)"
            }
        }

        return true.property
    }

    // MARK: - Deterministic time

    /// A fixed instant used by both the clock and the repository so timestamps
    /// are deterministic across iterations.
    private static let fixedNow = Date(timeIntervalSince1970: 1_700_000_000)

    // MARK: - Seeding

    private static func seed(_ seed: SeededCycle, into db: AppDatabase) throws {
        try db.dbWriter.write { db in
            // Parent wells first (foreign keys are enforced).
            for well in seed.wells {
                try WellRecord(well).insert(db)
            }
            for record in seed.records {
                switch record.payload {
                case .wellTest(let test):
                    try WellTestRecord(test).insert(db)
                case .wellheadLog(let log):
                    try WellheadLogRecord(log).insert(db)
                case .subcoolReading(let reading):
                    try SubcoolReadingRecord(reading).insert(db)
                }
            }
        }
    }

    // MARK: - Read-back

    /// Loads the persisted `SyncMetadata` for a record by scanning the table
    /// its `kind` maps to. Returns `nil` if the row is gone.
    private static func loadSyncMetadata(
        id: UUID,
        kind: SyncableRecordKind,
        from db: AppDatabase
    ) throws -> SyncMetadata? {
        try db.dbWriter.read { db -> SyncMetadata? in
            switch kind {
            case .wellTest:
                return try WellTestRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            case .wellheadLog:
                return try WellheadLogRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            case .subcoolReading:
                return try SubcoolReadingRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            case .escalation:
                return try EscalationRecord.fetchOne(db, key: id.uuidString)?.domain?.sync
            }
        }
    }

    // MARK: - Async bridge

    /// Drives an async operation to completion from the synchronous SwiftCheck
    /// predicate.
    private static func runBlocking(_ operation: @escaping @Sendable () async -> Void) {
        let semaphore = DispatchSemaphore(value: 0)
        Task {
            await operation()
            semaphore.signal()
        }
        semaphore.wait()
    }

    /// Drives an async operation that returns a value to completion from the
    /// synchronous SwiftCheck predicate.
    private static func runReturning<T: Sendable>(_ operation: @escaping @Sendable () async -> T) -> T {
        let semaphore = DispatchSemaphore(value: 0)
        let box = ResultBox<T>()
        Task {
            let value = await operation()
            await box.set(value)
            semaphore.signal()
        }
        semaphore.wait()
        return box.takeUnsafely()
    }

    /// A tiny holder so `runReturning` can carry a value back across the
    /// semaphore. The value is written before `signal()` and read after
    /// `wait()`, so the access is ordered.
    private final class ResultBox<T>: @unchecked Sendable {
        private var value: T?
        func set(_ newValue: T) async { value = newValue }
        func takeUnsafely() -> T { value! }
    }

    // MARK: - Test doubles

    /// The Cloud_Backend's by-`id` dedup contract, modeled as an actor so the
    /// ledger is safe across concurrent pushes. Records every push (the log),
    /// mints one stable `remoteId` the first time it sees a client `id`, and
    /// returns that same id forever after — never a second backend entry.
    private actor IdempotentBackend {
        /// The stable remote id minted per client id. One entry == one stored
        /// backend record; its presence is the "no duplicate" witness.
        private var remoteIds: [UUID: String] = [:]
        /// Every push the engine issued, in order, for log-based assertions.
        private var pushes: [UUID] = []

        /// Acknowledge a push of `id`, minting a stable remote id on first sight
        /// and echoing the same one on every re-push. Idempotent by `id`.
        func acknowledge(_ id: UUID) -> String {
            pushes.append(id)
            if let existing = remoteIds[id] {
                return existing
            }
            let minted = "remote-\(id.uuidString)"
            remoteIds[id] = minted
            return minted
        }

        func remoteId(for id: UUID) -> String? { remoteIds[id] }
        /// How many distinct backend entries exist for `id`: 0 (never pushed) or
        /// 1 (idempotent). Never more — that would be a duplicate.
        func entryCount(for id: UUID) -> Int { remoteIds[id] == nil ? 0 : 1 }
        func pushLog() -> [UUID] { pushes }
    }

    /// Bridges the engine's `SyncPushClient` seam onto the idempotent backend:
    /// every push is acknowledged with the backend's stable remote id for the
    /// record's client `id`.
    private struct IdempotentPushClient: SyncPushClient {
        let backend: IdempotentBackend

        func push(_ record: SyncableRecord) async -> SyncPushResult {
            let remoteId = await backend.acknowledge(record.id)
            return .acknowledged(remoteId: remoteId)
        }
    }

    /// Fixed-status connectivity, always emitting a single `.satisfied` value —
    /// the test-local equivalent of `StaticConnectivityProvider(satisfied)` for
    /// the sync `ConnectivityProvider` seam.
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

    /// A seeded sync cycle: the parent wells plus the pending records to push.
    private struct SeededCycle {
        let wells: [Well]
        let records: [SeededRecord]
    }

    /// One pending record, with its `kind` kept alongside for table-scoped
    /// read-back.
    private struct SeededRecord {
        let id: UUID
        let kind: SyncableRecordKind
        let payload: Payload

        enum Payload {
            case wellTest(WellTest)
            case wellheadLog(WellheadLog)
            case subcoolReading(SubcoolReading)
        }
    }

    // MARK: - Generators

    /// Draws a batch of 1...6 pending records (so the "set of records" clause is
    /// exercised with more than one), each a well test, wellhead log, or subcool
    /// reading associated with its own freshly seeded well. Every record is
    /// seeded `pending` with `retryCount == 0` and no prior attempt, so all are
    /// backoff-eligible in the first cycle.
    private func seededCycleGenerator() -> Gen<SeededCycle> {
        Gen<SeededCycle>.compose { c in
            let count: Int = c.generate(using: Gen<Int>.choose((1, 6)))
            var wells: [Well] = []
            var records: [SeededRecord] = []

            for _ in 0..<count {
                let well = Self.makeWell(c)
                wells.append(well)
                records.append(Self.makeSeededRecord(wellId: well.id, c: c))
            }

            return SeededCycle(wells: wells, records: records)
        }
    }

    private static let wellIdGen: Gen<String> =
        Gen<Int>.choose((1, 1_000_000)).map { "WELL-\($0)" }

    /// Capture / creation timestamps at 1-second precision.
    private static let timestampGen: Gen<Date> =
        Gen<Int>.choose((1_600_000_000, 1_800_000_000))
            .map { Date(timeIntervalSince1970: TimeInterval($0)) }

    private static func makeWell(_ c: GenComposer) -> Well {
        let id: String = c.generate(using: wellIdGen)
        let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
        return Well(
            id: id,
            name: "Well \(id)",
            type: isProduction ? .production : .injection,
            padId: nil,
            updatedAt: c.generate(using: timestampGen)
        )
    }

    /// Builds a pending seeded record of one of the three push-client kinds.
    private static func makeSeededRecord(wellId: String, c: GenComposer) -> SeededRecord {
        let capturedAt: Date = c.generate(using: timestampGen)
        // A pending creation metadata with no prior attempt → backoff-eligible.
        let sync = SyncMetadata(
            syncState: .pending,
            retryCount: 0,
            createdAt: capturedAt,
            lastAttemptAt: nil,
            remoteId: nil
        )
        let kind: Int = c.generate(using: Gen<Int>.choose((0, 2)))

        switch kind {
        case 0:
            let id = UUID()
            let test = WellTest(
                id: id,
                wellId: wellId,
                oilRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
                waterRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
                gasRatio: c.generate(using: Gen<Double>.choose((0.0, 1.0))),
                capturedAt: capturedAt,
                sync: sync
            )
            return SeededRecord(id: id, kind: .wellTest, payload: .wellTest(test))

        case 1:
            let id = UUID()
            let isProduction: Bool = c.generate(using: Gen<Bool>.fromElements(of: [true, false]))
            let log: WellheadLog
            if isProduction {
                log = WellheadLog(
                    id: id,
                    wellId: wellId,
                    logType: .production,
                    capturedAt: capturedAt,
                    emulsionFlowRate: c.generate(using: Gen<Double>.choose((0.0, 999_999.99))),
                    casingGasPressure: c.generate(using: Gen<Double>.choose((0.0, 999_999.99))),
                    espMotorTemperature: c.generate(using: Gen<Double>.choose((0.0, 999_999.99))),
                    waterCutPercent: c.generate(using: Gen<Double>.choose((0.0, 100.0))),
                    sync: sync
                )
            } else {
                log = WellheadLog(
                    id: id,
                    wellId: wellId,
                    logType: .injection,
                    capturedAt: capturedAt,
                    steamInjectionPressure: c.generate(using: Gen<Double>.choose((0.0, 25_000.0))),
                    steamTemperature: c.generate(using: Gen<Double>.choose((0.0, 400.0))),
                    steamFlowRate: c.generate(using: Gen<Double>.choose((0.0, 2_000.0))),
                    sync: sync
                )
            }
            return SeededRecord(id: id, kind: .wellheadLog, payload: .wellheadLog(log))

        default:
            let id = UUID()
            let pump: Double = c.generate(using: Gen<Double>.choose((0.0, 400.0)))
            let tsat: Double = c.generate(using: Gen<Double>.choose((0.0, 400.0)))
            let reading = SubcoolReading(
                id: id,
                wellId: wellId,
                casingPressure: c.generate(using: Gen<Double>.choose((0.0, 25_000.0))),
                pumpTemperature: pump,
                saturationTemperature: tsat,
                subcoolValue: tsat - pump,
                isFlashing: (tsat - pump) <= 10.0,
                capturedAt: capturedAt,
                sync: sync
            )
            return SeededRecord(id: id, kind: .subcoolReading, payload: .subcoolReading(reading))
        }
    }
}
