/*
 * JSON encode/decode for the Cloud_Backend wire format.
 *
 * The project ships no JSON library (no kotlinx.serialization, Moshi, or Gson
 * in the version catalog — see android/gradle/libs.versions.toml), so this codec
 * is built on `org.json` which is part of the Android platform and adds no
 * dependency. That keeps task 23.1 self-contained and matches the iOS client,
 * which uses the built-in `JSONEncoder`/`JSONDecoder` rather than a third-party
 * lib.
 *
 * Instants are formatted with [DateTimeFormatter.ISO_INSTANT] — the exact same
 * representation the local store uses (localstore/Converters.kt) and the iOS
 * client's ISO-8601 encoding — so `capturedAt`/`updatedAt` round-trip
 * identically across store, wire, and both platforms.
 *
 * Only the fields named in the design's API contract are emitted. The wire key
 * for every enum is its lowercase form (`production`/`injection`,
 * `high`/`medium`/`low`, `open`/`closed`), matching the backend's documented
 * `logType`/`priority`/`status` values; the domain enums are UPPERCASE Kotlin
 * names, so the mapping is explicit here rather than relying on `.name`.
 *
 * Introduced by task 23.1.
 */
package com.sagd.welltest.network

import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.LogType
import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.SubcoolReading
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.domain.WellheadLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The parsed acknowledgment body returned by every push endpoint on success:
 * `{ "id": "<clientId>", "remoteId": "<serverId>", "status": "accepted" }`
 * (design.md "Acknowledgment contract").
 */
data class PushAcknowledgment(
    val id: String,
    val remoteId: String,
    val status: String,
)

/**
 * Stateless JSON mapper between the pure-domain model and the Cloud_Backend wire
 * format. Encoding targets the push-endpoint bodies; decoding targets the
 * acknowledgment contract and the pull-endpoint responses.
 */
object JsonCodec {

    private fun Instant.wire(): String = DateTimeFormatter.ISO_INSTANT.format(this)

    private fun parseInstant(text: String): Instant =
        Instant.from(DateTimeFormatter.ISO_INSTANT.parse(text))

    // ----- enum <-> wire -----

    private fun WellType.wire(): String = name.lowercase()

    private fun parseWellType(text: String): WellType = WellType.valueOf(text.uppercase())

    private fun LogType.wire(): String = name.lowercase()

    private fun EscalationPriority.wire(): String = name.lowercase()

    private fun parseStatus(text: String): MaintenanceStatus =
        MaintenanceStatus.valueOf(text.uppercase())

    // ----- push payload encoding -----

    /** `POST /v1/well-tests` body (Req 2.1). */
    fun encodeWellTest(test: WellTest): String =
        JSONObject()
            .put("id", test.id.toString())
            .put("wellId", test.wellId)
            .put("oilRatio", test.oilRatio)
            .put("waterRatio", test.waterRatio)
            .put("gasRatio", test.gasRatio)
            .put("capturedAt", test.capturedAt.wire())
            .toString()

    /**
     * `POST /v1/wellhead-logs` body, discriminated by `logType` (Req 3.6, 4.5).
     * Only the fields applicable to the log type are emitted; the mutually
     * exclusive null fields are omitted rather than sent as JSON null, matching
     * the iOS encoder which drops nil optionals.
     */
    fun encodeWellheadLog(log: WellheadLog): String {
        val json = JSONObject()
            .put("id", log.id.toString())
            .put("wellId", log.wellId)
            .put("logType", log.logType.wire())
            .put("capturedAt", log.capturedAt.wire())

        when (log.logType) {
            LogType.PRODUCTION -> {
                log.emulsionFlowRate?.let { json.put("emulsionFlowRate", it) }
                log.casingGasPressure?.let { json.put("casingGasPressure", it) }
                log.espMotorTemperature?.let { json.put("espMotorTemperature", it) }
                log.waterCutPercent?.let { json.put("waterCutPercent", it) }
            }

            LogType.INJECTION -> {
                log.steamInjectionPressure?.let { json.put("steamInjectionPressure", it) }
                log.steamTemperature?.let { json.put("steamTemperature", it) }
                log.steamFlowRate?.let { json.put("steamFlowRate", it) }
            }
        }
        return json.toString()
    }

    /** `POST /v1/subcool-readings` body (optional persistence). */
    fun encodeSubcoolReading(reading: SubcoolReading): String {
        val json = JSONObject()
            .put("id", reading.id.toString())
            .put("casingPressure", reading.casingPressure)
            .put("pumpTemperature", reading.pumpTemperature)
            .put("saturationTemperature", reading.saturationTemperature)
            .put("subcoolValue", reading.subcoolValue)
            .put("isFlashing", reading.isFlashing)
            .put("capturedAt", reading.capturedAt.wire())
        reading.wellId?.let { json.put("wellId", it) }
        return json.toString()
    }

    /**
     * The JSON `metadata` part of the multipart escalation push (Req 7.6). The
     * photo travels as the separate binary part, so `photoRef` (a local file
     * reference) is intentionally not sent. Geotag fields are omitted when the
     * fix was unavailable (Req 7.5).
     */
    fun encodeEscalationMetadata(escalation: Escalation): String {
        val json = JSONObject()
            .put("id", escalation.id.toString())
            .put("wellId", escalation.wellId)
            .put("priority", escalation.priority.wire())
            .put("locationUnavailable", escalation.locationUnavailable)
            .put("capturedAt", escalation.capturedAt.wire())
        escalation.latitude?.let { json.put("latitude", it) }
        escalation.longitude?.let { json.put("longitude", it) }
        return json.toString()
    }

    // ----- acknowledgment decoding -----

    /**
     * Parse the push acknowledgment contract. Returns null if the body is not
     * the expected shape (so the caller can treat a malformed 2xx as a failure
     * rather than fabricate a remoteId).
     */
    fun decodeAcknowledgment(body: String): PushAcknowledgment? =
        runCatching {
            val json = JSONObject(body)
            val remoteId = json.optString("remoteId", "")
            // A 2xx without a remoteId is not a valid acknowledgment; surface
            // null so the caller retries rather than fabricating an id.
            if (remoteId.isEmpty()) {
                null
            } else {
                PushAcknowledgment(
                    id = json.optString("id", ""),
                    remoteId = remoteId,
                    status = json.optString("status", ""),
                )
            }
        }.getOrNull()

    // ----- pull response decoding -----

    /** Parse a `GET /v1/wells/{id}` body into a [Well]. */
    fun decodeWell(body: String): Well = wellFrom(JSONObject(body))

    /** Parse a `GET /v1/wells?updatedSince=...` body into a list of [Well]. */
    fun decodeWells(body: String): List<Well> =
        JSONArray(body).objects().map(::wellFrom)

    /** Parse a `GET /v1/wells/{id}/well-tests?order=desc` body (newest-first). */
    fun decodeWellTests(body: String): List<WellTest> =
        JSONArray(body).objects().map(::wellTestFrom)

    /** Parse a `GET /v1/wells/{id}/maintenance-tasks?status=open` body. */
    fun decodeMaintenanceTasks(body: String): List<MaintenanceTask> =
        JSONArray(body).objects().map(::maintenanceTaskFrom)

    private fun wellFrom(json: JSONObject): Well =
        Well(
            id = json.getString("id"),
            name = json.getString("name"),
            type = parseWellType(json.getString("type")),
            padId = if (json.isNull("padId")) null else json.optString("padId"),
            updatedAt = parseInstant(json.getString("updatedAt")),
        )

    /**
     * A pulled well test is historical reference data: the backend owns it, so
     * its local [com.sagd.welltest.domain.SyncMetadata] is seeded as already
     * SYNCHRONIZED with the server id, created at its captured time. This keeps
     * cached pull data out of the push queue (its state is not PENDING).
     */
    private fun wellTestFrom(json: JSONObject): WellTest {
        val capturedAt = parseInstant(json.getString("capturedAt"))
        return WellTest(
            id = UUID.fromString(json.getString("id")),
            wellId = json.getString("wellId"),
            oilRatio = json.getDouble("oilRatio"),
            waterRatio = json.getDouble("waterRatio"),
            gasRatio = json.getDouble("gasRatio"),
            capturedAt = capturedAt,
            sync = SyncMetadata(
                syncState = SyncState.SYNCHRONIZED,
                createdAt = capturedAt,
                remoteId = json.optString("remoteId").ifEmpty { null },
            ),
        )
    }

    private fun maintenanceTaskFrom(json: JSONObject): MaintenanceTask =
        MaintenanceTask(
            id = UUID.fromString(json.getString("id")),
            wellId = json.getString("wellId"),
            description = json.getString("description"),
            status = parseStatus(json.getString("status")),
            createdAt = parseInstant(json.getString("createdAt")),
        )

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }
}
