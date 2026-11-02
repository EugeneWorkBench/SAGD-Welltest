//
//  OldestFirstPushOrderingPropertyTests.swift
//  Property 4: Oldest-first push ordering (iOS) — task 15.2.
//
//  Validates: Requirements 2.1
//
//  Design "Property 4: Oldest-first push ordering":
//
//      For any set of pending records with distinct creation times, the sync
//      engine pushes them to the Cloud_Backend in strictly ascending
//      `createdAt` order.
//
//  This property exercises the real push-ordering path end to end through the
//  production `DefaultSyncEngine`:
//
//    * The store is a fresh in-memory `AppDatabase.makeInMemory()` per
//      generated case, with the real `GRDBSyncRepository` driving the uniform
//      push queue across all four syncable tables (`wellTest`, `wellheadLog`,
//      `subcoolReading`, `escalation`).
//    * A varied set of syncable records is seeded with DISTINCT `createdAt`
//      values spread across the record types, all left `pending` with
//      `retryCount == 0` and `lastAttemptAt == nil` so every record is eligible
//      this cycle (no backoff skips) and the full batch pushes in one
//      `runSyncCycle()`.
//    * A recording `SyncPushClient` and a recording `BlobUploadClient` share a
//      single `PushRecorder` (an actor), so the recorder captures the complete
//      push order across every record kind — non-escalation records route to
//      the push client, escalations route to the blob-upload client, and both
//      append to the same ordered log. Both clients acknowledge so no record is
//      retried and the recorded order is exactly the engine's push order.
//    * Connectivity is a fake provider reporting `.satisfied` so the cycle
//      runs; the `SyncClock` is a controllable clock pinned at a fixed instant
//      (time never advances, matching the deterministic-timing design).
//    * The property: the sequence of recorded record ids, mapped back to their
//      seeded `createdAt`, is strictly ascending.
//
//  Runs a minimum of 100 iterations via `pbtArguments()` and is tagged
//  `Feature: sagd-welltest-mobile, Property 4: Oldest-first push ordering`.

import XCTest
import SwiftCheck
import GRDB
@testable import SAGDWellTest

final class OldestFirstPushOrderingPropertyTests: XCTestCase {

    func testOldestFirstPushOrdering() {
        property(
            propertyName(4, "Oldest-first push ordering"),
            arguments: pbtArguments()
        ) <- forAll(Self.seedCaseGenerator()) { (seedCase: SeedCase) in
            do {
                return try self.oldestFirstHolds(for: seedCase)
            } catch {
                return false <?> "push-ordering check threw: \(error)"
            }
        }
    }

    // MARK: - Ordering check

    /// Seeds the generated records (distinct `createdAt`), runs one sync cycle
    /// through the real engine, and asserts the recorded push order is strictly
    /// ascending by `createdAt`.
    private func oldestFirstHolds(for seedCase: SeedCase) throws -> Testable {
        let appDB = try AppDatabase.makeInMemory()

        // Seed the parent wells first (FK), then every syncable record.
        try Self.seed(seedCase, into: appDB)

        let repository = GRDBSyncRepository(appDatabase: appDB)
        let recorder = PushRecorder()
        // A clock pinned at a fixed instant: fresh records (retryCount 0,
        // lastAttemptAt nil) are always eligible regardless of "now", so time
        // never needs to advance for this property.
        let clock = FixedSyncClock(now: Date(timeIntervalSince1970: 1_700_000_000))

        let engine = DefaultSyncEngine(
            repository: repository,
            pushClient: RecordingSyncPushClient(recorder: recorder),
            blobUploadClient: RecordingBlobUploadClient(recorder: recorder),
            escalationBlobSource: StubEscalationBlobSource(escalationsById: seedCase.escalationsById),
            clock: clock,
            connectivity: SatisfiedConnectivityProvider(),
            offlineIndicator: OfflineIndicator(),
            // Big enough that the whole seeded set pushes in one cycle.
            batchLimit: max(seedCase.records.count, 1)
        )

        Self.runBlocking {
            await engine.runSyncCycle()
        }

        let pushedIds = Self.runBlocking { await recorder.pushedIds() }

        // Every seeded record must have been pushed exactly once.
        let expectedIds = Set(seedCase.records.map(\.id))
        guard pushedIds.count == seedCase.records.count,
              Set(pushedIds) == expectedIds else {
            return false <?> """
                expected every seeded record pushed exactly once: \
                seeded \(seedCase.records.count), pushed \(pushedIds.count); \
                ids match=\(Set(pushedIds) == expectedIds)
                """
        }

        // Map each pushed id back to its seeded createdAt and assert the
        // sequence is strictly ascending (oldest first — Req 2.1).
        let createdAtById = Dictionary(
            uniqueKeysWithValues: seedCase.records.map { ($0.id, $0.createdAt) }
        )
        let pushedCreatedAts = pushedIds.compactMap { createdAtById[$0] }

        var strictlyAscending = true
        if pushedCreatedAts.count >= 2 {
            for i in 1..<pushedCreatedAts.count {
                if !(pushedCreatedAts[i - 1] < pushedCreatedAts[i]) {
                    strictlyAscending = false
                    break
                }
            }
        }

        return strictlyAscending
            <?> "push order not strictly ascending by createdAt: \(pushedCreatedAts)"
    }

    // MARK: - Seeding

    private static func seed(_ seedCase: SeedCase, into appDB: AppDatabase) throws {
        try appDB.dbWriter.write { db in
            // Parent wells (deduplicated) first so every FK resolves.
            for well in seedCase.wells {
                try WellRecord(well).insert(db)
            }
            for record in seedCase.records {
                switch record.kind {
                case .wellTest:
                    try WellTestRecord(
                        WellTest(
                            id: record.id,
                            wellId: record.wellId,
                            oilRatio: 0.3,
                            waterRatio: 0.4,
                            gasRatio: 0.3,
                            capturedAt: record.createdAt,
                            sync: Self.freshPending(record.createdAt)
                        )
                    ).insert(db)
                case .wellheadLog:
                    try WellheadLogRecord(
                        WellheadLog(
                            id: record.id,
                            wellId: record.wellId,
                            logType: .production,
                            capturedAt: record.createdAt,
                            emulsionFlowRate: 10,
                            casingGasPressure: 20,
                            espMotorTemperature: 30,
                            waterCutPercent: 40,
                            sync: Self.freshPending(record.createdAt)
                        )
                    ).insert(db)
                case .subcoolReading:
                    try SubcoolReadingRecord(
                        SubcoolReading(
                            id: record.id,
                            wellId: record.wellId,
                            casingPressure: 100,
                            pumpTemperature: 150,
                            saturationTemperature: 170,
                            subcoolValue: 20,
                            isFlashing: false,
                            capturedAt: record.createdAt,
                            sync: Self.freshPending(record.createdAt)
                        )
                    ).insert(db)
                case .escalation:
                    try EscalationRecord(
                        Escalation(
                            id: record.id,
                            wellId: record.wellId,
                            priority: .high,
                            photoRef: "photo-\(record.id.uuidString)",
                            latitude: nil,
                            longitude: nil,
                            locationUnavailable: true,
                            capturedAt: record.createdAt,
                            sync: Self.freshPending(record.createdAt)
                        )
                    ).insert(db)
                }
            }
        }
    }

    /// A fresh `pending` metadata with the given `createdAt` and no prior
    /// attempt, so the record is eligible on the first cycle.
    private static func freshPending(_ createdAt: Date) -> SyncMetadata {
        SyncMetadata(
            syncState: .pending,
            retryCount: 0,
            createdAt: createdAt,
            lastAttemptAt: nil,
            remoteId: nil
        )
    }

    // MARK: - Async bridge

    /// Runs an async operation to completion from the synchronous SwiftCheck
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

    // MARK: - Generated case

    /// One syncable record to seed: its id, kind, parent well id, and the
    /// distinct `createdAt` that drives its queue position.
    private struct SeedRecord {
        let id: UUID
        let kind: SyncableRecordKind
        let wellId: String
        let createdAt: Date
    }

    /// A full generated scenario: a set of records with distinct `createdAt`
    /// spread across the record kinds, plus the parent wells they reference and
    /// a lookup of the seeded escalations for the blob-source stub.
    private struct SeedCase {
        let wells: [Well]
        let records: [SeedRecord]
        let escalationsById: [UUID: Escalation]
    }

    // MARK: - Generators

    private static let kindGen: Gen<SyncableRecordKind> =
        Gen<SyncableRecordKind>.fromElements(of: [
            .wellTest, .wellheadLog, .subcoolReading, .escalation,
        ])

    /// Generates a scenario of 1...12 records with DISTINCT `createdAt` values
    /// (the property's precondition), varied kinds, and a shared parent well
    /// pool so FKs always resolve. The distinct timestamps are drawn as a set
    /// of epoch seconds. Seed/insertion order does not drive the result — the
    /// repository query orders the queue by `createdAt` regardless of insertion
    /// order, so the engine (not the seeding) is what the property exercises.
    private static func seedCaseGenerator() -> Gen<SeedCase> {
        Gen<SeedCase>.compose { c in
            let count: Int = c.generate(using: Gen<Int>.choose((1, 12)))

            // Distinct createdAt values: draw unique epoch seconds.
            var seconds = Set<Int>()
            var attempts = 0
            while seconds.count < count && attempts < count * 40 + 100 {
                seconds.insert(c.generate(using: Gen<Int>.choose((0, 2_000_000_000))))
                attempts += 1
            }
            let secondsList = Array(seconds)

            // A small pool of parent wells; each record picks one.
            let wellCount: Int = c.generate(using: Gen<Int>.choose((1, 3)))
            let wells: [Well] = (0..<wellCount).map { idx in
                Well(
                    id: "WELL-\(c.generate(using: Gen<Int>.choose((1, 1_000_000))))-\(idx)",
                    name: "Well \(idx)",
                    type: .production,
                    padId: nil,
                    updatedAt: Date(timeIntervalSince1970: 1_600_000_000)
                )
            }

            var records: [SeedRecord] = []
            var escalationsById: [UUID: Escalation] = [:]
            for sec in secondsList {
                let kind: SyncableRecordKind = c.generate(using: Self.kindGen)
                let well = wells[c.generate(using: Gen<Int>.choose((0, wells.count - 1)))]
                let id = UUID()
                let createdAt = Date(timeIntervalSince1970: TimeInterval(sec))
                records.append(
                    SeedRecord(id: id, kind: kind, wellId: well.id, createdAt: createdAt)
                )
                if kind == .escalation {
                    escalationsById[id] = Escalation(
                        id: id,
                        wellId: well.id,
                        priority: .high,
                        photoRef: "photo-\(id.uuidString)",
                        latitude: nil,
                        longitude: nil,
                        locationUnavailable: true,
                        capturedAt: createdAt,
                        sync: SyncMetadata(
                            syncState: .pending, retryCount: 0, createdAt: createdAt
                        )
                    )
                }
            }
            return SeedCase(wells: wells, records: records, escalationsById: escalationsById)
        }
    }
}

// MARK: - Shared push recorder

/// Records, in push order, the ids of every record the engine pushes. Shared by
/// the recording push client and the recording blob-upload client so the log is
/// the complete ordered push sequence across all record kinds. Actor-isolated
/// so concurrent appends from the engine's cycle are serialized.
private actor PushRecorder {
    private var ids: [UUID] = []

    func record(_ id: UUID) {
        ids.append(id)
    }

    func pushedIds() -> [UUID] {
        ids
    }
}

// MARK: - Recording clients

/// A `SyncPushClient` that records each pushed record id (in order) and
/// acknowledges it, so the record transitions to `synchronized` and is not
/// retried within the cycle.
private struct RecordingSyncPushClient: SyncPushClient {
    let recorder: PushRecorder

    func push(_ record: SyncableRecord) async -> SyncPushResult {
        await recorder.record(record.id)
        return .acknowledged(remoteId: "remote-\(record.id.uuidString)")
    }
}

/// A `BlobUploadClient` that records each pushed escalation id (in order) and
/// acknowledges it. Escalations route here rather than through the push client,
/// so sharing the recorder keeps the ordered log complete.
private struct RecordingBlobUploadClient: BlobUploadClient {
    let recorder: PushRecorder

    func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult {
        await recorder.record(escalation.id)
        return .acknowledged(remoteId: "remote-\(escalation.id.uuidString)")
    }
}

// MARK: - Fakes for the remaining engine seams

/// Resolves escalations by id for the engine's escalation push path, returning
/// a fixed placeholder blob URL (never read by the recording upload client).
private struct StubEscalationBlobSource: EscalationBlobSource {
    let escalationsById: [UUID: Escalation]

    func escalationWithBlobURL(id: UUID) async -> (escalation: Escalation, blobURL: URL)? {
        guard let escalation = escalationsById[id] else { return nil }
        let url = URL(fileURLWithPath: "/dev/null/\(id.uuidString).jpg")
        return (escalation, url)
    }
}

/// A connectivity provider pinned to `.satisfied` so `runSyncCycle()` proceeds.
/// The engine only reads `currentStatus` during a cycle; the stream is unused
/// here because the test drives the cycle directly rather than via `start()`.
private struct SatisfiedConnectivityProvider: ConnectivityProvider {
    var currentStatus: ConnectivityStatus { .satisfied }

    func statusStream() -> AsyncStream<ConnectivityStatus> {
        AsyncStream { continuation in
            continuation.yield(.satisfied)
            continuation.finish()
        }
    }
}

/// A clock pinned at a fixed instant. Fresh records are always backoff-eligible
/// regardless of "now", so time never needs to advance for this property.
private struct FixedSyncClock: SyncClock {
    let now_: Date

    init(now: Date) { self.now_ = now }

    func now() -> Date { now_ }

    func sleep(seconds: Double) async {}
}
