package com.sagd.welltest.repository

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.pbtConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Property 1: Persistence round-trip (Android) — task 6.2.
 *
 * **Validates: Requirements 1.1, 1.3, 7.3**
 *
 * Design "Property 1: Persistence round-trip":
 *
 *     For any valid syncable record (well test, wellhead log, subcool reading,
 *     or escalation), saving it to the Local_Store and then reloading from the
 *     store (including after the store is closed and reopened) yields a record
 *     whose captured fields are equal to those originally saved.
 *
 * ## Approach: why this test round-trips through the Mappers, not a live Room DB
 *
 * The iOS counterpart (`PersistenceRoundTripPropertyTests.swift`) opens a real
 * on-disk GRDB `DatabaseQueue`, saves, closes the connection, reopens the file,
 * and reads the record back. That is possible on iOS because GRDB runs directly
 * on the host as a plain library.
 *
 * Room cannot do the equivalent in a *pure JVM unit test*. Opening a Room
 * database requires an Android `Context` and the Android SQLite runtime;
 * `Room.inMemoryDatabaseBuilder` / `Room.databaseBuilder` both need that
 * framework present. In a plain `src/test` (JVM) module there is no Android
 * runtime, so an in-memory Room DB can only run under Robolectric or on-device
 * instrumentation (`androidTest`). This task is explicitly a pure-JVM
 * kotest-property test with no Robolectric/instrumentation harness available.
 *
 * So, as the task directs, this test proves the *core* of Property 1 — that the
 * captured-field mapping across the persistence boundary is lossless — by
 * round-tripping each record through the real entity<->domain [Mappers]:
 *
 *     domain -> toEntity() -> (the row Room would store) -> toDomain() -> domain
 *
 * `toEntity()` builds exactly the Room `@Entity` row that would be written, and
 * `toDomain()` rebuilds the domain record exactly as it would be read back on a
 * fresh app launch. Room persists those entity columns as a flat,
 * column-for-column store with no transformation of the captured fields (see
 * `Entities.kt`), so `toEntity().toDomain()` reproduces the save/close/reopen
 * path's effect on the captured fields. The live-DB close/reopen behaviour
 * (opening the existing DB on launch) is covered by task 4.1's Room
 * configuration; this property isolates the lossless mapping that round-trip
 * depends on.
 *
 * For the escalation photo (Req 7.3) the captured bytes are round-tripped
 * through the **real** [BlobStore], which is pure `java.io.File` I/O and runs
 * unchanged in a JVM test. A temp directory stands in for app-internal storage:
 * the photo is written via `BlobStore.write`, the returned `photoRef` is mapped
 * onto the entity (exactly as `EscalationRepositoryImpl` does), and the bytes
 * are read back from the store after the round-trip and compared byte-for-byte.
 *
 * ## Captured fields vs. store-assigned metadata
 *
 * The repositories deliberately re-stamp sync metadata at save time
 * (`SyncMetadata.normalizedForSave`: `syncState = PENDING`, `retryCount = 0`,
 * `createdAt = now`, `lastAttemptAt = null`, `remoteId = null`) and the
 * escalation repository assigns `photoRef` from the blob store. Those are
 * store-assigned values, not operator-captured input, so — exactly like the iOS
 * test — this property asserts equality of the *captured* fields (identity,
 * associations, measurements, timestamps, location, flags) rather than the
 * transient sync bookkeeping. The subcool reading is the one record saved
 * verbatim (its optional sync metadata is part of its captured value), so it is
 * compared in full.
 *
 * Runs at least 100 iterations via [pbtConfig] and is tagged
 * `Feature: sagd-welltest-mobile, Property 1: Persistence round-trip`.
 */
class PersistenceRoundTripPropertyTest : StringSpec({

    // --- Primitive generators (mirroring the iOS SwiftCheck draws) ---

    // Well ids of the "WELL-<n>" shape the parent well carries.
    val wellIds: Arb<String> = Arb.int(1, 1_000_000).map { "WELL-$it" }

    // Capture timestamps at 1-second precision, as the captured-timestamp
    // contract requires, so the round-tripped Instant compares exactly.
    val timestamps: Arb<Instant> =
        Arb.long(0L, 2_000_000_000L).map { Instant.ofEpochSecond(it) }

    // Well-test oil/water/gas ratios in a plausible [0, 1] range.
    val ratios: Arb<Double> = Arb.double(0.0, 1.0)

    // A production-field measurement in [0.00, 999,999.99].
    val measurements: Arb<Double> = Arb.double(0.0, 999_999.99)

    // Water cut percentage in [0, 100].
    val percents: Arb<Double> = Arb.double(0.0, 100.0)

    // Steam injection pressure in [0, 25,000].
    val steamPressures: Arb<Double> = Arb.double(0.0, 25_000.0)

    // Steam / pump temperature in [0, 400].
    val temperatures: Arb<Double> = Arb.double(0.0, 400.0)

    // Steam flow rate in [0, 2,000].
    val steamFlows: Arb<Double> = Arb.double(0.0, 2_000.0)

    // A parent well each generated record associates with. The type is drawn so
    // both production and injection parents appear; the association only needs
    // the id to exist.
    val wells: Arb<Well> = Arb.bind(
        wellIds,
        Arb.enum<WellType>(),
        timestamps,
    ) { id, type, updatedAt ->
        Well(id = id, name = "Well $id", type = type, padId = null, updatedAt = updatedAt)
    }

    // Sync metadata the repository would normalize anyway; generated here so the
    // captured-field comparison stays independent of whatever it holds.
    val syncMetadata: Arb<SyncMetadata> = timestamps.map { SyncMetadata(createdAt = it) }

    // --- Per-type syncable-case generators (all randomness via Arb.bind) ---

    val wellTestCases: Arb<SyncableCase> = Arb.bind(
        wells,
        Arb.uuid(),
        // Pack the three ratios + captured timestamp + sync into one draw so
        // Arb.bind stays within its arity while every field is composed.
        Arb.bind(ratios, ratios, ratios, timestamps) { o, w, g, t -> WellTestFields(o, w, g, t) },
        syncMetadata,
    ) { well, id, f, sync ->
        SyncableCase.WellTestCase(
            well,
            WellTest(
                id = id,
                wellId = well.id,
                oilRatio = f.oil,
                waterRatio = f.water,
                gasRatio = f.gas,
                capturedAt = f.capturedAt,
                sync = sync,
            ),
        )
    }

    // Production field group: all four values populated, injection group null.
    val productionLogs: Arb<ProductionFields> = Arb.bind(
        measurements,
        measurements,
        measurements,
        percents,
    ) { emulsion, casing, esp, waterCut -> ProductionFields(emulsion, casing, esp, waterCut) }

    // Injection field group: all three values populated, production group null.
    val injectionLogs: Arb<InjectionFields> = Arb.bind(
        steamPressures,
        temperatures,
        steamFlows,
    ) { pressure, temp, flow -> InjectionFields(pressure, temp, flow) }

    val wellheadLogCases: Arb<SyncableCase> = Arb.bind(
        wells,
        Arb.uuid(),
        timestamps,
        // Choose production or injection with equal weight; the chosen branch's
        // field group is the only one populated, matching the discriminated schema.
        Arb.choice(productionLogs.map { it as LogFields }, injectionLogs.map { it as LogFields }),
        syncMetadata,
    ) { well, id, capturedAt, fields, sync ->
        val log = when (fields) {
            is ProductionFields -> WellheadLog(
                id = id,
                wellId = well.id,
                logType = LogType.PRODUCTION,
                capturedAt = capturedAt,
                emulsionFlowRate = fields.emulsionFlowRate,
                casingGasPressure = fields.casingGasPressure,
                espMotorTemperature = fields.espMotorTemperature,
                waterCutPercent = fields.waterCutPercent,
                sync = sync,
            )

            is InjectionFields -> WellheadLog(
                id = id,
                wellId = well.id,
                logType = LogType.INJECTION,
                capturedAt = capturedAt,
                steamInjectionPressure = fields.steamInjectionPressure,
                steamTemperature = fields.steamTemperature,
                steamFlowRate = fields.steamFlowRate,
                sync = sync,
            )
        }
        SyncableCase.WellheadLogCase(well, log)
    }

    val subcoolReadingCases: Arb<SyncableCase> = Arb.bind(
        wells,
        Arb.uuid(),
        // casing pressure, pump temp, saturation temp, captured timestamp.
        Arb.bind(measurements, temperatures, temperatures, timestamps) { c, p, t, at ->
            SubcoolFields(c, p, t, at)
        },
        // Attach to a well or stand alone; carry sync metadata or not.
        Arb.bind(Arb.boolean(), Arb.boolean()) { attach, hasSync -> attach to hasSync },
    ) { well, id, f, (attach, hasSync) ->
        val subcool = f.saturationTemperature - f.pumpTemperature
        val reading = SubcoolReading(
            id = id,
            // wellId is optional (SET_NULL): generate both attached and
            // standalone readings so the optional association round-trips both
            // ways. Sync metadata is optional here too.
            wellId = if (attach) well.id else null,
            casingPressure = f.casingPressure,
            pumpTemperature = f.pumpTemperature,
            saturationTemperature = f.saturationTemperature,
            subcoolValue = subcool,
            isFlashing = subcool <= 10.0,
            capturedAt = f.capturedAt,
            sync = if (hasSync) {
                SyncMetadata(syncState = SyncState.PENDING, createdAt = f.capturedAt)
            } else {
                null
            },
        )
        SyncableCase.SubcoolReadingCase(well, reading)
    }

    // Non-empty photo payloads of varied length (1..512 bytes), as on iOS, so
    // the blob round-trip is a real byte-for-byte comparison.
    val photoBytes: Arb<ByteArray> =
        Arb.int(1, 512).map { length -> ByteArray(length) { (it * 31 + 7).toByte() } }

    val escalationCases: Arb<SyncableCase> = Arb.bind(
        wells,
        Arb.uuid(),
        Arb.bind(timestamps, Arb.enum<EscalationPriority>()) { at, p -> at to p },
        // hasFix drives both the coordinates and the locationUnavailable flag.
        Arb.bind(Arb.boolean(), Arb.double(-90.0, 90.0), Arb.double(-180.0, 180.0)) { hasFix, lat, lon ->
            LocationFix(hasFix, lat, lon)
        },
        photoBytes,
    ) { well, id, (capturedAt, priority), fix, bytes ->
        val escalation = Escalation(
            id = id,
            wellId = well.id,
            priority = priority,
            // The repository substitutes the real blob ref on save, so a
            // placeholder here is overwritten; the test maps the real ref in.
            photoRef = "placeholder",
            latitude = if (fix.hasFix) fix.latitude else null,
            longitude = if (fix.hasFix) fix.longitude else null,
            locationUnavailable = !fix.hasFix,
            capturedAt = capturedAt,
            sync = SyncMetadata(createdAt = capturedAt),
        )
        SyncableCase.EscalationCase(well, escalation, bytes)
    }

    // Draw a syncable case of one of the four types with equal weight so every
    // syncable record type is exercised across the 100+ iterations.
    val syncableCases: Arb<SyncableCase> = Arb.choice(
        wellTestCases,
        wellheadLogCases,
        subcoolReadingCases,
        escalationCases,
    )

    "Feature: sagd-welltest-mobile, Property 1: Persistence round-trip" {
        // A single blob store rooted at a fresh temp dir stands in for
        // app-internal storage for the whole run; refs are unique per write.
        val blobDir = createTempBlobDir()
        val blobStore = BlobStore(blobDir)
        try {
            checkAll(pbtConfig(), syncableCases) { case ->
                when (case) {
                    is SyncableCase.WellTestCase -> {
                        // Round-trip through the real Mappers: build the Room
                        // row, then rebuild the domain record from it.
                        val reloaded = case.record.toEntity().toDomain()
                        reloaded.capturedFieldsMatch(case.record) shouldBe true
                    }

                    is SyncableCase.WellheadLogCase -> {
                        val reloaded = case.record.toEntity().toDomain()
                        reloaded.capturedFieldsMatch(case.record) shouldBe true
                    }

                    is SyncableCase.SubcoolReadingCase -> {
                        // Saved verbatim, so the whole value — including its
                        // optional sync metadata — must round-trip exactly.
                        val reloaded = case.record.toEntity().toDomain()
                        reloaded shouldBe case.record
                    }

                    is SyncableCase.EscalationCase -> {
                        // The photo is written to the blob store first (as the
                        // repository does), yielding the ref the row carries.
                        val photoRef = blobStore.write(case.photoBytes)
                        val reloaded = case.record.toEntity(photoRef).toDomain()

                        reloaded.capturedFieldsMatch(case.record) shouldBe true

                        // Req 7.3: the captured photo must round-trip
                        // byte-for-byte via the reloaded row's photoRef.
                        val reloadedPhoto = blobStore.read(reloaded.photoRef)
                        (reloadedPhoto != null &&
                            reloadedPhoto.contentEquals(case.photoBytes)) shouldBe true
                    }
                }
            }
        } finally {
            blobDir.deleteRecursively()
        }
    }
})

// --- Supporting types and helpers (file-private) ---

/**
 * One generated syncable record of some type, plus the parent well it
 * associates with. Mirrors the iOS `SyncableCase.Record` enum.
 */
private sealed interface SyncableCase {
    data class WellTestCase(val well: Well, val record: WellTest) : SyncableCase
    data class WellheadLogCase(val well: Well, val record: WellheadLog) : SyncableCase
    data class SubcoolReadingCase(val well: Well, val record: SubcoolReading) : SyncableCase
    class EscalationCase(
        val well: Well,
        val record: Escalation,
        val photoBytes: ByteArray,
    ) : SyncableCase
}

/** Bundled well-test field draws (keeps the Arb.bind arity in range). */
private data class WellTestFields(
    val oil: Double,
    val water: Double,
    val gas: Double,
    val capturedAt: Instant,
)

/** Discriminated wellhead-log field groups; exactly one is populated per log. */
private sealed interface LogFields

private data class ProductionFields(
    val emulsionFlowRate: Double,
    val casingGasPressure: Double,
    val espMotorTemperature: Double,
    val waterCutPercent: Double,
) : LogFields

private data class InjectionFields(
    val steamInjectionPressure: Double,
    val steamTemperature: Double,
    val steamFlowRate: Double,
) : LogFields

/** Bundled subcool-reading field draws. */
private data class SubcoolFields(
    val casingPressure: Double,
    val pumpTemperature: Double,
    val saturationTemperature: Double,
    val capturedAt: Instant,
)

/** A location fix: either real decimal-degree coordinates, or unavailable. */
private data class LocationFix(
    val hasFix: Boolean,
    val latitude: Double,
    val longitude: Double,
)

/** Captured-field equality for a well test (ignores store-assigned sync metadata). */
private fun WellTest.capturedFieldsMatch(saved: WellTest): Boolean =
    id == saved.id &&
        wellId == saved.wellId &&
        oilRatio == saved.oilRatio &&
        waterRatio == saved.waterRatio &&
        gasRatio == saved.gasRatio &&
        capturedAt == saved.capturedAt

/** Captured-field equality for a wellhead log (ignores store-assigned sync metadata). */
private fun WellheadLog.capturedFieldsMatch(saved: WellheadLog): Boolean =
    id == saved.id &&
        wellId == saved.wellId &&
        logType == saved.logType &&
        capturedAt == saved.capturedAt &&
        emulsionFlowRate == saved.emulsionFlowRate &&
        casingGasPressure == saved.casingGasPressure &&
        espMotorTemperature == saved.espMotorTemperature &&
        waterCutPercent == saved.waterCutPercent &&
        steamInjectionPressure == saved.steamInjectionPressure &&
        steamTemperature == saved.steamTemperature &&
        steamFlowRate == saved.steamFlowRate

/**
 * Captured-field equality for an escalation. `photoRef` is store-assigned (the
 * blob ref) so it is deliberately excluded; the photo bytes are compared
 * separately against the blob store.
 */
private fun Escalation.capturedFieldsMatch(saved: Escalation): Boolean =
    id == saved.id &&
        wellId == saved.wellId &&
        priority == saved.priority &&
        latitude == saved.latitude &&
        longitude == saved.longitude &&
        locationUnavailable == saved.locationUnavailable &&
        capturedAt == saved.capturedAt

/** Fresh temp directory standing in for app-internal storage. */
private fun createTempBlobDir(): File =
    File.createTempFile("roundtrip-blobs-${UUID.randomUUID()}", "").let { marker ->
        marker.delete()
        marker.mkdirs()
        marker
    }
