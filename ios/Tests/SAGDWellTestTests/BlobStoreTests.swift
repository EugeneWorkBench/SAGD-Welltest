//
//  BlobStoreTests.swift
//  Unit tests for the photo blob file store (task 3.2).
//
//  Exercises the core behaviors of `BlobStore`: write/read round-trip, the
//  delete contract (idempotent), reference validation, and orphan garbage
//  collection. Each test runs against a fresh temporary directory so no state
//  leaks between cases.
//

import XCTest
@testable import SAGDWellTest

final class BlobStoreTests: XCTestCase {

    private var tempDirectory: URL!
    private var store: BlobStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BlobStoreTests-\(UUID().uuidString)", isDirectory: true)
        store = try BlobStore(directory: tempDirectory)
    }

    override func tearDownWithError() throws {
        if let tempDirectory, FileManager.default.fileExists(atPath: tempDirectory.path) {
            try? FileManager.default.removeItem(at: tempDirectory)
        }
        store = nil
        tempDirectory = nil
        try super.tearDownWithError()
    }

    // MARK: - Construction

    func testInitCreatesDirectory() throws {
        var isDirectory: ObjCBool = false
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: tempDirectory.path, isDirectory: &isDirectory)
        )
        XCTAssertTrue(isDirectory.boolValue)
    }

    // MARK: - Write / read

    func testWriteThenReadRoundTrips() throws {
        let bytes = Data([0x01, 0x02, 0x03, 0xFF, 0x00, 0x42])
        let ref = try store.write(bytes)

        XCTAssertTrue(store.exists(photoRef: ref))
        XCTAssertEqual(try store.read(photoRef: ref), bytes)
    }

    func testWriteProducesUniqueReferences() throws {
        let a = try store.write(Data([0x01]))
        let b = try store.write(Data([0x01]))
        XCTAssertNotEqual(a, b)
        // Both blobs coexist — a write never overwrites an existing blob.
        XCTAssertTrue(store.exists(photoRef: a))
        XCTAssertTrue(store.exists(photoRef: b))
    }

    func testReadMissingReferenceThrowsNotFound() throws {
        XCTAssertThrowsError(try store.read(photoRef: "does-not-exist.blob")) { error in
            XCTAssertEqual(error as? BlobStoreError, .notFound(photoRef: "does-not-exist.blob"))
        }
    }

    func testUrlForPhotoRefIsInsideBlobDirectory() throws {
        let ref = try store.write(Data([0x09]))
        let url = try store.url(forPhotoRef: ref)
        XCTAssertEqual(url.deletingLastPathComponent().standardizedFileURL,
                       tempDirectory.standardizedFileURL)
    }

    // MARK: - Delete

    func testDeleteRemovesBlob() throws {
        let ref = try store.write(Data([0x07]))
        XCTAssertTrue(store.exists(photoRef: ref))

        try store.delete(photoRef: ref)
        XCTAssertFalse(store.exists(photoRef: ref))
    }

    func testDeleteMissingReferenceIsNoOp() throws {
        // Idempotent: deleting a reference that never landed must not throw,
        // so rollback cleanup is safe regardless of whether the write happened.
        XCTAssertNoThrow(try store.delete(photoRef: "never-written.blob"))
    }

    // MARK: - Reference validation (path traversal)

    func testPathTraversalReferenceIsRejected() throws {
        for bad in ["../escape.blob", "nested/child.blob", "a\\b.blob", "", ".", ".."] {
            XCTAssertThrowsError(try store.read(photoRef: bad), "expected rejection for \(bad)") { error in
                XCTAssertEqual(error as? BlobStoreError, .invalidReference(photoRef: bad))
            }
        }
    }

    // MARK: - Garbage collection

    func testGarbageCollectRemovesOrphansAndKeepsReferenced() throws {
        let referenced = try store.write(Data([0xAA]))
        let orphanOne = try store.write(Data([0xBB]))
        let orphanTwo = try store.write(Data([0xCC]))

        let removed = try store.garbageCollect(referencedRefs: [referenced])

        XCTAssertEqual(Set(removed), [orphanOne, orphanTwo])
        XCTAssertTrue(store.exists(photoRef: referenced))
        XCTAssertFalse(store.exists(photoRef: orphanOne))
        XCTAssertFalse(store.exists(photoRef: orphanTwo))
    }

    func testGarbageCollectWithNoOrphansRemovesNothing() throws {
        let a = try store.write(Data([0x01]))
        let b = try store.write(Data([0x02]))

        let removed = try store.garbageCollect(referencedRefs: [a, b])

        XCTAssertTrue(removed.isEmpty)
        XCTAssertTrue(store.exists(photoRef: a))
        XCTAssertTrue(store.exists(photoRef: b))
    }

    func testGarbageCollectOnEmptyStoreReturnsNothing() throws {
        let removed = try store.garbageCollect(referencedRefs: ["unused.blob"])
        XCTAssertTrue(removed.isEmpty)
    }
}
