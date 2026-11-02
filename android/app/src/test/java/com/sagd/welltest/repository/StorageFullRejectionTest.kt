/*
 * Unit test — storage-full rejection (Android) — task 6.6.
 *
 * Requirements: 1.5
 *
 * Req 1.5: "IF available device storage is insufficient to persist a record,
 * THEN THE App SHALL reject the save operation, retain the Operator's entered
 * data in the active entry form, and display an error message indicating that
 * storage is full."
 *
 * The Android repositories enforce this with a pre-write free-space check
 * driven by an injectable [StorageGuard] (see StorageGuard.kt,
 * LogRepositoryImpl, EscalationRepositoryImpl). Rather than filling the real
 * disk, this test injects a guard that reports space *below* the required
 * threshold and asserts the save:
 *
 *   * returns [SaveResult.StorageFull] (so the UI shows the dedicated
 *     storage-full message and retains the in-form data — the "retain entered
 *     data" obligation is met by the save returning without consuming the
 *     operator's values); and
 *   * writes nothing to the store — the fake DAO's table stays empty, so no
 *     partial row is left behind; and
 *   * for an escalation additionally writes no photo blob — the blob directory
 *     stays empty — confirming the free-space check happens *before* the
 *     blob-first write path.
 *
 * Boundary. The log repositories call `storageGuard.hasFreeSpace()` with the
 * default headroom ([StorageGuard.DEFAULT_MIN_FREE_BYTES]); the escalation
 * repository calls it with `photo.size + DEFAULT_MIN_FREE_BYTES`. The
 * production guard ([FileSystemStorageGuard]) decides with
 * `availableBytes >= requiredBytes`. The "insufficient storage" cases drive a
 * guard reporting one byte *below* that required amount (so it answers false);
 * the companion "exactly at the threshold" cases report *exactly* the required
 * amount (so it answers true), where the save must instead *succeed* and
 * persist the row (and, for an escalation, the blob). Pinning both sides of the
 * `>=` comparison catches a regression that flips the boundary from `>=` to
 * `>`. This mirrors the iOS StorageFullRejectionTests.
 *
 * -- Why fakes, not a real Room DB --
 *
 * An in-memory Room database cannot be opened in a pure-JVM unit test (Room's
 * in-memory builder still needs an Android `Context` and the native SQLite
 * library, available only under Robolectric or on-device instrumentation;
 * neither is present here). So, exactly like the 6.3/6.4 property tests, this
 * test wires the REAL [LogRepositoryImpl] / [EscalationRepositoryImpl] over:
 *
 *   * map-backed fake DAOs implementing the production DAO interfaces, and
 *   * a [FakeTransactionRunner] honouring Room's withTransaction contract.
 *
 * The photo [BlobStore] is plain `java.io`, so a REAL BlobStore runs over a
 * throwaway temp directory. Nothing in the repositories themselves is stubbed —
 * only the persistence substrate. The guard is the sole injected collaborator
 * whose answer is controlled, which is the seam Req 1.5 is about.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class StorageFullRejectionTest : StringSpec({

    // -----------------------------------------------------------------------
    // Insufficient storage rejects the save and writes nothing
    // -----------------------------------------------------------------------

    "saveWellTest is rejected with StorageFull when storage is insufficient" {
        val store = FakeStore()
        val repo = LogRepositoryImpl(
            transactionRunner = FakeTransactionRunner(),
            wellheadLogDao = store.wellheadLogDao,
            wellTestDao = store.wellTestDao,
            // One byte below the row headroom the log path requests.
            storageGuard = capacityGuard(StorageGuard.DEFAULT_MIN_FREE_BYTES - 1),
        )

        val result = repo.saveWellTest(makeWellTest())

        result shouldBe SaveResult.StorageFull
        store.wellTestDao.count() shouldBe 0 // nothing persisted
    }

    "saveWellheadLog is rejected with StorageFull when storage is insufficient" {
        val store = FakeStore()
        val repo = LogRepositoryImpl(
            transactionRunner = FakeTransactionRunner(),
            wellheadLogDao = store.wellheadLogDao,
            wellTestDao = store.wellTestDao,
            storageGuard = capacityGuard(StorageGuard.DEFAULT_MIN_FREE_BYTES - 1),
        )

        val result = repo.saveWellheadLog(makeProductionLog())

        result shouldBe SaveResult.StorageFull
        store.wellheadLogDao.count() shouldBe 0 // nothing persisted
    }

    "saveEscalation is rejected with StorageFull and writes no row or blob when storage is insufficient" {
        val store = FakeStore()
        withTempBlobStore { blobStore, blobDir ->
            // The escalation path requires photo bytes + row headroom; one byte
            // short must reject before the blob-first write runs.
            val required = PHOTO.size.toLong() + StorageGuard.DEFAULT_MIN_FREE_BYTES
            val repo = EscalationRepositoryImpl(
                transactionRunner = FakeTransactionRunner(),
                escalationDao = store.escalationDao,
                blobStore = blobStore,
                storageGuard = capacityGuard(required - 1),
            )

            val result = repo.saveEscalation(makeEscalation(), PHOTO)

            result shouldBe SaveResult.StorageFull
            store.escalationDao.count() shouldBe 0 // no row persisted
            blobFileCount(blobDir) shouldBe 0 // no photo blob written
        }
    }

    // -----------------------------------------------------------------------
    // Boundary: exactly at the threshold the save succeeds
    // -----------------------------------------------------------------------

    "saveWellTest succeeds and persists the row with free space exactly at the threshold" {
        val store = FakeStore()
        val repo = LogRepositoryImpl(
            transactionRunner = FakeTransactionRunner(),
            wellheadLogDao = store.wellheadLogDao,
            wellTestDao = store.wellTestDao,
            // Exactly the required headroom: availableBytes >= requiredBytes holds.
            storageGuard = capacityGuard(StorageGuard.DEFAULT_MIN_FREE_BYTES),
        )

        val result = repo.saveWellTest(makeWellTest())

        result shouldBe SaveResult.Success
        store.wellTestDao.count() shouldBe 1 // persisted at the boundary
    }

    "saveWellheadLog succeeds and persists the row with free space exactly at the threshold" {
        val store = FakeStore()
        val repo = LogRepositoryImpl(
            transactionRunner = FakeTransactionRunner(),
            wellheadLogDao = store.wellheadLogDao,
            wellTestDao = store.wellTestDao,
            storageGuard = capacityGuard(StorageGuard.DEFAULT_MIN_FREE_BYTES),
        )

        val result = repo.saveWellheadLog(makeProductionLog())

        result shouldBe SaveResult.Success
        store.wellheadLogDao.count() shouldBe 1 // persisted at the boundary
    }

    "saveEscalation succeeds and persists row and blob with free space exactly at the threshold" {
        val store = FakeStore()
        withTempBlobStore { blobStore, blobDir ->
            val required = PHOTO.size.toLong() + StorageGuard.DEFAULT_MIN_FREE_BYTES
            val repo = EscalationRepositoryImpl(
                transactionRunner = FakeTransactionRunner(),
                escalationDao = store.escalationDao,
                blobStore = blobStore,
                storageGuard = capacityGuard(required),
            )

            val result = repo.saveEscalation(makeEscalation(), PHOTO)

            result shouldBe SaveResult.Success
            store.escalationDao.count() shouldBe 1 // row persisted at the boundary
            blobFileCount(blobDir) shouldBe 1 // photo blob written at the boundary
        }
    }
})

// ---------------------------------------------------------------------------
// Controllable storage guard
// ---------------------------------------------------------------------------

/**
 * A [StorageGuard] reporting a fixed [available] capacity, deciding with the
 * same `availableBytes >= requiredBytes` rule as the production
 * [FileSystemStorageGuard]. This lets the repositories' pre-write free-space
 * check be driven deterministically without touching the real filesystem, and
 * — because it honours the `>=` comparison against the exact `requiredBytes`
 * each save asks for — it pins both sides of the storage-full boundary.
 */
private fun capacityGuard(available: Long): StorageGuard =
    object : StorageGuard {
        override fun hasFreeSpace(requiredBytes: Long): Boolean = available >= requiredBytes
    }

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

private const val WELL_ID = "WELL-1"

private val CAPTURED_AT: Instant = Instant.ofEpochSecond(1_700_000_000L)

/** A small non-empty photo payload for escalation saves. */
private val PHOTO: ByteArray = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

private fun syncMeta() =
    SyncMetadata(syncState = SyncState.PENDING, retryCount = 0, createdAt = CAPTURED_AT)

private fun makeWellTest(): WellTest =
    WellTest(
        id = UUID.randomUUID(),
        wellId = WELL_ID,
        oilRatio = 0.5,
        waterRatio = 0.3,
        gasRatio = 0.2,
        capturedAt = CAPTURED_AT,
        sync = syncMeta(),
    )

private fun makeProductionLog(): WellheadLog =
    WellheadLog(
        id = UUID.randomUUID(),
        wellId = WELL_ID,
        logType = LogType.PRODUCTION,
        capturedAt = CAPTURED_AT,
        emulsionFlowRate = 12.5,
        casingGasPressure = 340.0,
        espMotorTemperature = 88.0,
        waterCutPercent = 42.0,
        sync = syncMeta(),
    )

private fun makeEscalation(): Escalation =
    Escalation(
        id = UUID.randomUUID(),
        wellId = WELL_ID,
        priority = EscalationPriority.HIGH,
        photoRef = "placeholder", // overwritten by the blob store on save
        latitude = 56.7,
        longitude = -111.4,
        locationUnavailable = false,
        capturedAt = CAPTURED_AT,
        sync = syncMeta(),
    )

// ---------------------------------------------------------------------------
// Temp blob store helper
// ---------------------------------------------------------------------------

/**
 * Runs [block] with a REAL [BlobStore] over a throwaway temp directory, then
 * removes the directory. The directory is the store's *base* dir; the store
 * confines blobs to a `blobs/` subdirectory under it (see [blobFileCount]).
 */
private suspend fun withTempBlobStore(block: suspend (BlobStore, File) -> Unit) {
    val baseDir = Files.createTempDirectory("sagd-storage-full-blobs").toFile()
    try {
        block(BlobStore(baseDir), baseDir)
    } finally {
        baseDir.deleteRecursively()
    }
}

/**
 * Number of finished blob files under the store's `blobs/` subdirectory,
 * ignoring any in-progress `tmp-` temp files (there should be none after a
 * completed or rejected save).
 */
private fun blobFileCount(baseDir: File): Int {
    val blobsDir = File(baseDir, "blobs")
    val entries = blobsDir.listFiles() ?: return 0
    return entries.count { it.isFile && !it.name.startsWith("tmp-") }
}

// ---------------------------------------------------------------------------
// Transaction runner — commit on success, roll back (propagate) on throw
// ---------------------------------------------------------------------------

/**
 * A [TransactionRunner] honouring Room's `withTransaction` contract. The fake
 * DAOs write straight into their maps, so for the success paths here the runner
 * just runs the body; on a throw it propagates (there is no throw in these
 * cases because the guard short-circuits or the save succeeds).
 */
private class FakeTransactionRunner : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
}

// ---------------------------------------------------------------------------
// Map-backed fake DAOs (no network, pure in-memory local store)
// ---------------------------------------------------------------------------
//
// Implement the production DAO interfaces so the real repositories talk to them
// exactly as they would Room. Only the methods the save paths use are
// meaningful; the rest satisfy the interface and are unused here.

private class FakeStore {
    val wellTestDao = FakeWellTestDao()
    val wellheadLogDao = FakeWellheadLogDao()
    val escalationDao = FakeEscalationDao()
}

private class FakeWellTestDao : WellTestDao {
    private val rows = linkedMapOf<UUID, WellTestEntity>()
    fun count(): Int = rows.size
    override suspend fun upsert(test: WellTestEntity) { rows[test.id] = test }
    override suspend fun getById(id: UUID): WellTestEntity? = rows[id]
    override suspend fun getForWell(wellId: String): List<WellTestEntity> =
        rows.values.filter { it.wellId == wellId }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        rows.values.filter { it.sync.syncState == pending }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun update(test: WellTestEntity) { rows[test.id] = test }
}

private class FakeWellheadLogDao : WellheadLogDao {
    private val rows = linkedMapOf<UUID, WellheadLogEntity>()
    fun count(): Int = rows.size
    override suspend fun upsert(log: WellheadLogEntity) { rows[log.id] = log }
    override suspend fun getById(id: UUID): WellheadLogEntity? = rows[id]
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> =
        rows.values.filter { it.wellId == wellId }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        rows.values.filter { it.sync.syncState == pending }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun update(log: WellheadLogEntity) { rows[log.id] = log }
}

private class FakeEscalationDao : EscalationDao {
    private val rows = linkedMapOf<UUID, EscalationEntity>()
    fun count(): Int = rows.size
    override suspend fun upsert(escalation: EscalationEntity) { rows[escalation.id] = escalation }
    override suspend fun getById(id: UUID): EscalationEntity? = rows[id]
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        rows.values.filter { it.sync.syncState == pending }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun allPhotoRefs(): List<String> = rows.values.map { it.photoRef }
    override suspend fun update(escalation: EscalationEntity) { rows[escalation.id] = escalation }
    override suspend fun delete(escalation: EscalationEntity) { rows.remove(escalation.id) }
}
