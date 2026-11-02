//
//  Save error mapping
//
//  Shared translation of errors thrown by the local store (GRDB SQLite errors
//  and `BlobStore` errors) into the repository's `SaveResult`. Centralized so
//  every repository classifies a disk-full condition identically as
//  `storageFull` (Req 1.5) and everything else as `failure(reason:)` (Req 1.4).
//
//  Implemented by task 5.1.

import Foundation
import GRDB

enum SaveErrorMapping {

    /// Maps any error thrown while saving into a non-success `SaveResult`.
    ///
    /// A filesystem out-of-space condition — whether surfaced by SQLite
    /// (`SQLITE_FULL`), the blob store (`BlobStoreError.outOfSpace`), or a
    /// POSIX/Cocoa disk-full error — maps to `.storageFull`. Everything else
    /// maps to `.failure(reason:)` with a diagnostic description. The
    /// transaction has already rolled back by the time this is called, so the
    /// prior persisted state is untouched (Req 1.4).
    static func saveResult(for error: Error) -> SaveResult {
        if isDiskFull(error) {
            return .storageFull
        }
        return .failure(reason: String(describing: error))
    }

    /// Whether `error` represents a filesystem out-of-space condition.
    static func isDiskFull(_ error: Error) -> Bool {
        // Blob store already classifies out-of-space explicitly.
        if let blobError = error as? BlobStoreError, blobError == .outOfSpace {
            return true
        }

        // GRDB surfaces SQLite result codes on DatabaseError. SQLITE_FULL (13)
        // is the disk-full code; it may also appear as an extended code whose
        // primary code is SQLITE_FULL.
        if let dbError = error as? DatabaseError {
            let primary = dbError.resultCode.primaryResultCode
            if primary == .SQLITE_FULL {
                return true
            }
        }

        // POSIX / Cocoa disk-full surfaced directly.
        let nsError = error as NSError
        if nsError.domain == NSPOSIXErrorDomain, nsError.code == Int(ENOSPC) {
            return true
        }
        if nsError.domain == NSCocoaErrorDomain,
           nsError.code == CocoaError.fileWriteOutOfSpace.rawValue {
            return true
        }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError,
           underlying.domain == NSPOSIXErrorDomain,
           underlying.code == Int(ENOSPC) {
            return true
        }

        return false
    }
}
