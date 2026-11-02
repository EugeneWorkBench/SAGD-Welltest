//
//  Background sync scheduler
//
//  Two capabilities the engine needs that are platform services on iOS and must
//  be mockable in tests:
//
//    * Background execution — register a `BGTaskScheduler` task so a sync cycle
//      can run when the app is backgrounded, and schedule the next such task
//      after each cycle (design "Connectivity and background execution").
//    * Background blob transfer — a background `URLSession` so escalation photo
//      uploads survive app suspension (Req 7.6).
//
//  Both are abstracted behind protocols so the engine can be constructed and
//  exercised with no-op / fake schedulers in unit and integration tests, while
//  the app wires the real `BGTaskScheduler`-backed implementation.
//
//  Introduced by task 15.1.

import Foundation

/// Schedules the engine's background sync work. On iOS this is backed by
/// `BGTaskScheduler`; in tests it is a recording no-op.
public protocol BackgroundSyncScheduling: Sendable {
    /// Register the background task handler with the system. Called once at app
    /// launch, before scheduling. The handler runs one sync cycle when the
    /// system grants background time.
    func registerTask(handler: @escaping @Sendable () async -> Void)
    /// Ask the system to run the sync task in the background no earlier than
    /// `earliest`. Called after each cycle so the engine keeps making progress
    /// while backgrounded.
    func scheduleNextRun(earliest: Date)
}

/// A no-op background scheduler. Used as the default so the engine runs in
/// foreground-only contexts (and tests) without a `BGTaskScheduler`.
public struct NoopBackgroundSyncScheduler: BackgroundSyncScheduling {
    public init() {}
    public func registerTask(handler: @escaping @Sendable () async -> Void) {}
    public func scheduleNextRun(earliest: Date) {}
}

#if canImport(BackgroundTasks)
import BackgroundTasks

/// `BGTaskScheduler`-backed scheduler used in the running iOS app.
///
/// The app registers a single refresh task identifier (declared in
/// `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`). On launch the app
/// calls `registerTask` with a handler that drives one `runSyncCycle()`; after
/// each cycle the engine calls `scheduleNextRun` to request the next
/// background slot. The actual submission is best-effort — the system decides
/// when (and whether) to run it.
public final class BGTaskSyncScheduler: BackgroundSyncScheduling, @unchecked Sendable {

    /// The task identifier; must match an entry in
    /// `BGTaskSchedulerPermittedIdentifiers`.
    public let taskIdentifier: String

    public init(taskIdentifier: String = "com.sagd.welltest.sync.refresh") {
        self.taskIdentifier = taskIdentifier
    }

    public func registerTask(handler: @escaping @Sendable () async -> Void) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier,
            using: nil
        ) { task in
            // Bridge the system task into the engine's async cycle. Expiration
            // cancels the work so the system reclaims the time gracefully.
            let work = Task {
                await handler()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = {
                work.cancel()
                task.setTaskCompleted(success: false)
            }
        }
    }

    public func scheduleNextRun(earliest: Date) {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = earliest
        // Submission can throw if the task is not permitted or over budget;
        // this is best-effort, so a failure simply means no background slot.
        try? BGTaskScheduler.shared.submit(request)
    }
}
#endif
