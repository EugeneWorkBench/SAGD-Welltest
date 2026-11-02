//
//  ConnectivityTimingIntegrationTests.swift
//  Integration test — connectivity timing (iOS) — task 15.8.
//
//  _Requirements: 2.2, 2.8_
//
//  Design "Integration testing" (controllable clock + fake `NWPathMonitor`):
//  the connectivity-timing behavior of the real `DefaultSyncEngine` is exercised
//  end to end through its production seams, with no real `NWPathMonitor`, no real
//  network, and no real delays. Two timing guarantees are asserted against a
//  controllable `SyncClock`:
//
//    * Req 2.2 — WHEN connectivity is restored after a period without it, the
//      Sync_Service SHALL begin synchronizing unsynchronized records within 60
//      seconds. Here: the engine is `start()`ed, connectivity is driven from
//      `.unsatisfied` to `.satisfied`, and we assert a sync cycle begins — the
//      recording push client observes the first push — and that the virtual
//      clock reading captured at that push is within 60 s of the clock reading
//      captured when the restore transition was driven.
//
//    * Req 2.8 — WHILE the device has no network connectivity, the App SHALL
//      display an offline indicator within 5 seconds of connectivity loss.
//      Here: connectivity is driven from `.satisfied` to `.unsatisfied`, and we
//      assert the `OfflineIndicator` the UI observes flips to offline within 5 s
//      of the loss transition (measured on the same virtual clock).
//
//  The store is a fresh in-memory `AppDatabase.makeInMemory()` with the real
//  `GRDBSyncRepository` and seeded pending records, so the cycle has real work
//  to push. The connectivity seam is a fake provider whose `statusStream()` can
//  be driven to emit `.satisfied` / `.unsatisfied` on command (standing in for
//  `NWPathMonitor`); the clock is a controllable `SyncClock` reporting a fixed
//  virtual instant so timing assertions are deterministic and never actually
//  wait.

import XCTest
import GRDB
@testable import SAGDWellTest

final class ConnectivityTimingIntegrationTests: XCTestCase {

    /// Req 2.2 — a connectivity restore begins a sync cycle (records push)
    /// within 60 s of the restore transition.
    func testSyncBeginsWithin60SecondsOfConnectivityRestore() async throws {
        let appDB = try AppDatabase.makeInMemory()
        try Self.seedPendingRecords(into: appDB)

        let repository = GRDBSyncRepository(appDatabase: appDB)
        let clock = ControllableSyncClock(now: Date(timeIntervalSince1970: 1_700_000_000))
        let recorder = TimedPushRecorder(clock: clock)
        // Start offline so the restore is a genuine "connectivity returns after a
        // period without it" transition (Req 2.2).
        let connectivity = DrivableConnectivityProvider(initial: .unsatisfied)
        let offlineIndicator = OfflineIndicator(initiallyOffline: true)

        let engine = DefaultSyncEngine(
            repository: repository,
            pushClient: RecordingTimedPushClient(recorder: recorder),
            blobUploadClient: RecordingTimedBlobUploadClient(recorder: recorder),
            escalationBlobSource: EmptyEscalationBlobSource(),
            clock: clock,
            connectivity: connectivity,
            offlineIndicator: offlineIndicator
        )

        await engine.start()

        // The instant (virtual clock) at which connectivity is restored.
        let restoreInstant = clock.now()
        connectivity.drive(.satisfied)

        // Wait for the engine to react to the restore and push at least one
        // record. Polling the recorder avoids any dependence on real time — the
        // engine runs its cycle immediately after the restore; this only awaits
        // the actor hops.
        let firstPushInstant = try await Self.waitForFirstPush(recorder)

        let elapsed = firstPushInstant.timeIntervalSince(restoreInstant)
        XCTAssertGreaterThanOrEqual(
            elapsed, 0,
            "the first push must occur at or after the restore transition"
        )
        XCTAssertLessThanOrEqual(
            elapsed,
            DefaultSyncEngine.connectivityTriggerDeadlineSeconds,
            "sync must begin within 60 s of connectivity restore (Req 2.2); elapsed \(elapsed)s"
        )

        await engine.stop()
    }

    /// Req 2.8 — a connectivity loss flips the offline indicator within 5 s of
    /// the loss transition.
    func testOfflineIndicatorFlipsWithin5SecondsOfConnectivityLoss() async throws {
        let appDB = try AppDatabase.makeInMemory()
        try Self.seedPendingRecords(into: appDB)

        let repository = GRDBSyncRepository(appDatabase: appDB)
        let clock = ControllableSyncClock(now: Date(timeIntervalSince1970: 1_700_000_000))
        let recorder = TimedPushRecorder(clock: clock)
        // Start online so the loss is a genuine satisfied -> unsatisfied drop.
        let connectivity = DrivableConnectivityProvider(initial: .satisfied)
        let offlineIndicator = OfflineIndicator(initiallyOffline: false)

        let engine = DefaultSyncEngine(
            repository: repository,
            pushClient: RecordingTimedPushClient(recorder: recorder),
            blobUploadClient: RecordingTimedBlobUploadClient(recorder: recorder),
            escalationBlobSource: EmptyEscalationBlobSource(),
            clock: clock,
            connectivity: connectivity,
            offlineIndicator: offlineIndicator
        )

        // Observe the offline indicator the UI binds to, before starting, so the
        // initial value and every change are seen.
        let observation = offlineIndicator.observe()

        await engine.start()

        // The instant (virtual clock) at which connectivity is lost.
        let lossInstant = clock.now()
        connectivity.drive(.unsatisfied)

        // Wait for the observed offline value to flip to `true`, capturing the
        // virtual clock reading at the moment it does.
        let offlineInstant = try await Self.waitForOffline(
            observation: observation,
            clock: clock
        )

        let elapsed = offlineInstant.timeIntervalSince(lossInstant)
        XCTAssertGreaterThanOrEqual(
            elapsed, 0,
            "the offline indicator must flip at or after the loss transition"
        )
        XCTAssertLessThanOrEqual(
            elapsed, 5,
            "offline indicator must flip within 5 s of connectivity loss (Req 2.8); elapsed \(elapsed)s"
        )

        await engine.stop()
    }

    // MARK: - Waiting helpers

    /// Polls the recorder until it has observed at least one push, returning the
    /// virtual clock instant captured at that push. Fails if no push is observed
    /// within a generous real-time budget (the engine reacts immediately; this
    /// budget only guards against a hang).
    private static func waitForFirstPush(
        _ recorder: TimedPushRecorder,
        realTimeout: TimeInterval = 5
    ) async throws -> Date {
        let deadline = Date().addingTimeInterval(realTimeout)
        while Date() < deadline {
            if let instant = await recorder.firstPushInstant() {
                return instant
            }
            try await Task.sleep(nanoseconds: 1_000_000) // 1 ms
        }
        XCTFail("no sync push observed within \(realTimeout)s of connectivity restore")
        throw IntegrationTestError.timedOut
    }

    /// Consumes the offline-indicator observation until it yields `true`,
    /// returning the virtual clock instant at that moment.
    private static func waitForOffline(
        observation: AsyncStream<Bool>,
        clock: ControllableSyncClock,
        realTimeout: TimeInterval = 5
    ) async throws -> Date {
        let waiter = Task { () -> Date? in
            for await isOffline in observation where isOffline {
                return clock.now()
            }
            return nil
        }
        let timeout = Task { () -> Date? in
            try? await Task.sleep(nanoseconds: UInt64(realTimeout * 1_000_000_000))
            waiter.cancel()
            return nil
        }
        let result = await waiter.value
        timeout.cancel()
        guard let instant = result else {
            XCTFail("offline indicator did not flip within \(realTimeout)s of connectivity loss")
            throw IntegrationTestError.timedOut
        }
        return instant
    }

    // MARK: - Seeding

    /// Seeds a small set of pending records (across record kinds) so a sync
    /// cycle triggered by connectivity has real work to push. Uses the in-memory
    /// `AppDatabase` + real `GRDBSyncRepository` exactly as the running app does.
    private static func seedPendingRecords(into appDB: AppDatabase) throws {
        let createdBase = Date(timeIntervalSince1970: 1_600_000_000)
        try appDB.dbWriter.write { db in
            let well = Well(
                id: "WELL-TIMING-1",
                name: "Timing Well",
                type: .production,
                padId: nil,
                updatedAt: createdBase
            )
            try WellRecord(well).insert(db)

            try WellTestRecord(
                WellTest(
                    id: UUID(),
                    wellId: well.id,
                    oilRatio: 0.3,
                    waterRatio: 0.4,
                    gasRatio: 0.3,
                    capturedAt: createdBase,
                    sync: SyncMetadata(
                        syncState: .pending,
                        retryCount: 0,
                        createdAt: createdBase,
                        lastAttemptAt: nil,
                        remoteId: nil
                    )
                )
            ).insert(db)

            try WellheadLogRecord(
                WellheadLog(
                    id: UUID(),
                    wellId: well.id,
                    logType: .production,
                    capturedAt: createdBase.addingTimeInterval(1),
                    emulsionFlowRate: 10,
                    casingGasPressure: 20,
                    espMotorTemperature: 30,
                    waterCutPercent: 40,
                    sync: SyncMetadata(
                        syncState: .pending,
                        retryCount: 0,
                        createdAt: createdBase.addingTimeInterval(1),
                        lastAttemptAt: nil,
                        remoteId: nil
                    )
                )
            ).insert(db)
        }
    }
}

// MARK: - Errors

private enum IntegrationTestError: Error {
    case timedOut
}

// MARK: - Controllable clock

/// A `SyncClock` reporting a fixed virtual instant, advanceable only on command.
/// `sleep` is a no-op so the engine never actually waits; timing assertions read
/// `now()` instead. Thread-safe so it can be read from the engine's actor and
/// the test concurrently.
private final class ControllableSyncClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(now: Date) {
        self.current = now
    }

    func now() -> Date {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    /// Advance virtual time by `seconds`. Not needed by these timing cases (the
    /// engine reacts immediately), but provided so the controllable clock can
    /// model elapsed time explicitly if a scenario requires it.
    func advance(by seconds: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        current = current.addingTimeInterval(seconds)
    }

    func sleep(seconds: Double) async {
        // No real waiting: timing is asserted on the virtual clock.
    }
}

// MARK: - Drivable connectivity provider (fake NWPathMonitor)

/// A fake `ConnectivityProvider` standing in for `NWPathMonitor`. Its
/// `statusStream()` is backed by a continuation the test drives with `drive(_:)`
/// to emit `.satisfied` / `.unsatisfied` on command, and `currentStatus`
/// reflects the latest driven value so a cycle that reads it behaves correctly.
private final class DrivableConnectivityProvider: ConnectivityProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var status: ConnectivityStatus
    private var continuation: AsyncStream<ConnectivityStatus>.Continuation?

    init(initial: ConnectivityStatus) {
        self.status = initial
    }

    var currentStatus: ConnectivityStatus {
        lock.lock(); defer { lock.unlock() }
        return status
    }

    func statusStream() -> AsyncStream<ConnectivityStatus> {
        AsyncStream { continuation in
            lock.lock()
            self.continuation = continuation
            let initial = status
            lock.unlock()
            // The first value is the current status at the time monitoring
            // starts (per the `ConnectivityProvider` contract).
            continuation.yield(initial)
        }
    }

    /// Drive a connectivity transition: update `currentStatus` and emit it on
    /// the stream so the engine reacts exactly as it would to a real
    /// `NWPathMonitor` path update.
    func drive(_ newStatus: ConnectivityStatus) {
        lock.lock()
        status = newStatus
        let continuation = self.continuation
        lock.unlock()
        continuation?.yield(newStatus)
    }
}

// MARK: - Timed push recorder and recording clients

/// Records the virtual clock instant of the first push observed. Shared by the
/// recording push client and blob-upload client so any record kind counts as
/// "sync begun". Actor-isolated so concurrent pushes serialize.
private actor TimedPushRecorder {
    private let clock: ControllableSyncClock
    private var firstInstant: Date?
    private var count = 0

    init(clock: ControllableSyncClock) {
        self.clock = clock
    }

    func record() {
        count += 1
        if firstInstant == nil {
            firstInstant = clock.now()
        }
    }

    func firstPushInstant() -> Date? { firstInstant }
}

/// A `SyncPushClient` that stamps each push on the shared recorder and
/// acknowledges it (so records transition to `synchronized` and are not
/// retried within the cycle).
private struct RecordingTimedPushClient: SyncPushClient {
    let recorder: TimedPushRecorder

    func push(_ record: SyncableRecord) async -> SyncPushResult {
        await recorder.record()
        return .acknowledged(remoteId: "remote-\(record.id.uuidString)")
    }
}

/// A `BlobUploadClient` that stamps each escalation push on the shared recorder
/// and acknowledges it. Not exercised by the seeded records here, but wired so
/// the engine's escalation path has a client if a cycle reaches it.
private struct RecordingTimedBlobUploadClient: BlobUploadClient {
    let recorder: TimedPushRecorder

    func upload(escalation: Escalation, blobURL: URL) async -> SyncPushResult {
        await recorder.record()
        return .acknowledged(remoteId: "remote-\(escalation.id.uuidString)")
    }
}

/// An escalation blob source that resolves nothing — the seeded records contain
/// no escalations, so the engine never asks it for a blob.
private struct EmptyEscalationBlobSource: EscalationBlobSource {
    func escalationWithBlobURL(id: UUID) async -> (escalation: Escalation, blobURL: URL)? {
        nil
    }
}
