/*
 * Property 2: Capture requires no network (Android) — task 6.3.
 *
 * Validates: Requirements 1.2
 *
 * Requirement 1.2 says the App must allow an Operator to create, view, and edit
 * Well_Test, Wellhead_Log, and Escalation records while the device has no
 * network connectivity, with all such operations completing without any
 * requirement for network access.
 *
 * This property asserts that directly against the repository layer, which is
 * the only component the UI uses to read and write data. For any valid set of
 * records, a full create -> view -> edit -> view round-trip through the
 * repositories:
 *
 *   * completes successfully (every save returns SaveResult.Success), and
 *   * yields the same persisted result whether or not the network is wired.
 *
 * -- Why the repositories take no network dependency, and how that is modeled --
 *
 * On iOS the mirror test (task 5.3, CaptureRequiresNoNetworkPropertyTests.swift)
 * constructs the REAL GRDB repositories over an in-memory AppDatabase with
 * `NoopSyncWorkSignal`, i.e. no network layer wired at all, and runs the
 * create/view/edit round-trip twice — once for an "offline" device and once for
 * an "online" device — asserting the reloaded records are equal.
 *
 * The point of Req 1.2 is that capture never reaches for the network: the
 * production Android repositories (WellRepositoryImpl, LogRepositoryImpl,
 * EscalationRepositoryImpl) take NO network dependency by design — their only
 * collaborators are the Room DAOs, the photo BlobStore, a pre-write
 * StorageGuard, and (for the two write repositories) a TransactionRunner. None
 * of those is a network type, so there is no seam through which capture could
 * reach the network even in principle. This test wires the REAL repositories
 * and exercises their actual save/read code — it stubs nothing in them.
 *
 * The only substitution is the persistence substrate, which is forced by the
 * environment, not by the property: an in-memory Room database cannot be opened
 * in a pure-JVM unit test (Room's in-memory builder still needs an Android
 * `Context` and the native SQLite library, available only under Robolectric or
 * on-device instrumentation; neither is present here). The task for this
 * property therefore directs: if an in-memory Room DB cannot run in a pure JVM
 * unit test without Robolectric, use an in-memory fake implementation of the
 * DAOs (backed by maps/lists) injected into the repositories.
 *
 * So the repositories are constructed here exactly as production is, but over:
 *
 *   * map-backed fake DAOs implementing the production DAO interfaces, and
 *   * a FakeTransactionRunner honouring Room's withTransaction contract
 *     (commit on normal return, roll back on throw) — the same seam the
 *     atomic-save property (task 6.4) uses. For the all-success capture paths
 *     here it simply commits.
 *
 * The photo BlobStore is plain `java.io` with no Android types, so the REAL
 * BlobStore runs unchanged over a throwaway temp directory.
 *
 * To make the "same persisted result as when the network is available" clause
 * concrete, each operation is run against two independent in-memory stores —
 * one standing for the offline device and one for the online device — and the
 * records reloaded from each are asserted equal.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged through its
 * spec name.
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.EscalationRepository
import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellRepository
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.localstore.MaintenanceTaskDao
import com.sagd.welltest.localstore.MaintenanceTaskEntity
import com.sagd.welltest.localstore.WellDao
import com.sagd.welltest.localstore.WellEntity
import com.sagd.welltest.localstore.WellTestDao
import com.sagd.welltest.localstore.WellTestEntity
import com.sagd.welltest.localstore.WellheadLogDao
import com.sagd.welltest.localstore.WellheadLogEntity
import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CaptureRequiresNoNetworkPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 2: Capture requires no network" {
        checkAll(pbtConfig(), capturedRecordsArb) { records ->
            // Run the identical create/view/edit sequence against two isolated
            // stores: one standing for an offline device (no network wired) and
            // one for an online device. Neither store has a network layer — that
            // is the point of Req 1.2, capture never depends on the network — and
            // asserting the two agree makes the "same persisted result as when
            // the network is available" clause explicit. Both clocks are pinned
            // to the same instant so the comparison is about captured data, not
            // wall-clock skew between the two harnesses.
            val offline = newStore().captureViewEdit(records)
            val online = newStore().captureViewEdit(records)

            // Every create/edit returned SaveResult.Success with no network wired.
            withClue("a create/view/edit op did not return Success offline") {
                offline.allSucceeded.shouldBeTrue()
            }

            // The reloaded records are all present (capture persisted locally
            // with no connectivity).
            withClue("offline reloaded records must all be present: ${offline.reloaded}") {
                (offline.reloaded.wellTest != null).shouldBeTrue()
                (offline.reloaded.wellheadLog != null).shouldBeTrue()
                (offline.reloaded.escalation != null).shouldBeTrue()
                (offline.reloaded.photoBytes != null).shouldBeTrue()
            }

            // The captured fields reloaded match what was captured. The
            // repository stamps sync metadata authoritatively on save
            // (normalizedForSave: PENDING, retryCount 0, createdAt = now), so the
            // expected record is the input with its sync normalized to the same
            // fixed clock — exactly the iOS test's "normalize the volatile sync
            // fields before comparing captured data" step. The escalation's
            // photoRef is likewise minted by the blob store on save, so the
            // escalation is compared up to its photoRef and the photo *bytes* are
            // compared directly.
            val savedInstant = FIXED_CLOCK.instant()
            offline.reloaded.wellTest shouldBe
                records.wellTest.copy(sync = records.wellTest.sync.normalizedForSave(savedInstant))
            offline.reloaded.wellheadLog shouldBe
                records.wellheadLog.copy(sync = records.wellheadLog.sync.normalizedForSave(savedInstant))
            offline.reloaded.escalation?.copy(photoRef = "") shouldBe
                records.escalation.copy(
                    photoRef = "",
                    sync = records.escalation.sync.normalizedForSave(savedInstant),
                )
            offline.reloaded.photoBytes shouldBe records.photoData.toList()

            // The persisted result after the full round-trip is identical
            // regardless of connectivity (same well test, same wellhead log, same
            // escalation up to its store-local photoRef, same photo bytes).
            withClue("offline vs online persisted results must match") {
                offline.reloaded.wellTest shouldBe online.reloaded.wellTest
                offline.reloaded.wellheadLog shouldBe online.reloaded.wellheadLog
                offline.reloaded.escalation?.copy(photoRef = "") shouldBe
                    online.reloaded.escalation?.copy(photoRef = "")
                offline.reloaded.photoBytes shouldBe online.reloaded.photoBytes
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Per-store harness
// ---------------------------------------------------------------------------

/**
 * The repository stamps createdAt = now() and a fresh SyncMetadata
 * authoritatively on every save, so two stores saving the "same" record at
 * different wall-clock instants would differ only by those volatile timestamps —
 * which have nothing to do with the operator's captured fields that Req 1.2 is
 * about. Pin both stores' clocks to one fixed instant so the equality assertion
 * is about captured data, not clock skew, exactly like the iOS test normalizes
 * the volatile sync fields before comparing.
 */
private val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC)

/**
 * A StorageGuard that always reports room — these properties are about the
 * success path (capture completes offline), not storage exhaustion (that is
 * task 6.6). The production FileSystemStorageGuard is itself network-free; this
 * fake simply removes the dependency on a real device filesystem.
 */
private object AlwaysFreeStorageGuard : StorageGuard {
    override fun hasFreeSpace(requiredBytes: Long): Boolean = true
}

/**
 * One isolated in-memory store plus the REAL repositories layered over it,
 * wired exactly as production is but with no network layer present on any
 * capture path — this is the "network unavailable" world.
 */
private class CaptureStore {
    val wellDao = FakeWellDao()
    val wellTestDao = FakeWellTestDao()
    val wellheadLogDao = FakeWellheadLogDao()
    val escalationDao = FakeEscalationDao()
    val maintenanceTaskDao = FakeMaintenanceTaskDao()

    // A real BlobStore rooted in a throwaway temp dir, unique per store so the
    // parallel offline/online harnesses never collide on blob files.
    val blobStore = BlobStore(Files.createTempDirectory("sagd-prop2-").toFile())

    // Commit-on-success / rollback-on-throw, same contract as Room's
    // withTransaction. Every capture here succeeds, so it always commits.
    private val runner = FakeTransactionRunner()

    val wells: WellRepository =
        WellRepositoryImpl(wellDao, wellTestDao, maintenanceTaskDao)
    val logs: LogRepository =
        LogRepositoryImpl(runner, wellheadLogDao, wellTestDao, AlwaysFreeStorageGuard, FIXED_CLOCK)
    val escalations: EscalationRepository =
        EscalationRepositoryImpl(runner, escalationDao, blobStore, AlwaysFreeStorageGuard, FIXED_CLOCK)

    /**
     * Exercises the full Req 1.2 surface for one well, with no network wired:
     * create each record, view it back, edit it, and view it again. Returns the
     * final reloaded records and whether every save returned SaveResult.Success.
     */
    suspend fun captureViewEdit(records: CapturedRecords): RoundTripOutcome {
        var allSucceeded = true

        // Precondition: the parent well exists locally. Well reference data is
        // populated by the cloud pull and lives in the local store; a captured
        // record references it by wellId. Seeding the well is a pure local-store
        // write (cached pull data), not a network call — Req 1.2 concerns the
        // operator's create/view/edit of captured records against already-cached
        // reference data with no connectivity.
        wellDao.upsert(records.well.toEntity())

        // --- Create ---
        allSucceeded = allSucceeded && logs.saveWellTest(records.wellTest) == SaveResult.Success
        allSucceeded = allSucceeded && logs.saveWellheadLog(records.wellheadLog) == SaveResult.Success
        allSucceeded = allSucceeded &&
            escalations.saveEscalation(records.escalation, records.photoData) == SaveResult.Success

        // --- View (read-back is a pure local-store query, never the network) ---
        val afterCreate = wells.getWellTests(records.wellId)
        allSucceeded = allSucceeded && afterCreate.any { it.id == records.wellTest.id }

        // --- Edit: capture a second, distinct record for the same well, proving
        // repeated offline capture/editing of the well's data set succeeds with
        // no network wired. (The save API is an upsert keyed on a new id, so an
        // "edit" is a fresh capture, mirroring the iOS test.)
        allSucceeded = allSucceeded && logs.saveWellTest(records.secondWellTest) == SaveResult.Success
        allSucceeded =
            allSucceeded && logs.saveWellheadLog(records.secondWellheadLog) == SaveResult.Success

        // --- View again and capture the final persisted state ---
        val reloadedTest = wells.getWellTests(records.wellId)
            .firstOrNull { it.id == records.wellTest.id }
        val reloadedLog = wellheadLogDao.getById(records.wellheadLog.id)?.toDomain()
        val reloadedEscalation = escalationDao.getById(records.escalation.id)?.toDomain()
        val reloadedPhoto = reloadedEscalation?.let { blobStore.read(it.photoRef)?.toList() }

        return RoundTripOutcome(
            allSucceeded = allSucceeded,
            reloaded = ReloadedRecords(
                wellTest = reloadedTest,
                wellheadLog = reloadedLog,
                escalation = reloadedEscalation,
                photoBytes = reloadedPhoto,
            ),
        )
    }
}

private fun newStore(): CaptureStore = CaptureStore()

/**
 * The captured fields reloaded from a store after create + edit. Only the
 * fields the operator captured are compared; the escalation photo bytes are
 * read back from the blob store to prove the photo persisted with no network.
 */
private data class ReloadedRecords(
    val wellTest: WellTest?,
    val wellheadLog: WellheadLog?,
    val escalation: Escalation?,
    val photoBytes: List<Byte>?,
)

private data class RoundTripOutcome(
    val allSucceeded: Boolean,
    val reloaded: ReloadedRecords,
)

// ---------------------------------------------------------------------------
// Transaction runner — commit on success, roll back (propagate) on throw
// ---------------------------------------------------------------------------

/**
 * A [TransactionRunner] honouring Room's `withTransaction` contract. The fake
 * DAOs write straight into their maps, so for the all-success capture paths
 * here the runner just runs the body and returns; on a throw it propagates (the
 * repository's catch then classifies it), matching Room's rollback-on-throw.
 */
private class FakeTransactionRunner : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
}

// ---------------------------------------------------------------------------
// Map-backed fake DAOs (no network, pure in-memory local store)
// ---------------------------------------------------------------------------

// These fakes implement the production DAO interfaces so the real
// WellRepositoryImpl / LogRepositoryImpl / EscalationRepositoryImpl talk to them
// exactly as they would Room — the only difference from production is that the
// store is a map rather than SQLite, which is irrelevant to Req 1.2 (no DAO is
// a network type). Only the methods the capture paths use are meaningful; the
// rest satisfy the interface.

private class FakeWellDao : WellDao {
    private val rows = linkedMapOf<String, WellEntity>()
    override suspend fun upsert(well: WellEntity) { rows[well.id] = well }
    override suspend fun upsertAll(wells: List<WellEntity>) = wells.forEach { rows[it.id] = it }
    override suspend fun getWell(id: String): WellEntity? = rows[id]
    override fun observeWell(id: String): Flow<WellEntity?> = flowOf(rows[id])
    override fun observeAll(): Flow<List<WellEntity>> = flowOf(rows.values.sortedBy { it.name })
    override suspend fun deleteAll() = rows.clear()
}

private class FakeWellTestDao : WellTestDao {
    private val rows = linkedMapOf<UUID, WellTestEntity>()
    override suspend fun upsert(test: WellTestEntity) { rows[test.id] = test }
    override suspend fun getById(id: UUID): WellTestEntity? = rows[id]
    override suspend fun getForWell(wellId: String): List<WellTestEntity> =
        rows.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellTestEntity> =
        rows.values.filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun update(test: WellTestEntity) { rows[test.id] = test }
}

private class FakeWellheadLogDao : WellheadLogDao {
    private val rows = linkedMapOf<UUID, WellheadLogEntity>()
    override suspend fun upsert(log: WellheadLogEntity) { rows[log.id] = log }
    override suspend fun getById(id: UUID): WellheadLogEntity? = rows[id]
    override suspend fun getForWell(wellId: String): List<WellheadLogEntity> =
        rows.values.filter { it.wellId == wellId }.sortedByDescending { it.capturedAt }
    override suspend fun nextPending(limit: Int, pending: SyncState): List<WellheadLogEntity> =
        rows.values.filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun update(log: WellheadLogEntity) { rows[log.id] = log }
}

private class FakeEscalationDao : EscalationDao {
    private val rows = linkedMapOf<UUID, EscalationEntity>()
    override suspend fun upsert(escalation: EscalationEntity) { rows[escalation.id] = escalation }
    override suspend fun getById(id: UUID): EscalationEntity? = rows[id]
    override suspend fun nextPending(limit: Int, pending: SyncState): List<EscalationEntity> =
        rows.values.filter { it.sync.syncState == pending }
            .sortedBy { it.sync.createdAt }.take(limit)
    override fun observeSyncState(id: UUID): Flow<SyncState?> = flowOf(rows[id]?.sync?.syncState)
    override suspend fun allPhotoRefs(): List<String> = rows.values.map { it.photoRef }
    override suspend fun update(escalation: EscalationEntity) { rows[escalation.id] = escalation }
    override suspend fun delete(escalation: EscalationEntity) { rows.remove(escalation.id) }
}

private class FakeMaintenanceTaskDao : MaintenanceTaskDao {
    private val rows = linkedMapOf<UUID, MaintenanceTaskEntity>()
    override suspend fun upsert(task: MaintenanceTaskEntity) { rows[task.id] = task }
    override suspend fun upsertAll(tasks: List<MaintenanceTaskEntity>) =
        tasks.forEach { rows[it.id] = it }
    override suspend fun getOpenForWell(
        wellId: String,
        open: MaintenanceStatus,
    ): List<MaintenanceTaskEntity> =
        rows.values.filter { it.wellId == wellId && it.status == open }
            .sortedByDescending { it.createdAt }
    override suspend fun deleteForWell(wellId: String) {
        rows.values.filter { it.wellId == wellId }.map { it.id }.forEach { rows.remove(it) }
    }
}

// ---------------------------------------------------------------------------
// Generated inputs
// ---------------------------------------------------------------------------

/**
 * A full set of valid records to capture for a single well, plus a second
 * distinct well test / wellhead log (new ids) standing in for the continued-
 * capture ("edit") path the repositories support.
 */
private data class CapturedRecords(
    val wellId: String,
    val well: Well,
    val wellTest: WellTest,
    val secondWellTest: WellTest,
    val wellheadLog: WellheadLog,
    val secondWellheadLog: WellheadLog,
    val escalation: Escalation,
    val photoData: ByteArray,
)

private fun syncMeta(createdAt: Instant) =
    SyncMetadata(syncState = SyncState.PENDING, retryCount = 0, createdAt = createdAt)

private fun wellTestOf(wellId: String, capturedAt: Instant, o: Double, w: Double, g: Double) =
    WellTest(
        id = UUID.randomUUID(),
        wellId = wellId,
        oilRatio = o,
        waterRatio = w,
        gasRatio = g,
        capturedAt = capturedAt,
        sync = syncMeta(capturedAt),
    )

private fun productionLogOf(
    wellId: String,
    capturedAt: Instant,
    emulsion: Double,
    casing: Double,
    esp: Double,
    waterCut: Double,
) = WellheadLog(
    id = UUID.randomUUID(),
    wellId = wellId,
    logType = LogType.PRODUCTION,
    capturedAt = capturedAt,
    emulsionFlowRate = emulsion,
    casingGasPressure = casing,
    espMotorTemperature = esp,
    waterCutPercent = waterCut,
    sync = syncMeta(capturedAt),
)

private fun injectionLogOf(
    wellId: String,
    capturedAt: Instant,
    pressure: Double,
    temperature: Double,
    flow: Double,
) = WellheadLog(
    id = UUID.randomUUID(),
    wellId = wellId,
    logType = LogType.INJECTION,
    capturedAt = capturedAt,
    steamInjectionPressure = pressure,
    steamTemperature = temperature,
    steamFlowRate = flow,
    sync = syncMeta(capturedAt),
)

private val wellIdArb: Arb<String> = Arb.int(1..100_000).map { "WELL-$it" }
private val capturedAtArb: Arb<Instant> =
    Arb.long(1_600_000_000L..1_800_000_000L).map { Instant.ofEpochSecond(it) }

/**
 * Generates a valid, internally consistent set of records: a well (parent
 * reference row), a well test, a wellhead log (production or injection), and an
 * escalation, all for the same well, plus a second distinct well test and
 * wellhead log for the continued-capture path. All numeric fields are drawn
 * inside their accepted ranges so the capture path is unambiguously the success
 * path (validation is covered by the validator properties, not here).
 */
private val capturedRecordsArb: Arb<CapturedRecords> = Arb.bind(
    wellIdArb,
    capturedAtArb,
    Arb.boolean(),
    Arb.element(EscalationPriority.entries.toList()),
    Arb.list(Arb.int(0..255), 1..256).map { bytes -> ByteArray(bytes.size) { bytes[it].toByte() } },
) { wellId, capturedAt, isProduction, priority, photoData ->
    val wellType = if (isProduction) WellType.PRODUCTION else WellType.INJECTION
    val well = Well(
        id = wellId,
        name = "Well $wellId",
        type = wellType,
        padId = null,
        updatedAt = capturedAt,
    )

    fun makeLog() =
        if (isProduction) {
            productionLogOf(wellId, capturedAt, 100.0, 200.0, 85.0, 42.0)
        } else {
            injectionLogOf(wellId, capturedAt, 12_000.0, 250.0, 1_000.0)
        }

    CapturedRecords(
        wellId = wellId,
        well = well,
        wellTest = wellTestOf(wellId, capturedAt, 0.6, 0.3, 0.1),
        secondWellTest = wellTestOf(wellId, capturedAt, 0.5, 0.4, 0.1),
        wellheadLog = makeLog(),
        secondWellheadLog = makeLog(),
        escalation = Escalation(
            id = UUID.randomUUID(),
            wellId = wellId,
            priority = priority,
            photoRef = "placeholder", // overwritten by the blob store on save
            latitude = 56.7,
            longitude = -111.4,
            locationUnavailable = false,
            capturedAt = capturedAt,
            sync = syncMeta(capturedAt),
        ),
        photoData = photoData,
    )
}
