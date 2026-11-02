//
//  Escalation blob source
//
//  To push an escalation the engine needs the escalation's fields (for the
//  JSON part) and the on-disk URL of its photo blob (for the binary part). This
//  seam resolves both from the local store without the engine depending on GRDB
//  or the blob store directly, keeping the engine testable with a fake source.
//
//  Introduced by task 15.1.

import Foundation
import GRDB

/// Resolves an escalation record and the file URL of its photo blob by id.
public protocol EscalationBlobSource: Sendable {
    /// Load the escalation with `id` and the absolute URL of its photo blob, or
    /// `nil` if the escalation is missing or its blob cannot be resolved.
    func escalationWithBlobURL(id: UUID) async -> (escalation: Escalation, blobURL: URL)?
}

/// A GRDB + blob-store backed escalation blob source used in the running app.
public final class GRDBEscalationBlobSource: EscalationBlobSource {

    private let appDatabase: AppDatabase
    private let blobStore: BlobStore

    public init(appDatabase: AppDatabase, blobStore: BlobStore) {
        self.appDatabase = appDatabase
        self.blobStore = blobStore
    }

    public func escalationWithBlobURL(id: UUID) async -> (escalation: Escalation, blobURL: URL)? {
        let writer = appDatabase.dbWriter
        let record = try? await writer.read { db in
            try EscalationRecord.fetchOne(db, key: id.uuidString)
        }
        guard let escalation = record?.domain,
              let blobURL = try? blobStore.url(forPhotoRef: escalation.photoRef)
        else { return nil }
        return (escalation, blobURL)
    }
}
