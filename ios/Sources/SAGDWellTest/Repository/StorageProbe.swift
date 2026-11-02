//
//  Storage free-space probe
//
//  A small, injectable abstraction over "how much writable space is left" so
//  the repositories can perform a pre-write free-space check and return
//  `SaveResult.storageFull` before attempting a write that the filesystem would
//  reject (Req 1.5). Keeping it behind a protocol lets the storage-full unit
//  test (task 5.6) and the atomic-save property test (task 5.4) drive the
//  decision deterministically without actually filling the disk.
//
//  Implemented by task 5.1.

import Foundation

/// Reports the writable free space available for new records, so a save can be
/// rejected with `storageFull` before any bytes are written.
///
/// The repository consults the probe *before* opening a write transaction: if
/// the reported free space is below the required estimate, the save returns
/// `storageFull`, nothing is written, and the caller retains the operator's
/// in-form data (Req 1.5). The write path additionally catches an actual
/// disk-full error from the filesystem as a backstop, so a probe that reports
/// optimistically can never produce a partial write.
public protocol StorageProbe: Sendable {
    /// Bytes of writable space currently available, or `nil` if it cannot be
    /// determined (in which case the repository proceeds and relies on the
    /// write-path disk-full backstop).
    func availableCapacityBytes() -> Int64?
}

/// The default probe backed by the OS volume capacity for "important usage",
/// which reflects space the system is willing to make available for user data.
public struct SystemStorageProbe: StorageProbe {
    /// A directory on the volume being measured. Defaults to Application
    /// Support, the same volume the database and blob store live on.
    private let probeURL: URL
    private let fileManager: FileManager

    public init(probeURL: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        if let probeURL {
            self.probeURL = probeURL
        } else {
            // Fall back to a sensible per-user location; failures are tolerated
            // because `availableCapacityBytes()` returns nil on error.
            self.probeURL = (try? fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: false
            )) ?? URL(fileURLWithPath: NSTemporaryDirectory())
        }
    }

    public func availableCapacityBytes() -> Int64? {
        do {
            let values = try probeURL.resourceValues(
                forKeys: [.volumeAvailableCapacityForImportantUsageKey]
            )
            if let capacity = values.volumeAvailableCapacityForImportantUsage {
                return Int64(capacity)
            }
        } catch {
            // Ignore and fall through; the write-path disk-full catch is the
            // backstop when the probe cannot answer.
        }
        return nil
    }
}

/// The conservative minimum free space, in bytes, a save requires before it is
/// allowed to proceed. A single captured record (and, for escalations, its
/// photo blob) is small; this threshold leaves headroom for the SQLite
/// write-ahead log and the row itself. Escalation saves add the photo size on
/// top of this baseline.
public let minimumFreeSpaceBytes: Int64 = 1 * 1024 * 1024  // 1 MiB
