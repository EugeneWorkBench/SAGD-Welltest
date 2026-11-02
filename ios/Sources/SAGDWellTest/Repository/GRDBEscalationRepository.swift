//
//  GRDB-backed escalation repository
//
//  Persists an escalation and its photo blob. Per the design's "Geotagged
//  Photo Escalation Flow" and "Blob (photo) storage": the photo blob is
//  written to the file store *first*, then the referencing row is inserted in a
//  single transaction. If the row insert fails, the just-written blob is
//  deleted so no orphan is left (Req 7.4); if the process crashes between the
//  two writes, the orphan is reclaimed by the blob store's launch-time garbage
//  collection — a row can never point at a missing file.
//
//  Implemented by task 5.1.

import Foundation
import GRDB

/// Persists escalations and their photo blobs transactionally.
public final class GRDBEscalationRepository: EscalationRepository {

    private let appDatabase: AppDatabase
    private let blobStore: BlobStore
    private let storageProbe: StorageProbe
    private let syncSignal: SyncWorkSignaling
    private let now: @Sendable () -> Date

    public init(
        appDatabase: AppDatabase,
        blobStore: BlobStore,
        storageProbe: StorageProbe = SystemStorageProbe(),
        syncSignal: SyncWorkSignaling = NoopSyncWorkSignal(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.appDatabase = appDatabase
        self.blobStore = blobStore
        self.storageProbe = storageProbe
        self.syncSignal = syncSignal
        self.now = now
    }

    public func saveEscalation(_ escalation: Escalation, photoData: Data) async -> SaveResult {
        // Pre-write free-space check sized to the photo plus the row baseline so
        // a large photo on a nearly-full disk is rejected cleanly (Req 1.5).
        let required = minimumFreeSpaceBytes + Int64(photoData.count)
        if let available = storageProbe.availableCapacityBytes(),
           available < required {
            return .storageFull
        }

        // 1. Blob first. A failure here means nothing was persisted; map
        //    out-of-space to storageFull, anything else to failure.
        let photoRef: String
        do {
            photoRef = try blobStore.write(photoData)
        } catch {
            return SaveErrorMapping.saveResult(for: error)
        }

        // 2. Then the row, in a single transaction, referencing the blob and
        //    committing `pending` with a fresh createdAt / retryCount = 0.
        var escalation = escalation
        escalation.photoRef = photoRef
        escalation.sync = SyncMetadata(
            syncState: .pending,
            retryCount: 0,
            createdAt: now(),
            lastAttemptAt: nil,
            remoteId: nil
        )
        let record = EscalationRecord(escalation)

        let writer = appDatabase.dbWriter
        do {
            try await writer.write { db in
                try record.insert(db)
            }
        } catch {
            // Row insert failed: delete the orphaned blob so the store is left
            // exactly as it was before the save (Req 7.4). Deletion is
            // best-effort and idempotent; a failure to delete leaves at most an
            // orphan the launch-time GC reclaims.
            try? blobStore.delete(photoRef: photoRef)
            return SaveErrorMapping.saveResult(for: error)
        }

        await syncSignal.enqueuePendingWork()
        return .success
    }
}
