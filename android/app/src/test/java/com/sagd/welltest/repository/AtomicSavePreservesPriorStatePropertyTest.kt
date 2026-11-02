/*
 * Property 3: Atomic save — failure preserves prior state (Android) — task 6.4.
 *
 * Validates: Requirements 1.4, 7.4
 *
 * Design "Property 3: Atomic save — failure preserves prior state":
 *
 *     For any save that encounters an injected write failure, the save returns
 *     SaveResult.Failure (never Success, and not reclassified as StorageFull for
 *     an integrity/I/O error), the local store's contents are exactly what they
 *     were immediately before the attempt — no partial row is committed (Req 1.4)
 *     — and for the escalation path no orphan photo blob is left behind in the
 *     blob store (Req 7.4).
 *
 * --- How the failure is injected, and why fakes (not a real Room DB) ---
 *
 * An in-memory Room database cannot be opened in a pure-JVM unit test: Room's
 * in-memory builder still needs an Android `Context` and the native SQLite
 * library, which exist only under instrumentation or Robolectric. Neither is
 * available here (no JDK/Gradle in this environment, and the unit-test source
 * set is plain JVM). The task guidance therefore directs the failure to be
 * injected through fakes, which this test does at the two real seams the
 * repositories expose:
 *
 *   * Transaction boundary. The repositories were given a small [TransactionRunner]
 *     seam whose production implementation is a thin pass-through to Room's
 *     `withTransaction`. This test supplies [FakeTransactionRunner], which honours
 *     the exact contract the atomic-save property depends on: it begins a
 *     transaction over the in-memory fake store, runs the body, COMMITS on normal
 *     return, and ROLLS BACK (discarding every write the body made) while
 *     propagating the throwable on failure — precisely Room's commit-on-success /
 *     rollback-on-throw semantics.
 *
 *   * DAO writes. The DAOs are interfaces, so [FakeWellTestDao],
 *     [FakeWellheadLogDao] and [FakeEscalationDao] back the real repository save
 *     code with an in-memory, transaction-aware store. A DAO can be configured to
 *     THROW on `upsert` — modelling SQLite raising a constraint / disk error
 *     mid-write — so the repository's catch runs against a genuine failure, and
 *     the fake runner rolls the buffered write back exactly as Room would.
 *
 *   * Blob store. [BlobStore] is plain `java.io` (no Android types), so this test
 *     uses the REAL BlobStore over a temp directory. Two escalation failure modes
 *     are exercised:
 *       - Blob-write failure (the task's "BlobStore pointed at an un-writable
 *         path"): a real BlobStore whose `blobs/` directory is deleted after
 *         construction, so `BlobStore.write` throws an IOException before any row
 *         is attempted. Nothing reaches either store, so there is trivially no
 *         orphan blob.
 *       - Row-insert failure after a successful blob write: a real BlobStore over
 *         the watched temp dir, with the escalation DAO configured to throw on
 *         upsert. The blob is written first, then the row insert throws, so the
 *         repository MUST delete the just-written blob (Req 7.4). The harness
 *         asserts the blob directory ends up byte-for-byte as it started — no
 *         orphan left behind.
 *
 * None of this stubs the repository behaviour itself: the real
 * [LogRepositoryImpl] / [EscalationRepositoryImpl] save methods run, including
 * the pre-write storage check, the normalize-for-save step, the blob-first
 * ordering, the transaction, and the catch/rollback/orphan-delete logic. Only
 * the persistence substrate (transaction + DAOs + blob directory) is faked.
 *
 * --- What "store unchanged" is checked against ---
 *
 * Before each attempt the harness seeds NON-TRIVIAL prior state through the real
 * repositories: one already-saved record of each syncable kind (well test,
 * wellhead log, escalation) plus a pre-existing blob on disk. It snapshots the
 * three tables (as sorted row lists) and the set of blob file names, drives the
 * chosen save into its failure branch, snapshots again, and asserts the two
 * snapshots are equal — proving the failed save neither added a partial row nor
 * disturbed existing rows or blobs.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 3: Atomic save — failure preserves
 * prior state`, mirroring the iOS AtomicSavePreservesPriorStatePropertyTests.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class AtomicSavePreservesPriorStatePropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 3: Atomic save — failure preserves prior state" {
        checkAll(pbtConfig(), failureCaseArb) { testCase ->
            // Each generated case gets its own isolated store + temp blob dir.
            val store = FakeStore()
            val runner = FakeTransactionRunner(store)
            val blobDir = Files.createTempDirectory("sagd-prop3-blobs").toFile()
            try {
                atomicSaveFailurePreservesPriorState(testCase, store, runner, blobDir)
            } finally {
                blobDir.deleteRecursively()
            }
        }
    }
})

// ---------------------------------------------------------------------------
// The property check for one generated case
// ---------------------------------------------------------------------------

/**
 * Seeds prior state through the real repositories, snapshots the store + blob
 * dir, drives the chosen save path into its injected-failure branch, and
 * asserts: the result is [SaveResult.Failure], the store is unchanged, and (for
 * the escalation paths) no orphan blob remains.
 */
private suspend fun atomicSaveFailurePreservesPriorState(
    testCase: FailureCase,
    store: FakeStore,
    runner: FakeTransactionRunner,
    blobDir: File,
) {
    // A real BlobStore over the watched temp dir, used BOTH to seed a
    // pre-existing blob and (for the row-FK escalation path) to write the blob
    // the repository must later delete on rollback.
    val liveBlobStore = BlobStore(blobDir)
    val ampleStorage = AmpleStorageGuard

    // --- Establish non-trivial prior state the failed save must not touch. ---
    val logRepo = LogRepositoryImpl(
        transactionRunner = runner,
        wellheadLogDao = store.wellheadLogDao,
        wellTestDao = store.wellTestDao,
        storageGuard = ampleStorage,
    )
    val escalationRepo = EscalationRepositoryImpl(
        transactionRunner = runner,
        escalationDao = store.escalationDao,
        blobStore = liveBlobStore,
        storageGuard = ampleStorage,
    )

    seedPriorState(testCase.priorState, logRepo, escalationRepo)

    val before = store.snapshot(blobDir)

    // --- Attempt the save engineered to fail. ---
    val result = attemptFailingSave(testCase, store, runner, blobDir, liveBlobStore)

    val after = store.snapshot(blobDir)

    withClue("case=${testCase.label}") {
        // 1. The save must report Failure — never Success, and never silently
        //    reclassified as StorageFull (the injected error is an integrity /
        //    I/O error, not disk-full).
        result.shouldBeInstanceOf<SaveResult.Failure>()

        // 2. Every table must be exactly as it was before the attempt: no
        //    partial row added, no existing row disturbed (Req 1.4).
        withClue("store rows changed (before=${before.rowCounts}, after=${after.rowCounts})") {
            after.wellTests shouldContainExactly before.wellTests
            after.wellheadLogs shouldContainExactly before.wellheadLogs
            after.escalations shouldContainExactly before.escalations
        }

        // 3. The blob set must be exactly as it was before — no orphan left and
        //    nothing pre-existing removed (Req 7.4).
        withClue("blob set changed (before=${before.blobRefs}, after=${after.blobRefs})") {
            after.blobRefs shouldBe before.blobRefs
        }
    }
}

// ---------------------------------------------------------------------------
// Driving each save path into failure
// ---------------------------------------------------------------------------

private suspend fun attemptFailingSave(
    testCase: FailureCase,
    store: FakeStore,
    runner: FakeTransactionRunner,
    blobDir: File,
    liveBlobStore: BlobStore,
): SaveResult = when (val injection = testCase.injection) {
    is Injection.WellTestWriteFailure -> {
        // The well-test DAO throws on upsert; the fake runner rolls the
        // buffered write back and the repository maps the throw to Failure.
        store.wellTestDao.failOnUpsert = true
        LogRepositoryImpl(runner, store.wellheadLogDao, store.wellTestDao, AmpleStorageGuard)
            .saveWellTest(injection.test)
    }

    is Injection.WellheadLogWriteFailure -> {
        store.wellheadLogDao.failOnUpsert = true
        LogRepositoryImpl(runner, store.wellheadLogDao, store.wellTestDao, AmpleStorageGuard)
            .saveWellheadLog(injection.log)
    }

    is Injection.EscalationBlobWriteFailure -> {
        // A real BlobStore whose blobs/ directory is removed after construction,
        // so BlobStore.write throws before any row is attempted (the task's
        // "BlobStore pointed at an un-writable path"). The watched dir is left
        // untouched, so the seeded blob survives and no orphan can appear.
        val broken = makeBlobStoreThatFailsWrites()
        try {
            EscalationRepositoryImpl(runner, store.escalationDao, broken.store, AmpleStorageGuard)
                .saveEscalation(injection.escalation, injection.photo)
        } finally {
            broken.baseDir.deleteRecursively()
        }
    }

    is Injection.EscalationRowWriteFailure -> {
        // Real BlobStore over the watched dir: the blob writes successfully,
        // then the escalation DAO throws on upsert, so the repository must
        // delete the just-written blob (Req 7.4).
        store.escalationDao.failOnUpsert = true
        EscalationRepositoryImpl(runner, store.escalationDao, liveBlobStore, AmpleStorageGuard)
            .saveEscalation(injection.escalation, injection.photo)
    }
}

/** A real [BlobStore] whose write path is guaranteed to fail, plus its base dir. */
private class BrokenBlobStore(val store: BlobStore, val baseDir: File)

/**
 * Builds a real [BlobStore] whose write path is guaranteed to fail: the store
 * is created over a fresh temp base dir (so `init` succeeds and creates
 * `blobs/`), then `blobs/` is deleted and replaced by a regular file, so every
 * `FileOutputStream` into a child path throws an IOException — the directory
 * component is now a file. Mirrors the iOS `makeBlobStoreThatFailsWrites`.
 */
private fun makeBlobStoreThatFailsWrites(): BrokenBlobStore {
    val baseDir = Files.createTempDirectory("sagd-prop3-broken").toFile()
    val store = BlobStore(baseDir) // creates <baseDir>/blobs
    val blobsDir = File(baseDir, "blobs")
    blobsDir.deleteRecursively()
    // Put a regular file where the blobs directory used to be, so any write into
    // <blobs>/<ref> fails because a path component is a file, not a directory.
    blobsDir.writeBytes(byteArrayOf(0))
    return BrokenBlobStore(store, baseDir)
}

// ---------------------------------------------------------------------------
// Seeding prior state through the real repositories
// ---------------------------------------------------------------------------

private suspend fun seedPriorState(
    prior: PriorState,
    logRepo: LogRepositoryImpl,
    escalationRepo: EscalationRepositoryImpl,
) {
    check(logRepo.saveWellTest(prior.wellTest) is SaveResult.Success) {
        "seed well test must succeed"
    }
    check(logRepo.saveWellheadLog(prior.wellheadLog) is SaveResult.Success) {
        "seed wellhead log must succeed"
    }
    check(escalationRepo.saveEscalation(prior.escalation, prior.escalationPhoto) is SaveResult.Success) {
        "seed escalation must succeed"
    }
}

// ---------------------------------------------------------------------------
// In-memory, transaction-aware fake store + DAOs
// ---------------------------------------------------------------------------

/**
 * The persistence substrate standing in for Room. Holds one map per table and a
 * transaction buffer so [FakeTransactionRunner] can begin/commit/rollback.
 * Inside a transaction every DAO write is staged in the buffer and only merged
 * into the committed maps on commit; a rollback drops the buffer, so a failed
 * write leaves the committed state untouched exactly like Room.
 */
private class FakeStore {
    // Committed state.
    private val wellTests = linkedMapOf<UUID, WellTestEntity>()
    private val wellheadLogs = linkedMapOf<UUID, WellheadLogEntity>()
    private val escalations = linkedMapOf<UUID, EscalationEntity>()

    // Active transaction staging (null when no transaction is open). A staged
    // write records the new row; on commit the staged rows overwrite committed
    // state, on rollback they are dropped.
    private var txWellTests: MutableMap<UUID, WellTestEntity>? = null
    private var txWellheadLogs: MutableMap<UUID, WellheadLogEntity>? = null
    private var txEscalations: MutableMap<UUID, EscalationEntity>? = null

    val wellTestDao = FakeWellTestDao(this)
    val wellheadLogDao = FakeWellheadLogDao(this)
    val escalationDao = FakeEscalationDao(this)

    fun begin() {
        txWellTests = linkedMapOf()
        txWellheadLogs = linkedMapOf()
        txEscalations = linkedMapOf()
    }

    fun commit() {
        txWellTests?.let { wellTests.putAll(it) }
        txWellheadLogs?.let { wellheadLogs.putAll(it) }
        txEscalations?.let { escalations.putAll(it) }
        clearTx()
    }

    fun rollback() = clearTx()

    private fun clearTx() {
        txWellTests = null
        txWellheadLogs = null
        txEscalations = null
    }

    fun putWellTest(e: WellTestEntity) {
        (txWellTests ?: wellTests)[e.id] = e
    }

    fun putWellheadLog(e: WellheadLogEntity) {
        (txWellheadLogs ?: wellheadLogs)[e.id] = e
    }

    fun putEscalation(e: EscalationEntity) {
        (txEscalations ?: escalations)[e.id] = e
    }

    /** A comparable snapshot of every committed table plus the on-disk blob set. */
    fun snapshot(blobDir: File): StoreSnapshot = StoreSnapshot(
        wellTests = wellTests.values.sortedBy { it.id },
        wellheadLogs = wellheadLogs.values.sortedBy { it.id },
        escalations = escalations.values.sortedBy { it.id },
        blobRefs = blobRefsOnDisk(blobDir),
    )

    private fun blobRefsOnDisk(blobDir: File): Set<String> {
        val blobsDir = File(blobDir, "blobs")
        val entries = blobsDir.listFiles() ?: return emptySet()
        return entries.filter { it.isFile }.map { it.name }.toSet()
    }
}

/** Immutable snapshot used for the before/after equality assertions. */
private class StoreSnapshot(
    val wellTests: List<WellTestEntity>,
    val wellheadLogs: List<WellheadLogEntity>,
    val escalations: List<EscalationEntity>,
    val blobRefs: Set<String>,
) {
    val rowCounts: String
        get() = "wellTest=${wellTests.size}, wellheadLog=${wellheadLogs.size}, " +
            "escalation=${escalations.size}"
}

/**
 * Transaction runner over a [FakeStore] honouring Room's contract: begin, run
 * the body, COMMIT on normal return, and ROLLBACK (discarding writes) while
 * propagating the throwable on failure. Non-reentrant, which is all the
 * single-save repository paths need.
 */
private class FakeTransactionRunner(private val store: FakeStore) : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R {
        store.begin()
        return try {
            val result = block()
            store.commit()
            result
        } catch (t: Throwable) {
            store.rollback()
            throw t
        }
    }
}

// --- Fake DAOs: only the methods the repositories call are meaningful; the
//     rest satisfy the interface and are unused by this property. ---

private class FakeWellTestDao(private val store: FakeStore) : WellTestDao {
    /** When true, [upsert] throws to model a mid-write constraint / I/O error. */
    var failOnUpsert: Boolean = false

    override suspend fun upsert(test: WellTestEntity) {
        if (failOnUpsert) throw FakeWriteException("wellTest upsert failed")
        store.putWellTest(test)
    }

    override suspend fun getById(id: UUID): WellTestEntity? = unsupported()
    override suspend fun getForWell(wellId: String): List<WellTestEntity> = unsupported()
    override suspend fun nextPending(
        limit: Int,
        pending: com.sagd.welltest.domain.SyncState,
    ): List<WellTestEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<com.sagd.welltest.domain.SyncState?> = flowOf(null)
    override suspend fun update(test: WellTestEntity) = unsupported()
}

private class FakeWellheadLogDao(private val store: FakeStore) : WellheadLogDao {
    var failOnUpsert: Boolean = false

    override suspend fun upsert(log: WellheadLogEntity) {
        if (failOnUpsert) throw FakeWriteException("wellheadLog upsert failed")
        store.putWellheadLog(log)
    }

    override suspend fun getById(id: UUID): WellheadLogEntity? = unsupported()
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> = unsupported()
    override suspend fun nextPending(
        limit: Int,
        pending: com.sagd.welltest.domain.SyncState,
    ): List<WellheadLogEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<com.sagd.welltest.domain.SyncState?> = flowOf(null)
    override suspend fun update(log: WellheadLogEntity) = unsupported()
}

private class FakeEscalationDao(private val store: FakeStore) : EscalationDao {
    var failOnUpsert: Boolean = false

    override suspend fun upsert(escalation: EscalationEntity) {
        if (failOnUpsert) throw FakeWriteException("escalation upsert failed")
        store.putEscalation(escalation)
    }

    override suspend fun getById(id: UUID): EscalationEntity? = unsupported()
    override suspend fun nextPending(
        limit: Int,
        pending: com.sagd.welltest.domain.SyncState,
    ): List<EscalationEntity> = unsupported()
    override fun observeSyncState(id: UUID): Flow<com.sagd.welltest.domain.SyncState?> = flowOf(null)
    override suspend fun allPhotoRefs(): List<String> = unsupported()
    override suspend fun update(escalation: EscalationEntity) = unsupported()
    override suspend fun delete(escalation: EscalationEntity) = unsupported()
}

/** Models a SQLite write failure (constraint / I/O) raised mid-transaction. */
private class FakeWriteException(message: String) : RuntimeException(message)

private fun unsupported(): Nothing =
    throw UnsupportedOperationException("not used by the atomic-save property")

/** Always reports ample space so the pre-write check never short-circuits to StorageFull. */
private object AmpleStorageGuard : StorageGuard {
    override fun hasFreeSpace(requiredBytes: Long): Boolean = true
}

// ---------------------------------------------------------------------------
// Generated case model
// ---------------------------------------------------------------------------

/** One generated failure scenario: prior state, the injected save, and a label. */
private data class FailureCase(
    val priorState: PriorState,
    val injection: Injection,
    val label: String,
)

/** The injected-failure save under test; each drives a real save path to Failure. */
private sealed interface Injection {
    data class WellTestWriteFailure(val test: WellTest) : Injection
    data class WellheadLogWriteFailure(val log: WellheadLog) : Injection
    data class EscalationBlobWriteFailure(val escalation: Escalation, val photo: ByteArray) : Injection
    data class EscalationRowWriteFailure(val escalation: Escalation, val photo: ByteArray) : Injection
}

/** Non-trivial prior state: one already-saved record of each syncable kind. */
private data class PriorState(
    val wellTest: WellTest,
    val wellheadLog: WellheadLog,
    val escalation: Escalation,
    val escalationPhoto: ByteArray,
)

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------
//
// One flat generator draws every primitive the case needs and assembles the
// prior-state records and the injected record directly, so there is no nested
// Arb sampling. The `which` draw (0..3) selects the injection kind uniformly,
// so across 100+ iterations each of the four real failure branches is
// exercised many times. The injected record carries freshly drawn values and a
// distinct random id from the seeded records, so a committed write would be
// observable in the snapshot.

/** Raw primitives drawn for a single case, bundled to stay within Arb.bind's arity. */
private class CasePrimitives(
    val wellId: String,
    val which: Int,
    val seedPhoto: ByteArray,
    val injectedPhoto: ByteArray,
    val doubles: List<Double>,
    val timestamps: List<Instant>,
    val priorities: List<EscalationPriority>,
    val flag: Boolean,
)

private val wellIdArb: Arb<String> = Arb.int(1..1_000_000).map { "WELL-$it" }

private val timestampArb: Arb<Instant> =
    Arb.long(1_000_000_000L, 2_000_000_000L).map { Instant.ofEpochSecond(it) }

private val photoArb: Arb<ByteArray> =
    Arb.list(Arb.byte(), 1..256).map { it.toByteArray() }

/** Normalizes any finite double into [0, 1] so it can be scaled into a field range. */
private fun unit(d: Double): Double {
    val x = if (d.isFinite()) d else 0.0
    val frac = x - Math.floor(x)
    return Math.abs(frac).coerceIn(0.0, 1.0)
}

private fun scaled(d: Double, min: Double, max: Double): Double = min + unit(d) * (max - min)

private val failureCaseArb: Arb<FailureCase> = Arb.bind(
    wellIdArb,
    Arb.int(0..3),
    photoArb,
    photoArb,
    Arb.list(Arb.double(0.0, 1_000_000.0), 16..16),
    Arb.bind(
        timestampArb, timestampArb, timestampArb, timestampArb,
    ) { a, b, c, d -> listOf(a, b, c, d) },
    Arb.bind(
        Arb.element(EscalationPriority.entries.toList()),
        Arb.element(EscalationPriority.entries.toList()),
    ) { a, b -> listOf(a, b) },
    Arb.boolean(),
) { wellId, which, seedPhoto, injectedPhoto, doubles, timestamps, priorities, flag ->
    CasePrimitives(
        wellId = wellId,
        which = which,
        seedPhoto = seedPhoto,
        injectedPhoto = injectedPhoto,
        doubles = doubles,
        timestamps = timestamps,
        priorities = priorities,
        flag = flag,
    )
}.map { p -> p.toFailureCase() }

private fun CasePrimitives.toFailureCase(): FailureCase {
    val d = doubles
    // Seeded prior records (distinct ids from any injected record).
    val priorWellTest = WellTest(
        id = UUID.randomUUID(),
        wellId = wellId,
        oilRatio = unit(d[0]),
        waterRatio = unit(d[1]),
        gasRatio = unit(d[2]),
        capturedAt = timestamps[0],
        sync = SyncMetadata(createdAt = timestamps[0]),
    )
    val priorLog = WellheadLog(
        id = UUID.randomUUID(),
        wellId = wellId,
        logType = LogType.PRODUCTION,
        capturedAt = timestamps[1],
        emulsionFlowRate = scaled(d[3], 0.0, 999_999.99),
        casingGasPressure = scaled(d[4], 0.0, 999_999.99),
        espMotorTemperature = scaled(d[5], 0.0, 999_999.99),
        waterCutPercent = scaled(d[6], 0.0, 100.0),
        sync = SyncMetadata(createdAt = timestamps[1]),
    )
    val priorEscalation = Escalation(
        id = UUID.randomUUID(),
        wellId = wellId,
        priority = priorities[0],
        photoRef = "placeholder",
        latitude = scaled(d[7], -90.0, 90.0),
        longitude = scaled(d[8], -180.0, 180.0),
        locationUnavailable = flag,
        capturedAt = timestamps[2],
        sync = SyncMetadata(createdAt = timestamps[2]),
    )
    val prior = PriorState(
        wellTest = priorWellTest,
        wellheadLog = priorLog,
        escalation = priorEscalation,
        escalationPhoto = seedPhoto,
    )

    return when (which) {
        0 -> FailureCase(
            priorState = prior,
            injection = Injection.WellTestWriteFailure(
                WellTest(
                    id = UUID.randomUUID(),
                    wellId = wellId,
                    oilRatio = unit(d[9]),
                    waterRatio = unit(d[10]),
                    gasRatio = unit(d[11]),
                    capturedAt = timestamps[3],
                    sync = SyncMetadata(createdAt = timestamps[3]),
                ),
            ),
            label = "wellTest write failure (wellId=$wellId)",
        )
        1 -> FailureCase(
            priorState = prior,
            injection = Injection.WellheadLogWriteFailure(
                WellheadLog(
                    id = UUID.randomUUID(),
                    wellId = wellId,
                    logType = LogType.PRODUCTION,
                    capturedAt = timestamps[3],
                    emulsionFlowRate = scaled(d[9], 0.0, 999_999.99),
                    casingGasPressure = scaled(d[10], 0.0, 999_999.99),
                    espMotorTemperature = scaled(d[11], 0.0, 999_999.99),
                    waterCutPercent = scaled(d[12], 0.0, 100.0),
                    sync = SyncMetadata(createdAt = timestamps[3]),
                ),
            ),
            label = "wellheadLog write failure (wellId=$wellId)",
        )
        2 -> FailureCase(
            priorState = prior,
            injection = Injection.EscalationBlobWriteFailure(
                injectedEscalation(d, timestamps[3], priorities[1], flag),
                injectedPhoto,
            ),
            label = "escalation blob-write failure (wellId=$wellId)",
        )
        else -> FailureCase(
            priorState = prior,
            injection = Injection.EscalationRowWriteFailure(
                injectedEscalation(d, timestamps[3], priorities[1], flag),
                injectedPhoto,
            ),
            label = "escalation row-write failure (wellId=$wellId)",
        )
    }
}

private fun CasePrimitives.injectedEscalation(
    d: List<Double>,
    captured: Instant,
    priority: EscalationPriority,
    flag: Boolean,
): Escalation = Escalation(
    id = UUID.randomUUID(),
    wellId = wellId,
    priority = priority,
    photoRef = "placeholder",
    latitude = scaled(d[13], -90.0, 90.0),
    longitude = scaled(d[14], -180.0, 180.0),
    locationUnavailable = flag,
    capturedAt = captured,
    sync = SyncMetadata(createdAt = captured),
)
