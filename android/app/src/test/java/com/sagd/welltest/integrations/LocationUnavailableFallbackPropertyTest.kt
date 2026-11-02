/*
 * Property 23: Location-unavailable fallback (Android) — task 19.4.
 *
 * **Validates: Requirements 7.5**
 *
 * Design "Property 23: Location-unavailable fallback":
 *
 *     For any escalation captured when no GPS fix is produced within the
 *     30-second bound, the saved escalation has its location-unavailable
 *     indicator set, has null coordinates, and the save still succeeds.
 *
 * ## Approach
 *
 * This mirrors the iOS `LocationUnavailableFallbackPropertyTests`: the property
 * is exercised end-to-end through the real [EscalationCaptureCoordinator], the
 * real [DefaultEscalationValidator], and the real [EscalationRepositoryImpl]
 * writing through a real [BlobStore] rooted in a throwaway temp directory — no
 * mocks on the assemble/persist path. The persisted row is then read back from
 * the store and asserted on, so the fallback state checked is what actually
 * landed in the Local_Store, not just what the coordinator returned.
 *
 * The only injected seams are the two that iOS also injects:
 *
 *   - the location provider: a fake [LocationProvider] that always resolves to
 *     [LocationResult.Unavailable], standing in for a GPS fix that never arrives
 *     within the 30-second bound (the timeout outcome of Req 7.5); and
 *   - the capture clock: a fixed [LocationClock] whose instant carries a
 *     sub-second fraction, so the "UTC 1-second `capturedAt`" assertion actually
 *     exercises the truncation the coordinator applies on the no-fix path rather
 *     than passing trivially.
 *
 * The persistence substrate is the one forced substitution (an in-memory Room
 * database cannot open in a pure-JVM unit test without Robolectric — the same
 * constraint the capture/sync property tests document). So the REAL
 * [EscalationRepositoryImpl] is wired exactly as production over:
 *
 *   - a map-backed [FakeEscalationDao] implementing the production DAO, and
 *   - a [FakeTransactionRunner] honouring Room's withTransaction contract
 *     (commit on return, roll back on throw) — the save path here always
 *     commits; and
 *   - the real [BlobStore] over a unique temp directory, with an always-free
 *     [StorageGuard] so the success path (not storage exhaustion) is the one
 *     under test.
 *
 * The row is read back through the DAO's [FakeEscalationDao.getById] as the
 * stored [EscalationEntity], whose `latitude`/`longitude`/`locationUnavailable`/
 * `capturedAt` columns are public, so the assertions inspect the persisted
 * record directly without the repository-package-internal entity→domain mapper.
 *
 * For each generated case the harness:
 *   1. captures an escalation with valid selections (a priority and a non-blank
 *      well), a non-empty photo, and the always-unavailable location provider,
 *      using the fractional-instant clock;
 *   2. asserts the coordinator returns [EscalationCaptureResult.Saved] with its
 *      location-unavailable flag set (the operator can proceed — Req 7.5); and
 *   3. reads the persisted row back and asserts `locationUnavailable == true`,
 *      null latitude and longitude, and a `capturedAt` equal to the clock
 *      instant truncated to a whole UTC second (1-second precision, no
 *      sub-second component).
 *
 * Pure JVM: the fakes are plain in-memory objects and the blob store is plain
 * java.io; no Android runtime, Robolectric, or instrumentation is required.
 * Runs at least 100 iterations via [pbtConfig].
 */
package com.sagd.welltest.integrations

import com.sagd.welltest.domain.DefaultEscalationValidator
import com.sagd.welltest.domain.EscalationInput
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.EscalationRepository
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.localstore.EscalationDao
import com.sagd.welltest.localstore.EscalationEntity
import com.sagd.welltest.pbtConfig
import com.sagd.welltest.repository.EscalationRepositoryImpl
import com.sagd.welltest.repository.StorageGuard
import com.sagd.welltest.repository.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

class LocationUnavailableFallbackPropertyTest : StringSpec({

    // region Injected seams

    // A location provider whose single-fix request always reports Unavailable,
    // standing in for a GPS fix that never arrives within the 30-second bound
    // (the timeout outcome of Req 7.5). Mirrors the iOS AlwaysTimesOut provider.
    class AlwaysUnavailableLocationProvider : LocationProvider {
        override suspend fun currentFix(): LocationResult = LocationResult.Unavailable
    }

    // A clock the coordinator consults on the no-fix path to stamp capturedAt.
    // Its instant carries a non-zero sub-second fraction so the "truncate to a
    // whole UTC second" assertion exercises the truncation rather than passing
    // trivially. LocationClock.now() is intrinsically a UTC point on the
    // timeline; the coordinator truncates it to the second.
    class FixedFractionalClock(private val instant: Instant) : LocationClock {
        override fun now(): Instant = instant
    }

    // endregion

    // region Persistence substrate (real repository over fakes + real BlobStore)

    // A TransactionRunner honouring Room's withTransaction contract: run the
    // body and return (commit) on success, propagate on throw (rollback). The
    // save path here always commits.
    class FakeTransactionRunner : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    // A StorageGuard that always reports room — this property is about the
    // success path (the save still succeeds despite the location timeout), not
    // storage exhaustion.
    val alwaysFreeStorageGuard = object : StorageGuard {
        override fun hasFreeSpace(requiredBytes: Long): Boolean = true
    }

    // endregion

    // region Generators

    // A non-blank well identifier (so the real validator admits the input and
    // the coordinator reaches the geotag/persist path).
    val wellIds: Arb<String> = Arb.int(1, 1_000_000).map { "WELL-$it" }

    // A photo payload of varied non-empty length (forwarded to the repository;
    // the fallback property does not depend on its contents).
    val photoBytes: Arb<ByteArray> =
        Arb.int(1, 256).map { length -> ByteArray(length) { (it * 31 + 7).toByte() } }

    // A capture instant that carries a non-zero sub-second fraction, so the
    // "capturedAt truncated to a whole UTC second" assertion actually exercises
    // the truncation. Built from whole epoch seconds plus a 1..999ms fraction.
    val fractionalInstants: Arb<Instant> = Arb.bind(
        Arb.long(1_000_000_000L, 2_000_000_000L),
        Arb.int(1, 999),
    ) { seconds, millis -> Instant.ofEpochSecond(seconds).plusMillis(millis.toLong()) }

    // One already-valid capture case: a selected priority, a non-blank well, a
    // photo payload, and the fractional clock instant stamped onto the
    // escalation when the fix times out.
    val fallbackCases: Arb<FallbackCase> = Arb.bind(
        wellIds,
        Arb.enum<EscalationPriority>(),
        photoBytes,
        fractionalInstants,
    ) { wellId, priority, bytes, nowInstant ->
        FallbackCase(
            input = EscalationInput(wellId = wellId, priority = priority, photoRef = null),
            photoBytes = bytes,
            nowInstant = nowInstant,
        )
    }

    // endregion

    "Feature: sagd-welltest-mobile, Property 23: Location-unavailable fallback" {
        checkAll(pbtConfig(), fallbackCases) { case ->
            // Real BlobStore rooted in a throwaway temp dir, unique per case so
            // parallel iterations never collide on blob files.
            val blobDir = Files.createTempDirectory("sagd-prop23-").toFile()
            try {
                val escalationDao = FakeEscalationDao()
                // The repository stamps sync metadata from this clock on save;
                // pin it to the same whole-second instant the capture clock
                // truncates to, so the harness is fully deterministic.
                val repoClock = Clock.fixed(
                    case.nowInstant.truncatedTo(ChronoUnit.SECONDS),
                    ZoneOffset.UTC,
                )
                val repository: EscalationRepository = EscalationRepositoryImpl(
                    transactionRunner = FakeTransactionRunner(),
                    escalationDao = escalationDao,
                    blobStore = BlobStore(blobDir),
                    storageGuard = alwaysFreeStorageGuard,
                    clock = repoClock,
                )

                val coordinator = EscalationCaptureCoordinator(
                    validator = DefaultEscalationValidator(),
                    locationProvider = AlwaysUnavailableLocationProvider(),
                    repository = repository,
                    clock = FixedFractionalClock(case.nowInstant),
                )

                val result = coordinator.capture(case.input, case.photoBytes)

                // 1. The operator can proceed: the capture succeeded despite the
                //    location timeout (Req 7.5), and the result flags the fix as
                //    unavailable.
                val saved = withClue("expected Saved on a location timeout; got $result") {
                    (result as? EscalationCaptureResult.Saved)
                        ?: error("expected EscalationCaptureResult.Saved, got $result")
                }
                withClue("the save result must report the location as unavailable") {
                    saved.locationUnavailable shouldBe true
                }

                // 2. Read the row back from the store — the fallback state must
                //    be what actually persisted, not just what was returned. The
                //    entity's columns are public, so this needs no internal
                //    entity→domain mapper.
                val persisted = withClue("the escalation must be found in the store after save") {
                    escalationDao.getById(saved.escalationId).shouldNotBeNull()
                }

                // 3a. The location-unavailable indicator is set (Req 7.5).
                withClue("persisted.locationUnavailable must be true on a timeout") {
                    persisted.locationUnavailable shouldBe true
                }

                // 3b. No coordinates were recorded (null lat/long).
                withClue("persisted latitude must be null when no fix was obtained") {
                    persisted.latitude shouldBe null
                }
                withClue("persisted longitude must be null when no fix was obtained") {
                    persisted.longitude shouldBe null
                }

                // 3c. capturedAt is the injected clock instant truncated to a
                //     whole UTC second (1-second precision). Equality to the
                //     truncated instant proves the fraction was dropped; the
                //     explicit whole-second check proves the precision contract
                //     independently of the generator. java.time.Instant is
                //     intrinsically a UTC point on the timeline.
                val expectedCapturedAt = case.nowInstant.truncatedTo(ChronoUnit.SECONDS)
                withClue("persisted.capturedAt must be the clock instant truncated to a whole UTC second") {
                    persisted.capturedAt shouldBe expectedCapturedAt
                }
                withClue("persisted.capturedAt must have 1-second precision (no sub-second part)") {
                    persisted.capturedAt.nano shouldBe 0
                    persisted.capturedAt.truncatedTo(ChronoUnit.SECONDS) shouldBe persisted.capturedAt
                }
            } finally {
                blobDir.deleteRecursively()
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Supporting types
// ---------------------------------------------------------------------------

/**
 * One already-valid escalation capture case for the location-unavailable path:
 * the as-selected input (priority + non-blank well), the photo bytes to
 * persist, and the fixed clock instant (carrying a sub-second fraction) stamped
 * onto the escalation when the fix times out.
 */
private data class FallbackCase(
    val input: EscalationInput,
    val photoBytes: ByteArray,
    val nowInstant: Instant,
)

/**
 * Map-backed fake [EscalationDao]: the real [EscalationRepositoryImpl] writes
 * through it exactly as it would Room. Only the save (`upsert`) and read-back
 * (`getById`) paths are meaningful to this property; the rest satisfy the
 * interface.
 */
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
