/*
 * Property 21: Escalation geotag attachment (Android) — task 19.3.
 *
 * **Validates: Requirements 7.1**
 *
 * Design "Property 21: Escalation geotag attachment":
 *
 *     For any escalation captured while a GPS fix is available, the saved
 *     escalation carries the provided decimal-degree latitude and longitude and
 *     a UTC one-second-precision capture timestamp.
 *
 * ## Approach
 *
 * This drives the real [EscalationCaptureCoordinator] — the component that
 * assembles and persists an escalation — with two fakes standing in for the
 * device/storage seams, mirroring the iOS `EscalationGeotagAttachmentPropertyTests`:
 *
 *   - a fake [LocationProvider] that always returns
 *     [LocationResult.Available] with a generated [LocationFix] whose latitude
 *     and longitude are decimal degrees and whose timestamp is a UTC instant
 *     already truncated to the second (as the production [BoundedLocationProvider]
 *     guarantees before handing the fix to the coordinator);
 *   - a fake [EscalationRepository] that records the [Escalation] it was asked
 *     to save and reports [SaveResult.Success], so the test can inspect exactly
 *     the record the coordinator persisted.
 *
 * With a valid input (priority + well both present) the coordinator requests one
 * fix, and since the fix is available it must copy the fix's coordinates and
 * timestamp onto the persisted escalation and leave the location-unavailable
 * flag clear. The property asserts, for every generated (input, fix) pair, that
 * the recorded escalation's:
 *   - latitude/longitude equal the fix's decimal-degree coordinates (Req 7.1);
 *   - capturedAt equals the fix's timestamp AND carries 1-second precision
 *     (no sub-second component) and is in UTC (Req 7.1);
 *   - locationUnavailable flag is false (a fix was attached);
 *   - and that the save in fact succeeded (the coordinator reported Saved).
 *
 * The validator is the real [DefaultEscalationValidator]; inputs are generated
 * already-valid (a selected priority and a non-blank well) so every iteration
 * reaches the geotag-and-persist path the property is about. The coordinator's
 * [LocationClock] is irrelevant on the fix-available path (its capture time
 * comes from the fix), but a fixed clock is injected so the test is fully
 * deterministic.
 *
 * Pure JVM: the fakes are plain in-memory objects and no Android runtime,
 * Robolectric, or instrumentation is required. Runs at least 100 iterations via
 * [pbtConfig].
 */
package com.sagd.welltest.integrations

import com.sagd.welltest.domain.DefaultEscalationValidator
import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationInput
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.EscalationRepository
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.Instant
import java.time.temporal.ChronoUnit

class EscalationGeotagAttachmentPropertyTest : StringSpec({

    // region Fakes

    // A location provider that always reports a fix is available, carrying the
    // supplied fix. Mirrors the iOS fake CLLocation-backed provider: the GPS
    // "fix within 30s?" branch always resolves to Available for this property.
    class FixedLocationProvider(private val fix: LocationFix) : LocationProvider {
        override suspend fun currentFix(): LocationResult = LocationResult.Available(fix)
    }

    // An escalation repository that records the escalation handed to it and
    // reports success. Captures exactly what the coordinator chose to persist so
    // the test inspects the real saved record (not a re-derived copy).
    class RecordingEscalationRepository : EscalationRepository {
        var saved: Escalation? = null
            private set
        var savedPhoto: ByteArray? = null
            private set

        override suspend fun saveEscalation(
            escalation: Escalation,
            photoData: ByteArray,
        ): SaveResult {
            saved = escalation
            savedPhoto = photoData
            return SaveResult.Success
        }
    }

    // endregion

    // region Generators

    // A non-blank well identifier (so the real validator admits the input and
    // the coordinator reaches the geotag/persist path).
    val wellIds: Arb<String> = Arb.int(1, 1_000_000).map { "WELL-$it" }

    // Decimal-degree coordinates across the full valid globe range.
    val latitudes: Arb<Double> = Arb.double(-90.0, 90.0)
    val longitudes: Arb<Double> = Arb.double(-180.0, 180.0)

    // Capture instants generated at 1-second precision in UTC, exactly as the
    // production BoundedLocationProvider truncates them before the fix reaches
    // the coordinator. Epoch-second construction yields a UTC instant with no
    // sub-second part.
    val fixTimestamps: Arb<Instant> =
        Arb.long(0L, 2_000_000_000L).map { Instant.ofEpochSecond(it) }

    // A photo payload of varied non-empty length (the coordinator forwards it to
    // the repository; the geotag property does not depend on its contents).
    val photoBytes: Arb<ByteArray> =
        Arb.int(1, 256).map { length -> ByteArray(length) { (it * 31 + 7).toByte() } }

    // One already-valid capture case: a selected priority, a non-blank well, an
    // available fix (lat/lon + UTC-second timestamp), and a photo payload.
    val captureCases: Arb<CaptureCase> = Arb.bind(
        wellIds,
        Arb.enum<EscalationPriority>(),
        Arb.bind(latitudes, longitudes, fixTimestamps) { lat, lon, ts ->
            LocationFix(latitude = lat, longitude = lon, timestamp = ts)
        },
        photoBytes,
    ) { wellId, priority, fix, bytes ->
        CaptureCase(
            input = EscalationInput(wellId = wellId, priority = priority, photoRef = null),
            fix = fix,
            photoBytes = bytes,
        )
    }

    // A clock the coordinator never consults on the fix-available path (capture
    // time comes from the fix); pinned so the test is deterministic regardless.
    val fixedClock = object : LocationClock {
        override fun now(): Instant = Instant.ofEpochSecond(1_700_000_000L)
    }

    // endregion

    "Feature: sagd-welltest-mobile, Property 21: Escalation geotag attachment" {
        checkAll(pbtConfig(), captureCases) { case ->
            val repository = RecordingEscalationRepository()
            val coordinator = EscalationCaptureCoordinator(
                validator = DefaultEscalationValidator(),
                locationProvider = FixedLocationProvider(case.fix),
                repository = repository,
                clock = fixedClock,
            )

            val result = coordinator.capture(case.input, case.photoBytes)

            // The save must have succeeded with a fix attached (not unavailable),
            // so the coordinator reached the persist path this property is about.
            val savedResult = withClue("expected a successful save; got $result") {
                (result as? EscalationCaptureResult.Saved)
                    ?: error("expected Saved, got $result")
            }
            withClue("a fix was attached, so the result must not report unavailable") {
                savedResult.locationUnavailable shouldBe false
            }

            val saved = repository.saved
            withClue("coordinator must have persisted exactly one escalation") {
                (saved != null) shouldBe true
            }
            requireNotNull(saved)

            // 1. The saved escalation carries the fix's decimal-degree coordinates.
            withClue("saved latitude must equal the fix latitude") {
                saved.latitude shouldBe case.fix.latitude
            }
            withClue("saved longitude must equal the fix longitude") {
                saved.longitude shouldBe case.fix.longitude
            }

            // 2. capturedAt is exactly the fix timestamp, with 1-second precision
            //    and in UTC (Req 7.1). Equality to the fix instant proves it is
            //    carried verbatim; the truncation/zone checks prove the precision
            //    and timezone contract independently of the generator.
            withClue("capturedAt must equal the fix timestamp") {
                saved.capturedAt shouldBe case.fix.timestamp
            }
            // java.time.Instant is intrinsically a UTC point on the timeline, so
            // the only observable precision contract to assert is 1-second
            // granularity: no sub-second (nanosecond) component survives.
            withClue("capturedAt must have 1-second precision (no sub-second part)") {
                saved.capturedAt.nano shouldBe 0
                saved.capturedAt.truncatedTo(ChronoUnit.SECONDS) shouldBe saved.capturedAt
            }

            // 3. A fix was attached, so the location-unavailable flag is clear.
            withClue("locationUnavailable must be false when a fix is attached") {
                saved.locationUnavailable shouldBe false
            }
        }
    }
})

// region Supporting types

/**
 * One already-valid escalation capture case: the as-selected input, the
 * available location fix to attach, and the photo bytes to persist.
 */
private data class CaptureCase(
    val input: EscalationInput,
    val fix: LocationFix,
    val photoBytes: ByteArray,
)

// endregion
