/*
 * Escalation capture coordinator.
 *
 * Orchestrates the back half of the Geotagged Photo Escalation Flow (design.md),
 * composing three already-built pieces into the capture sequence:
 *
 *   1. Validate — [DefaultEscalationValidator] requires a priority (High/Medium/
 *      Low) and an associated well; a missing selection blocks the save and the
 *      caller retains the operator's data (Req 7.2).
 *   2. Geotag  — a bounded [LocationProvider] fix (30s budget) supplies decimal-
 *      degree lat/long + a UTC-second timestamp, or locationUnavailable on
 *      timeout so the operator proceeds (Req 7.1, 7.5).
 *   3. Persist — the assembled [Escalation] and its photo bytes go to the
 *      [EscalationRepository], which writes the blob file first and then the
 *      referencing row in a single transaction (Req 7.3, 7.4). That ordering and
 *      its crash-safety live in EscalationRepositoryImpl; this coordinator does
 *      not re-implement it.
 *
 * The capture timestamp (Req 7.1) comes from the location fix when a fix is
 * obtained, and otherwise from the same injected [LocationClock] so an
 * escalation always carries a UTC-second capture time even when location is
 * unavailable.
 *
 * Mirrors the iOS escalation-capture coordinator. Requirements: 7.1, 7.3, 7.4,
 * 7.5 (and 7.2 via the validator it drives).
 */
package com.sagd.welltest.integrations

import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationInput
import com.sagd.welltest.domain.EscalationRepository
import com.sagd.welltest.domain.EscalationValidator
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.ValidationResult
import java.time.temporal.ChronoUnit
import java.util.UUID

// region Result

/**
 * The outcome of a capture attempt, surfaced to the UI.
 *
 * - [Saved] — the escalation persisted; [locationUnavailable] tells the UI
 *   whether a GPS fix was attached, so it can note "location unavailable" when
 *   the fix timed out (Req 7.5).
 * - [Invalid] — validation blocked the save; [errors] names exactly the missing
 *   selections so the UI retains data and prompts for them (Req 7.2).
 * - [StorageFull] / [PersistenceFailed] — the repository rejected the write; the
 *   UI retains the entered data and leaves the escalation unsaved (Req 7.4, and
 *   Req 1.5 for storage-full).
 */
sealed interface EscalationCaptureResult {
    data class Saved(
        val escalationId: UUID,
        val locationUnavailable: Boolean,
    ) : EscalationCaptureResult

    data class Invalid(val errors: List<com.sagd.welltest.domain.FieldError>) :
        EscalationCaptureResult

    data object StorageFull : EscalationCaptureResult

    data class PersistenceFailed(val reason: String) : EscalationCaptureResult
}

// endregion

// region Coordinator

/**
 * Drives validate -> geotag -> persist for one escalation.
 *
 * @param validator the escalation validator ([DefaultEscalationValidator] in
 *   production); priority + well are both required (Req 7.2).
 * @param locationProvider the bounded GPS source (30s); production wires
 *   [FusedLocationProvider.create], tests inject a fake (Req 7.1, 7.5).
 * @param repository persists the escalation and its photo blob, blob-first then
 *   row, inside a transaction (Req 7.3, 7.4).
 * @param clock the capture-time source used when no GPS fix is obtained, so the
 *   escalation still carries a UTC-second timestamp (Req 7.1); shared with the
 *   bounded provider's clock in production for a single time source.
 */
class EscalationCaptureCoordinator(
    private val validator: EscalationValidator,
    private val locationProvider: LocationProvider,
    private val repository: EscalationRepository,
    private val clock: LocationClock = SystemLocationClock,
) {

    /**
     * Captures an escalation.
     *
     * Order matters: validation runs first so a missing priority/well is
     * reported without spending the 30-second location budget or touching the
     * disk (Req 7.2). On valid input a single bounded fix is requested; whether
     * or not it succeeds, the escalation is assembled and handed to the
     * repository, which persists it (blob first, then row) within the 5-second
     * budget (Req 7.3).
     *
     * @param input the as-selected form input (well, priority, and the photoRef
     *   placeholder the validator checks; the actual bytes come in [photoData]).
     * @param photoData the captured photo bytes to persist as the blob.
     */
    suspend fun capture(
        input: EscalationInput,
        photoData: ByteArray,
    ): EscalationCaptureResult {
        // (1) Validate before doing any slow or side-effecting work (Req 7.2).
        val validated =
            when (val result = validator.validate(input)) {
                is ValidationResult.Valid -> result.value
                is ValidationResult.Invalid -> return EscalationCaptureResult.Invalid(result.errors)
            }

        // Validation guarantees both selections are present; assert the contract
        // locally so the non-null assembly below is justified. Trim the same
        // character set the validator uses (spaces + tabs, matching iOS
        // `.whitespaces`) so a value the validator admitted is never judged blank
        // here — the two steps must agree on what "present" means.
        val priority =
            requireNotNull(validated.priority) { "validator admitted a null priority" }
        val wellId =
            requireNotNull(validated.wellId?.trim(' ', '\t')?.takeIf { it.isNotEmpty() }) {
                "validator admitted a blank wellId"
            }

        // (2) Attempt one bounded GPS fix (Req 7.1); on timeout proceed with
        // locationUnavailable (Req 7.5).
        val locationResult = locationProvider.currentFix()

        val latitude: Double?
        val longitude: Double?
        val locationUnavailable: Boolean
        val capturedAt =
            when (locationResult) {
                is LocationResult.Available -> {
                    latitude = locationResult.fix.latitude
                    longitude = locationResult.fix.longitude
                    locationUnavailable = false
                    locationResult.fix.timestamp
                }
                LocationResult.Unavailable -> {
                    latitude = null
                    longitude = null
                    locationUnavailable = true
                    // No fix, but the escalation still needs a UTC-second capture
                    // time (Req 7.1): stamp from the same clock, truncated.
                    clock.now().truncatedTo(ChronoUnit.SECONDS)
                }
            }

        // (3) Assemble and persist. The repository owns the blob-first/row
        // ordering, the single transaction, and the 5-second budget (Req 7.3,
        // 7.4). photoRef is set by the repository when it writes the blob, so a
        // placeholder here is overwritten; carry the validator's value if any.
        val escalation =
            Escalation(
                id = UUID.randomUUID(),
                wellId = wellId,
                priority = priority,
                photoRef = validated.photoRef ?: "",
                latitude = latitude,
                longitude = longitude,
                locationUnavailable = locationUnavailable,
                capturedAt = capturedAt,
                // createdAt drives the oldest-first push queue; use the same
                // capture instant so ordering matches capture order.
                sync = SyncMetadata(createdAt = capturedAt),
            )

        return when (val save = repository.saveEscalation(escalation, photoData)) {
            SaveResult.Success ->
                EscalationCaptureResult.Saved(
                    escalationId = escalation.id,
                    locationUnavailable = locationUnavailable,
                )
            SaveResult.StorageFull -> EscalationCaptureResult.StorageFull
            is SaveResult.Failure -> EscalationCaptureResult.PersistenceFailed(save.reason)
        }
    }
}

// endregion
