//
//  AcknowledgmentYieldsSynchronizedStatePropertyTests.swift
//
//  Property 5: Acknowledgment yields synchronized state (iOS) — task 15.3.
//
//  Validates: Requirements 2.3
//
//  Requirement 2.3 says that when the Cloud_Backend acknowledges a pushed
//  record (a 2xx response carrying a server-assigned remote id), the App must
//  mark that record's sync state `synchronized` and persist the returned
//  remote id against it.
//
//  Design "Property 5: Acknowledgment yields synchronized state":
//
//      For any set of pending records, running a sync cycle in which every
//      push is acknowledged leaves each of those records in the
//      `synchronized` state with the acknowledgment's `remoteId` stored.
//
//  This property drives the real engine end to end against the real store:
//
//    * `DefaultSyncEngine` is the actual production engine, wired to the real
//      `GRDBSyncRepository` over an in-memory `AppDatabase` (foreign keys
//      enforced). Nothing about the acknowledgment path is stubbed on the
//      engine or repository side.
//    * The push client is a stub that returns `.acknowledged(remoteId:)` for
//      every record, with the `remoteId` derived from the pushed record's `id`
//      so the test can assert the *specific* id each record received was the
//      one persisted — not merely that *some* id was stored.
//    * Connectivity is fixed to `.satisfied` (a test `FixedConnectivityProvider`
//      standing in for `StaticConnectivityProvider(satisfied)`), so the cycle
//      actually runs — `runSyncCycle` no-ops while offline.
//    * A controllable clock (`FixedSyncClock`) supplies a deterministic "now";
//      seeded records have `lastAttemptAt == nil` / `retryCount == 0`, so every
//      record is immediately backoff-eligible and pushed in the one cycle.
//
//  Only well-test, wellhead-log, and subcool-reading records are seeded: those
//  are the kinds the engine routes through `SyncPushClient` (Req 2.3's push
//  path). Escalations take the separate multipart blob-upload path, which is a
//  different property; the escalation seams here are inert and never exercised.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 5: Acknowledgment yields synchronized state`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class AcknowledgmentYieldsSynchronizedStatePropertyTests: XCTestCase {

    func testAcknowledgmentYieldsSynchronizedState() {
        property(
            propertyName(5, "Acknowledgment yields synchronized state"),
            arguments: pbtArguments()
        ) <- forAll(seededCycleGenerator()) { (seed: SeededCycle) in
            do {
                return try self.acknowledgmentSynchronizesAll(seed)
            } catch {
                return false <?> "cycle threw: \(error)"
            }
        }
    }

    // MARK: - The property

    /// Seeds the generated pending records, runs a single sync cycle in which
    /// every push is acknowledged, then asserts each seeded record is now
    /// `synchronized` and carries the exact `remoteId` the stub returned for it.
    private func acknowledgmentSynchronizesAll(_ seed: SeededCycle) throws -> Testable {
        let db = try AppDatabase.makeInMemory()
        try Self.seed(seed, into: db)

        let repository = GRDBSyncRepository(appDatabase: db, now: { Self.fixedNow })

        // Acknowledge every push, echoing a remote id derived from the record
        // id so we can verify the per-record association, not just presence.
        let pushClient = AcknowledgingPushClient(remoteIdFor: Self.remoteId(for:))

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
            batchLimit: max(seed.records.count, 1)
        )

        Self.runBlocking { await engine.runSyncCycle() }

        // Every seeded record must now be `synchronized` with its remote id.
        for record in seed.records {
            let observed = try Self.loadSyncMetadata(id: record.id, kind: record.kind, from: db)
            guard let observed else {
                return false <?> "record \(record.id) (\(record.kind)) vanished after the cycle"
            }
            guard observed.syncState == .synchronized else {
                return false <?> "record \(record.id) (\(record.kind)) is \(observed.syncState), expected synchronized"
            }
            guard observed.remoteId == Self.remoteId(for: record.id) else {
                return false <?> "record \(record.id) stored remoteId \(observed.remoteId ?? "nil"), expected \(Self.remoteId(for: record.id))"
            }
        }

        return true.property
    }

    // MARK: - Deterministic time / remote-id mapping

    /// A fixed instant used by both the clock and the repository so timestamps
    /// are deterministic across iterations.
    private static let fixedNow = Date(timeIntervalSince1970: 1_700_000_000)

    /// The remote id the stub returns for a given record id. Deterministic and
    /// unique per record so the test can assert the exact id round-trips.
    private static func remoteId(for id: UUID) -> String {
        "remote-\(id.uuidString)"
    }

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

    // MARK: - Test doubles

    /// A push client that acknowledges every record with a remote id derived
    /// from its `id`.
    private struct AcknowledgingPushClient: SyncPushClient {
        let remoteIdFor: @Sendable (UUID) -> String

        func push(_ record: SyncableRecord) async -> SyncPushResult {
            .acknowledged(remoteId: remoteIdFor(record.id))
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

    /// Draws a batch of 1...6 pending records (so the "set of records" clause
    /// is exercised with more than one), each a well test, wellhead log, or
    /// subcool reading associated with its own freshly seeded well. Every
    /// record is seeded `pending` with `retryCount == 0` and no prior attempt,
    /// so all are backoff-eligible in the single cycle.
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
