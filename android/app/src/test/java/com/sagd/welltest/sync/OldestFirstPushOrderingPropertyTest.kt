/*
 * Property 4: Oldest-first push ordering (Android) — task 16.2.
 *
 * Validates: Requirements 2.1
 *
 * Design "Property 4: Oldest-first push ordering":
 *
 *     For any set of pending records with distinct creation times, the sync
 *     engine pushes them to the Cloud_Backend in strictly ascending
 *     `createdAt` order.
 *
 * This mirrors the iOS counterpart (OldestFirstPushOrderingPropertyTests.swift,
 * task 15.2). It exercises the real push-ordering path end to end through the
 * production [SyncCycleRunner] — the plain, Android-free testable core from task
 * 16.1 that the [SyncWorker] CoroutineWorker merely delegates to:
 *
 *   * A varied set of syncable records is seeded with DISTINCT `createdAt`
 *     values spread across all four syncable types (WELL_TEST, WELLHEAD_LOG,
 *     SUBCOOL_READING, ESCALATION), all left PENDING with retryCount == 0 and
 *     lastAttemptAt == null so every record is eligible this cycle (no backoff
 *     skips) and the whole batch pushes in one [SyncCycleRunner.runSyncCycle].
 *   * A recording [SyncPushClient] and a recording [BlobUploadClient] share a
 *     single [PushRecorder], so the recorder captures the complete push order
 *     across every record kind — non-escalation records route to the push
 *     client, escalations route to the blob-upload client, and both append to
 *     the same ordered log. Both clients acknowledge so no record is retried
 *     and the recorded order is exactly the runner's push order.
 *   * The runner pulls its batch from [SyncRepository.nextPendingBatch], the
 *     single ordering authority (ORDER BY createdAt ASC — Req 2.1). The clock is
 *     pinned at a fixed instant (fresh records are always eligible regardless of
 *     "now", so time never needs to advance) matching the deterministic-timing
 *     design.
 *   * The property: the sequence of recorded record ids, mapped back to their
 *     seeded `createdAt`, is strictly ascending.
 *
 * -- Why a map-backed fake SyncRepository rather than a live Room DB --
 *
 * On iOS the mirror test opens a real in-memory GRDB AppDatabase and drives the
 * real GRDBSyncRepository, because GRDB runs directly on the host as a plain
 * library. Room cannot do the equivalent in a pure-JVM unit test: opening a
 * Room database (even in-memory) needs an Android Context and the native SQLite
 * runtime, available only under Robolectric or on-device instrumentation,
 * neither present here. As task 16.2 directs, this test therefore backs the
 * SyncRepository with a map-backed fake whose [nextPendingBatch] reproduces the
 * exact query shape the real Room-backed SyncRepositoryImpl pins down — ORDER BY
 * createdAt ASC, excluding FAILED rows (Repositories.kt, Req 2.1). The REAL
 * SyncCycleRunner runs unchanged over it; the only substitution is the
 * persistence substrate (a map rather than SQLite), which is irrelevant to the
 * push ordering Req 2.1 is about. Insertion order into the fake deliberately
 * does NOT drive the result: the fake sorts by createdAt on read, so the runner
 * (via the repository query) — not the seeding — is what the property exercises.
 *
 * Runs a minimum of 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 4: Oldest-first push ordering`.
 */
package com.sagd.welltest.sync

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import com.sagd.welltest.domain.SyncableRecordType
import com.sagd.welltest.network.BlobUploadClient
import com.sagd.welltest.network.SyncPushClient
import com.sagd.welltest.network.SyncPushResult
import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.util.UUID

class OldestFirstPushOrderingPropertyTest : StringSpec({

    "Feature: sagd-welltest-mobile, Property 4: Oldest-first push ordering" {
        checkAll(pbtConfig(), seedCaseArb) { case ->
            // A fresh in-memory store + recorder per generated case.
            val repository = FakeSyncRepository(case.records.associateBy { it.id })
            val recorder = PushRecorder()

            val runner = SyncCycleRunner(
                syncRepository = repository,
                pushClient = RecordingSyncPushClient(recorder),
                blobUploadClient = RecordingBlobUploadClient(recorder),
                blobSource = StubEscalationBlobSource(case.escalationsById),
                // Fresh records (retryCount 0, lastAttemptAt null) are always
                // eligible, so the real policy never skips any this cycle.
                backoffPolicy = ExponentialBackoffPolicy(),
                // Clock pinned at a fixed instant: time never needs to advance.
                clock = FixedSyncClock(Instant.ofEpochSecond(1_700_000_000L)),
                // Big enough that the whole seeded set pushes in one cycle.
                batchSize = maxOf(case.records.size, 1),
            )

            runner.runSyncCycle()

            val pushedIds = recorder.pushedIds()

            // Every seeded record must have been pushed exactly once.
            val expectedIds = case.records.map { it.id }.toSet()
            withClue(
                "expected every seeded record pushed exactly once: " +
                    "seeded ${case.records.size}, pushed ${pushedIds.size}; " +
                    "ids match=${pushedIds.toSet() == expectedIds}",
            ) {
                pushedIds.size shouldBe case.records.size
                pushedIds.toSet() shouldBe expectedIds
            }

            // Map each pushed id back to its seeded createdAt and assert the
            // sequence is strictly ascending (oldest first — Req 2.1).
            val createdAtById = case.records.associate { it.id to it.createdAt }
            val pushedCreatedAts = pushedIds.map { createdAtById.getValue(it) }

            withClue("push order not strictly ascending by createdAt: $pushedCreatedAts") {
                pushedCreatedAts.zipWithNext()
                    .all { (earlier, later) -> earlier.isBefore(later) }
                    .shouldBeTrue()
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Generated case
// ---------------------------------------------------------------------------

/**
 * One syncable record to seed: its id, type, parent well id, and the distinct
 * `createdAt` that drives its queue position.
 */
private data class SeedRecord(
    val id: UUID,
    val type: SyncableRecordType,
    val wellId: String,
    val createdAt: Instant,
)

/**
 * A full generated scenario: a set of records with DISTINCT `createdAt` spread
 * across the record kinds, plus a lookup of the seeded escalations for the
 * blob-source stub.
 */
private data class SeedCase(
    val records: List<SeedRecord>,
    val escalationsById: Map<UUID, Escalation>,
)

/**
 * Generates a scenario of 1..12 records with DISTINCT `createdAt` values (the
 * property's precondition), varied types across all four syncable kinds, and a
 * small shared parent-well pool. The distinct timestamps are drawn as a set of
 * epoch seconds. Seed/insertion order does not drive the result — the
 * repository query orders the queue by `createdAt` regardless of insertion
 * order, so the runner (not the seeding) is what the property exercises.
 */
private val seedCaseArb: Arb<SeedCase> = arbitrary { rs ->
    val count = Arb.int(1, 12).bind(rs)

    // Distinct createdAt values: draw unique epoch seconds.
    val seconds = linkedSetOf<Long>()
    var attempts = 0
    val secondArb = Arb.long(0L, 2_000_000_000L)
    while (seconds.size < count && attempts < count * 40 + 100) {
        seconds.add(secondArb.bind(rs))
        attempts++
    }

    // A small pool of parent well ids; each record picks one.
    val wellCount = Arb.int(1, 3).bind(rs)
    val wellIds = (0 until wellCount).map { idx ->
        "WELL-${Arb.int(1, 1_000_000).bind(rs)}-$idx"
    }

    val typeArb = Arb.enum<SyncableRecordType>()
    val records = mutableListOf<SeedRecord>()
    val escalationsById = mutableMapOf<UUID, Escalation>()
    for (sec in seconds) {
        val type = typeArb.bind(rs)
        val wellId = wellIds[Arb.int(0, wellIds.size - 1).bind(rs)]
        val id = UUID.randomUUID()
        val createdAt = Instant.ofEpochSecond(sec)
        records.add(SeedRecord(id, type, wellId, createdAt))
        if (type == SyncableRecordType.ESCALATION) {
            escalationsById[id] = Escalation(
                id = id,
                wellId = wellId,
                priority = EscalationPriority.HIGH,
                photoRef = "photo-$id",
                latitude = null,
                longitude = null,
                locationUnavailable = true,
                capturedAt = createdAt,
                sync = SyncMetadata(
                    syncState = SyncState.PENDING,
                    retryCount = 0,
                    createdAt = createdAt,
                ),
            )
        }
    }

    SeedCase(records = records, escalationsById = escalationsById)
}

// ---------------------------------------------------------------------------
// Map-backed fake SyncRepository
// ---------------------------------------------------------------------------

/**
 * A map-backed [SyncRepository] whose [nextPendingBatch] reproduces the real
 * Room-backed query shape: PENDING rows ordered oldest-[SyncMetadata.createdAt]
 * first, excluding FAILED rows (Req 2.1). This is the single ordering authority
 * the runner relies on. The state mutators are implemented so the runner's
 * acknowledgment path is honoured, but this property only reads the push order
 * the recorder captures.
 */
private class FakeSyncRepository(seed: Map<UUID, SeedRecord>) : SyncRepository {

    // Each syncable record as its uniform descriptor, keyed by id.
    private val records: MutableMap<UUID, SyncableRecord> =
        seed.values.associateTo(linkedMapOf()) { record ->
            record.id to SyncableRecord(
                recordId = record.id,
                type = record.type,
                sync = SyncMetadata(
                    syncState = SyncState.PENDING,
                    retryCount = 0,
                    createdAt = record.createdAt,
                    lastAttemptAt = null,
                    remoteId = null,
                ),
            )
        }

    // Reproduces: PENDING only, ORDER BY createdAt ASC, excluding FAILED (Req 2.1).
    override suspend fun nextPendingBatch(limit: Int): List<SyncableRecord> =
        records.values
            .filter { it.sync.syncState == SyncState.PENDING }
            .sortedBy { it.sync.createdAt }
            .take(limit)

    override suspend fun markSynchronized(recordId: UUID, remoteId: String) {
        val current = records[recordId] ?: return
        records[recordId] = current.copy(
            sync = current.sync.copy(
                syncState = SyncState.SYNCHRONIZED,
                remoteId = remoteId,
            ),
        )
    }

    override suspend fun recordFailure(recordId: UUID) {
        val current = records[recordId] ?: return
        val nextRetry = current.sync.retryCount + 1
        val nextState = if (nextRetry >= 5) SyncState.FAILED else SyncState.PENDING
        records[recordId] = current.copy(
            sync = current.sync.copy(retryCount = nextRetry, syncState = nextState),
        )
    }

    override suspend fun manualRetry(recordId: UUID) {
        val current = records[recordId] ?: return
        records[recordId] = current.copy(
            sync = current.sync.copy(syncState = SyncState.PENDING, retryCount = 0),
        )
    }

    override fun observeSyncState(recordId: UUID): Flow<SyncState> =
        flowOf(records[recordId]?.sync?.syncState ?: SyncState.PENDING)
}

// ---------------------------------------------------------------------------
// Shared push recorder
// ---------------------------------------------------------------------------

/**
 * Records, in push order, the ids of every record the runner pushes. Shared by
 * the recording push client and the recording blob-upload client so the log is
 * the complete ordered push sequence across all record kinds. The runner
 * processes the batch sequentially within a single coroutine, so a plain list
 * records the exact push order.
 */
private class PushRecorder {
    private val ids = mutableListOf<UUID>()

    fun record(id: UUID) {
        ids.add(id)
    }

    fun pushedIds(): List<UUID> = ids.toList()
}

// ---------------------------------------------------------------------------
// Recording clients
// ---------------------------------------------------------------------------

/**
 * A [SyncPushClient] that records each pushed record id (in order) and
 * acknowledges it, so the record transitions to SYNCHRONIZED and is not retried
 * within the cycle.
 */
private class RecordingSyncPushClient(private val recorder: PushRecorder) : SyncPushClient {
    override suspend fun push(record: SyncableRecord): SyncPushResult {
        recorder.record(record.recordId)
        return SyncPushResult.Acknowledged(remoteId = "remote-${record.recordId}")
    }
}

/**
 * A [BlobUploadClient] that records each pushed escalation id (in order) and
 * acknowledges it. Escalations route here rather than through the push client,
 * so sharing the recorder keeps the ordered log complete.
 */
private class RecordingBlobUploadClient(private val recorder: PushRecorder) : BlobUploadClient {
    override suspend fun upload(escalation: Escalation, blobPath: String): SyncPushResult {
        recorder.record(escalation.id)
        return SyncPushResult.Acknowledged(remoteId = "remote-${escalation.id}")
    }
}

// ---------------------------------------------------------------------------
// Fakes for the remaining runner seams
// ---------------------------------------------------------------------------

/**
 * Resolves escalations by id for the runner's escalation push path, returning a
 * fixed placeholder blob path (never read by the recording upload client).
 */
private class StubEscalationBlobSource(
    private val escalationsById: Map<UUID, Escalation>,
) : EscalationBlobSource {
    override suspend fun escalationWithBlob(id: UUID): EscalationBlob? {
        val escalation = escalationsById[id] ?: return null
        return EscalationBlob(escalation = escalation, blobPath = "/dev/null/$id.jpg")
    }
}

/**
 * A clock pinned at a fixed instant. Fresh records are always backoff-eligible
 * regardless of "now", so time never needs to advance for this property.
 */
private class FixedSyncClock(private val fixedNow: Instant) : SyncClock {
    override fun now(): Instant = fixedNow
    override suspend fun sleep(seconds: Double) {}
}
