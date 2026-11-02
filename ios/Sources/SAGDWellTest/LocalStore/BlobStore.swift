//
//  Photo blob file store
//
//  Escalation photos are stored as files on disk, referenced by `photoRef`,
//  rather than as SQLite BLOB columns. Keeping the bytes out of the database
//  keeps the DB small and lets the sync engine stream the file directly as a
//  multipart part without loading it into memory.
//
//  Ordering guarantee (design "Blob (photo) storage", Req 7.3/7.4): the blob
//  file is always written *before* the DB row that references it. A crash
//  between the two writes therefore leaves at most an orphaned blob file on
//  disk — never a row pointing at a missing file. Orphaned blobs are reclaimed
//  by `garbageCollect(referencedRefs:)`, which the app runs on launch once the
//  set of still-referenced `photoRef`s is known.
//
//  Implemented by task 3.2. This type is pure Foundation file I/O with no
//  dependency on GRDB, the network layer, or the UI.

import Foundation

/// Errors surfaced by the blob store. These map onto the repository's
/// `SaveResult` by the caller: `.outOfSpace` becomes `storageFull`, everything
/// else becomes a `failure(reason:)`.
public enum BlobStoreError: Error, Equatable, Sendable {
    /// The underlying filesystem reported it is out of space while writing.
    case outOfSpace
    /// A blob referenced by `photoRef` does not exist on disk.
    case notFound(photoRef: String)
    /// A `photoRef` did not resolve to a path inside the blob directory
    /// (e.g. it contained path-traversal components). Guards against a row
    /// pointing outside the managed store.
    case invalidReference(photoRef: String)
    /// Any other I/O failure; carries a diagnostic description.
    case io(reason: String)
}

/// A file-based store for escalation photo blobs.
///
/// Blobs live in a dedicated subdirectory of the app's Application Support
/// directory (iOS app container). Each blob is addressed by an opaque
/// `photoRef` — the file name relative to the blob directory — which is what
/// the `Escalation` row persists.
///
/// Typical escalation save flow (owned by the repository / escalation
/// use case):
/// ```
/// let ref = try blobStore.write(photoData)   // 1. blob first
/// escalation.photoRef = ref
/// try repository.insert(escalation)           // 2. then the row
/// ```
/// If step 2 fails, the caller deletes the just-written blob so no orphan is
/// left; if the process crashes between the two steps, the orphan is reclaimed
/// on the next launch by `garbageCollect(referencedRefs:)`.
public final class BlobStore: @unchecked Sendable {

    /// Absolute URL of the directory that holds all managed blob files.
    public let directory: URL

    private let fileManager: FileManager

    // MARK: - Construction

    /// Creates a blob store rooted at an explicit directory. The directory is
    /// created (including intermediates) if it does not already exist.
    ///
    /// Prefer ``makeDefault(fileManager:)`` in app code; this initializer is
    /// primarily for tests, which point it at a temporary directory.
    ///
    /// - Parameters:
    ///   - directory: The directory in which blob files are stored.
    ///   - fileManager: The file manager to use (injectable for testing).
    public init(directory: URL, fileManager: FileManager = .default) throws {
        // Standardize once so the path-traversal check in `resolve` compares
        // like-for-like regardless of how the caller spelled the directory.
        self.directory = directory.standardizedFileURL
        self.fileManager = fileManager
        try createDirectoryIfNeeded()
    }

    /// Creates the default blob store rooted at
    /// `<Application Support>/Photos`.
    ///
    /// `Application Support` is used (rather than `Caches`) because escalation
    /// photos are user data that must survive until they have been synced — the
    /// system may purge `Caches` under disk pressure, which would break the
    /// blob/row invariant.
    ///
    /// - Parameter fileManager: The file manager to use (injectable for testing).
    public static func makeDefault(fileManager: FileManager = .default) throws -> BlobStore {
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let blobDirectory = appSupport.appendingPathComponent("Photos", isDirectory: true)
        return try BlobStore(directory: blobDirectory, fileManager: fileManager)
    }

    // MARK: - Write

    /// Writes `data` to a new blob file and returns its `photoRef`.
    ///
    /// The reference is a freshly generated UUID-based file name, so a write
    /// never overwrites an existing blob and two concurrent captures cannot
    /// collide. The write is performed with `.atomic` so a reader never sees a
    /// partially written file.
    ///
    /// Callers MUST persist the returned `photoRef` on the referencing row and,
    /// if that row write fails, call ``delete(photoRef:)`` to avoid leaving an
    /// orphan.
    ///
    /// - Parameter data: The photo bytes to store.
    /// - Returns: The `photoRef` to persist on the referencing row.
    /// - Throws: `BlobStoreError.outOfSpace` if the disk is full, otherwise
    ///   `BlobStoreError.io`.
    @discardableResult
    public func write(_ data: Data) throws -> String {
        let photoRef = "\(UUID().uuidString).blob"
        let url = try resolve(photoRef)
        do {
            // `.atomic` writes to a temporary file and renames into place, so a
            // crash mid-write cannot leave a truncated blob at `url`.
            try data.write(to: url, options: .atomic)
        } catch {
            throw Self.mapWriteError(error)
        }
        return photoRef
    }

    // MARK: - Read

    /// Reads the bytes of the blob identified by `photoRef`.
    ///
    /// - Parameter photoRef: The reference returned by ``write(_:)`` (the value
    ///   stored on the row).
    /// - Returns: The stored photo bytes.
    /// - Throws: `BlobStoreError.notFound` if no such blob exists,
    ///   `BlobStoreError.invalidReference` if the reference escapes the store,
    ///   otherwise `BlobStoreError.io`.
    public func read(photoRef: String) throws -> Data {
        let url = try resolve(photoRef)
        guard fileManager.fileExists(atPath: url.path) else {
            throw BlobStoreError.notFound(photoRef: photoRef)
        }
        do {
            return try Data(contentsOf: url)
        } catch {
            throw BlobStoreError.io(reason: String(describing: error))
        }
    }

    /// Returns the absolute URL of the blob identified by `photoRef`.
    ///
    /// The sync engine uses this to stream the file as a multipart body without
    /// loading it into memory. Does not check for existence.
    ///
    /// - Parameter photoRef: The stored reference.
    /// - Returns: The absolute file URL of the blob.
    /// - Throws: `BlobStoreError.invalidReference` if the reference escapes the
    ///   store.
    public func url(forPhotoRef photoRef: String) throws -> URL {
        try resolve(photoRef)
    }

    /// Whether a blob exists for `photoRef`.
    public func exists(photoRef: String) -> Bool {
        guard let url = try? resolve(photoRef) else { return false }
        return fileManager.fileExists(atPath: url.path)
    }

    // MARK: - Delete

    /// Deletes the blob identified by `photoRef`.
    ///
    /// Deleting a reference that does not exist is a no-op (idempotent), so this
    /// is safe to call during rollback cleanup regardless of whether the write
    /// actually landed.
    ///
    /// - Parameter photoRef: The reference to delete.
    /// - Throws: `BlobStoreError.invalidReference` if the reference escapes the
    ///   store, otherwise `BlobStoreError.io`.
    public func delete(photoRef: String) throws {
        let url = try resolve(photoRef)
        guard fileManager.fileExists(atPath: url.path) else { return }
        do {
            try fileManager.removeItem(at: url)
        } catch {
            throw BlobStoreError.io(reason: String(describing: error))
        }
    }

    // MARK: - Garbage collection

    /// Removes orphaned blob files — those on disk whose `photoRef` is not in
    /// `referencedRefs` — and returns the references that were reclaimed.
    ///
    /// Run this on launch, after the database is open, passing the set of
    /// `photoRef`s still referenced by persisted escalation rows. It reclaims
    /// blobs left behind when the process crashed after ``write(_:)`` but before
    /// the referencing row was committed (Req 7.3/7.4).
    ///
    /// The operation is best-effort and resilient: a failure to remove any one
    /// file does not abort the sweep, and an empty store returns no removals.
    /// Because the invariant is that the row is always written *after* the blob,
    /// a blob missing from `referencedRefs` can only be an orphan — never a
    /// file that a not-yet-committed row depends on.
    ///
    /// - Parameter referencedRefs: `photoRef`s still referenced by live rows.
    /// - Returns: The `photoRef`s that were removed.
    /// - Throws: `BlobStoreError.io` only if the blob directory itself cannot be
    ///   enumerated.
    @discardableResult
    public func garbageCollect(referencedRefs: Set<String>) throws -> [String] {
        let contents: [URL]
        do {
            contents = try fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.isRegularFileKey],
                options: [.skipsHiddenFiles]
            )
        } catch {
            throw BlobStoreError.io(reason: String(describing: error))
        }

        var removed: [String] = []
        for fileURL in contents {
            // Skip anything that is not a regular file (e.g. a stray directory).
            let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey])
            if values?.isRegularFile == false { continue }

            let ref = fileURL.lastPathComponent
            guard !referencedRefs.contains(ref) else { continue }

            // Best-effort: keep sweeping even if one removal fails.
            if (try? fileManager.removeItem(at: fileURL)) != nil {
                removed.append(ref)
            }
        }
        return removed
    }

    // MARK: - Private helpers

    private func createDirectoryIfNeeded() throws {
        var isDirectory: ObjCBool = false
        if fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory) {
            if isDirectory.boolValue { return }
            throw BlobStoreError.io(
                reason: "Blob directory path is occupied by a file: \(directory.path)"
            )
        }
        do {
            try fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
        } catch {
            throw Self.mapWriteError(error)
        }
    }

    /// Resolves a `photoRef` to an absolute URL inside the blob directory,
    /// rejecting any reference that would escape the store (path traversal,
    /// nested directories, or an empty name).
    private func resolve(_ photoRef: String) throws -> URL {
        guard !photoRef.isEmpty,
              !photoRef.contains("/"),
              !photoRef.contains("\\"),
              photoRef != ".",
              photoRef != ".."
        else {
            throw BlobStoreError.invalidReference(photoRef: photoRef)
        }

        let url = directory.appendingPathComponent(photoRef, isDirectory: false)

        // Defense in depth: confirm the resolved file is a direct child of the
        // blob directory after standardization.
        let standardizedParent = url.deletingLastPathComponent().standardizedFileURL
        guard standardizedParent == directory.standardizedFileURL else {
            throw BlobStoreError.invalidReference(photoRef: photoRef)
        }
        return url
    }

    /// Maps a Cocoa/POSIX write error onto a `BlobStoreError`, distinguishing
    /// the out-of-space case so the repository can surface `storageFull`.
    private static func mapWriteError(_ error: Error) -> BlobStoreError {
        let nsError = error as NSError
        if nsError.domain == NSCocoaErrorDomain,
           nsError.code == CocoaError.fileWriteOutOfSpace.rawValue {
            return .outOfSpace
        }
        if nsError.domain == NSPOSIXErrorDomain,
           nsError.code == Int(ENOSPC) {
            return .outOfSpace
        }
        // Some write failures wrap the POSIX error in `userInfo` under an
        // underlying error; check that too.
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError,
           underlying.domain == NSPOSIXErrorDomain,
           underlying.code == Int(ENOSPC) {
            return .outOfSpace
        }
        return .io(reason: String(describing: error))
    }
}
