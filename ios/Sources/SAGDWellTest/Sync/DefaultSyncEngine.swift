//
//  Default sync engine
//
//  The concrete `SyncEngine` that drives the per-record sync-state machine
//  (design "Sync Engine"). It is an `actor` so its cycle scheduling and the
//  "only one cycle at a time" guarantee hold without external locking, and so
//  it is `Sendable` for use from connectivity callbacks and background tasks.
//
//  Responsibilities (Req 2.1–2.8, 7.6–7.7):
//    * `runSyncCycle` pulls `SyncRepository.nextPendingBatch` oldest-first,
//      skips records still inside their backoff window, pushes each via the
//      injected client, and advances state:
//        - acknowledged (2xx)      -> `markSynchronized(remoteId:)`  (Req 2.3)
//        - retryable / non-retryable failure -> `recordFailure`, which keeps
//          the record and increments `retryCount`; the 5th failure flips it to
//          `failed` and excludes it from future batches  (Req 2.4, 2.5)
//      Escalations are pushed as a multipart upload of their JSON + photo blob
//      via the background blob client (Req 7.6).
//    * Connectivity: a restore triggers a cycle within 60 s (Req 2.2); a loss
//      flips the offline indicator within 5 s (Req 2.8). Both are driven by the
//      injected `ConnectivityProvider` and `SyncClock`, so timing is
//      deterministic under test.
//    * Backoff is exponential, keyed on each record's `lastAttemptAt` (Req 2.4).
//    * Idempotency is delegated to the push client, which addresses records by
//      their client `id`; a redundant push of an acknowledged record simply
//      re-acknowledges (design "Push ordering and acknowledgment").
//    * Registers a background task so a cycle can run while suspended, and
//      schedules the next background slot after each cycle.
//
//  Implemented by task 15.1. The concrete push / blob-upload clients are the
//  `URLSession` REST client supplied by task 22.

import Foundation

/// The production sync engine. Construct it with the repository, a push client
/// (and blob-upload client for escalations), and the connectivity / clock /
/// scheduler seams; call `start()` once to begin reacting to connectivity.
public actor DefaultSyncEngine: SyncEngine {

    // MARK: - Tuning

    /// How soon after connectivity is restored a sync cycle must begin (Req 2.2).
    public static let connectivityTriggerDeadlineSeconds: Double = 60
    /// The default push batch size per cycle.
    public static let defaultBatchLimit = 50

    // MARK: - Dependencies

    private let repository: SyncRepository
    private let pushClient: SyncPushClient
    private let blobUploadClient: BlobUploadClient
    private let escalationBlobSource: EscalationBlobSource
    private let backoff: BackoffPolicy
    private let clock: SyncClock
    private let connectivity: ConnectivityProvider
    private let offlineIndicator: OfflineIndicatorPublishing
    private let backgroundScheduler: BackgroundSyncScheduling
    private let batchLimit: Int

    // MARK: - State

    /// The in-flight cycle, if any. Used so overlapping triggers coalesce onto
    /// a single run rather than racing the same records.
    private var activeCycle: Task<Void, Never>?
    /// The connectivity-monitoring task started by `start()`.
    private var monitorTask: Task<Void, Never>?

    public init(
        repository: SyncRepository,
        pushClient: SyncPushClient,
        blobUploadClient: BlobUploadClient,
        escalationBlobSource: EscalationBlobSource,
        backoff: BackoffPolicy = ExponentialBackoffPolicy(),
        clock: SyncClock = SystemSyncClock(),
        connectivity: ConnectivityProvider,
        offlineIndicator: OfflineIndicatorPublishing,
        backgroundScheduler: BackgroundSyncScheduling = NoopBackgroundSyncScheduler(),
        batchLimit: Int = DefaultSyncEngine.defaultBatchLimit
    ) {
        self.repository = repository
        self.pushClient = pushClient
        self.blobUploadClient = blobUploadClient
        self.escalationBlobSource = escalationBlobSource
        self.backoff = backoff
        self.clock = clock
        self.connectivity = connectivity
        self.offlineIndicator = offlineIndicator
        self.backgroundScheduler = backgroundScheduler
        self.batchLimit = batchLimit
    }

    // MARK: - Lifecycle

    /// Begin reacting to connectivity transitions and register the background
    /// task. Call once at app launch. Idempotent: a second call is a no-op
    /// while monitoring is already active.
    public func start() {
        guard monitorTask == nil else { return }

        // Register the background task so a cycle can run while suspended. The
        // handler hops back onto the actor to run one cycle.
        backgroundScheduler.registerTask { [weak self] in
            await self?.runSyncCycle()
        }

        monitorTask = Task { [weak self] in
            guard let self else { return }
            let stream = await self.connectivity.statusStream()
            for await status in stream {
                await self.handleConnectivity(status)
            }
        }
    }

    /// Stop monitoring connectivity. The next `start()` resumes it.
    public func stop() {
        monitorTask?.cancel()
        monitorTask = nil
        activeCycle?.cancel()
        activeCycle = nil
    }

    // MARK: - Connectivity reactions

    /// React to a connectivity transition: a loss flips the offline indicator
    /// (Req 2.8); a restore triggers `onConnectivityAvailable()` (Req 2.2).
    private func handleConnectivity(_ status: ConnectivityStatus) async {
        switch status {
        case .satisfied:
            await offlineIndicator.setOffline(false)
            await onConnectivityAvailable()
        case .unsatisfied:
            // The offline indicator is published promptly; the design's 5 s
            // budget (Req 2.8) is the deadline, and publishing synchronously on
            // the transition meets it with margin.
            await offlineIndicator.setOffline(true)
        }
    }

    // MARK: - SyncEngine

    /// Schedule a sync cycle so syncing begins within the connectivity-trigger
    /// deadline (Req 2.2). The cycle runs immediately here (well within 60 s);
    /// the deadline is the upper bound the integration test asserts against.
    public func onConnectivityAvailable() async {
        scheduleCycle()
    }

    /// Run one sync cycle: pull the pending batch oldest-first, push each
    /// eligible record, and advance its state machine.
    ///
    /// Only runs when connectivity is satisfied — there is no point pushing
    /// while offline, and a cycle triggered by a stale schedule simply no-ops.
    public func runSyncCycle() async {
        guard connectivity.currentStatus == .satisfied else { return }

        let batch = await repository.nextPendingBatch(limit: batchLimit)
        // The repository already returns oldest-first; iterate in that order so
        // records push in strictly ascending `createdAt` (Req 2.1).
        for record in batch {
            if Task.isCancelled { break }

            // Respect the per-record backoff window keyed on `lastAttemptAt`
            // (Req 2.4). A record still inside its window is left for a later
            // cycle; it stays `pending` and keeps its place in the queue.
            guard backoff.isEligible(record.sync, now: clock.now()) else { continue }

            await push(record)
        }

        // Ask for the next background slot so progress continues while the app
        // is suspended (design "Connectivity and background execution").
        let nextRun = clock.now().addingTimeInterval(Self.connectivityTriggerDeadlineSeconds)
        backgroundScheduler.scheduleNextRun(earliest: nextRun)
    }

    /// Upload an escalation's photo blob via multipart on the background client,
    /// advancing the escalation's state exactly as a normal push would.
    ///
    /// Exposed on the protocol for callers that want to drive a single
    /// escalation upload directly; `runSyncCycle` reaches the same path through
    /// `push(_:)` when it encounters an escalation record.
    public func uploadPhotoBlob(for escalation: Escalation) async {
        guard let resolved = await escalationBlobSource.escalationWithBlobURL(id: escalation.id) else {
            // Missing escalation or blob: treat as a failed attempt so the
            // record is not silently stranded.
            await repository.recordFailure(recordId: escalation.id)
            return
        }
        let result = await blobUploadClient.upload(
            escalation: resolved.escalation,
            blobURL: resolved.blobURL
        )
        await apply(result, to: escalation.id)
    }

    // MARK: - Push one record

    /// Push a single record through the right client for its kind and advance
    /// its state based on the classified result.
    private func push(_ record: SyncableRecord) async {
        let result: SyncPushResult
        switch record.kind {
        case .escalation:
            // Escalations go through the multipart blob upload (JSON + photo).
            if let resolved = await escalationBlobSource.escalationWithBlobURL(id: record.id) {
                result = await blobUploadClient.upload(
                    escalation: resolved.escalation,
                    blobURL: resolved.blobURL
                )
            } else {
                result = .retryableFailure(reason: "escalation blob unavailable")
            }
        case .wellTest, .wellheadLog, .subcoolReading:
            result = await pushClient.push(record)
        }
        await apply(result, to: record.id)
    }

    /// Advance a record's state machine from a push result.
    ///
    /// Acknowledgment marks the record `synchronized` with its `remoteId`
    /// (Req 2.3). Both retryable and non-retryable failures are recorded as a
    /// failed attempt via `recordFailure`, which increments `retryCount`, keeps
    /// the record (no data loss — Req 2.4), and transitions it to `failed` on
    /// the 5th consecutive failure (Req 2.5, 7.7). Counting a non-retryable
    /// failure toward the limit stops the engine looping forever on a record
    /// the backend will never accept.
    private func apply(_ result: SyncPushResult, to recordId: UUID) async {
        switch result {
        case let .acknowledged(remoteId):
            await repository.markSynchronized(recordId: recordId, remoteId: remoteId)
        case .retryableFailure, .nonRetryableFailure:
            await repository.recordFailure(recordId: recordId)
        }
    }

    // MARK: - Cycle scheduling

    /// Start a cycle, coalescing with any already in flight so overlapping
    /// triggers do not race the same records. A trigger that arrives mid-cycle
    /// is served by the current run (which re-reads the batch each time it is
    /// started), so no work is dropped.
    private func scheduleCycle() {
        if let active = activeCycle, !active.isCancelled {
            return
        }
        activeCycle = Task { [weak self] in
            await self?.runSyncCycle()
            await self?.clearActiveCycle()
        }
    }

    private func clearActiveCycle() {
        activeCycle = nil
    }
}
