//
//  Sync layer
//
//  Background component that monitors connectivity (NWPathMonitor), selects
//  unsynchronized records oldest-first, pushes them (including photo blobs via
//  a background URLSession), handles acknowledgments and failures, and advances
//  each record's per-record sync-state machine. Registers a BGTaskScheduler
//  task for background execution.
//
//  This file defines the SyncEngine protocol (task 1.2). The concrete engine
//  (`DefaultSyncEngine`), connectivity monitor (`NWPathConnectivityProvider`),
//  offline indicator, backoff policy, clock, and background scheduler are
//  implemented in task 15.1 (see the other files in this group). The concrete
//  push / blob-upload clients the engine depends on are supplied by task 22.

import Foundation

/// Namespace marker for the Sync layer.
enum SyncLayer {}

/// The background synchronization engine. Triggered by connectivity
/// transitions, it pulls the pending batch oldest-first, pushes each record,
/// and advances each record's sync-state machine.
public protocol SyncEngine: Sendable {
    /// Called when connectivity becomes available (NWPathMonitor). Schedules a
    /// sync cycle so syncing begins within 60 seconds of restoration.
    func onConnectivityAvailable() async

    /// Run one sync cycle: pull a batch oldest-first, push each record, and
    /// advance its state (acknowledge -> synchronized; failure -> retry or
    /// failed at the retry limit).
    func runSyncCycle() async

    /// Upload an escalation's photo blob via multipart on a background session.
    func uploadPhotoBlob(for escalation: Escalation) async
}
