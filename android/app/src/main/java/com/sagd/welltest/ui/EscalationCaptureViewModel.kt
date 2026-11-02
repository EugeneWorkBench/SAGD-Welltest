/*
 * UI layer — escalation capture view model.
 *
 * Holds all logic for the escalation capture screen so the composable stays
 * thin: it renders [EscalationCaptureUiState] and forwards raw intents (select
 * a priority, select a well, attach a photo, tap Save). Mirrors the iOS task
 * 20.2 `EscalationCaptureViewModel`.
 *
 * Responsibilities (design.md "Geotagged Photo Escalation Flow"):
 *   - Keep the operator's selections (well, priority) and the attached photo
 *     bytes.
 *   - On Save, run the pure [DefaultEscalationValidator] first for immediate
 *     inline feedback on a missing priority/well, retaining the entered data
 *     (Req 7.2); only when both selections are present does it hand off to the
 *     [EscalationCaptureCoordinator], which revalidates, obtains a bounded GPS
 *     fix (Req 7.1), assembles, and persists the escalation and its photo.
 *   - Map the coordinator's [EscalationCaptureResult] onto a renderable
 *     [EscalationSubmissionState], including the saved confirmation with the
 *     geotag / location-unavailable state (Req 7.1).
 *
 * State is exposed as a single immutable [StateFlow]; saves run on an injectable
 * scope. Validation, geotagging, and persistence are the domain/integration
 * layers' concern — this class only orchestrates them and maps results.
 *
 * Implemented by task 21.2.
 * Requirements: 7.1, 7.2.
 */
package com.sagd.welltest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagd.welltest.domain.EscalationInput
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.EscalationValidator
import com.sagd.welltest.domain.FieldError
import com.sagd.welltest.domain.ValidationResult
import com.sagd.welltest.domain.Well
import com.sagd.welltest.integrations.EscalationCaptureCoordinator
import com.sagd.welltest.integrations.EscalationCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The outcome of a save attempt, mapped for the view from the coordinator's
 * [EscalationCaptureResult] and the validator's pre-check.
 */
sealed interface EscalationSubmissionState {
    /** No save attempted yet, or the form was edited after a prior attempt. */
    data object Editing : EscalationSubmissionState

    /** A save is in flight (GPS fix + persistence). */
    data object Saving : EscalationSubmissionState

    /**
     * Saved successfully. [locationUnavailable] tells the UI whether a GPS fix
     * was attached so it can note "location unavailable" when the fix timed out
     * (Req 7.1, 7.5).
     */
    data class Saved(
        val escalationId: UUID,
        val locationUnavailable: Boolean,
    ) : EscalationSubmissionState

    /**
     * Blocked by a missing selection; carries exactly the offending fields so
     * the view can mark them and retain entered data (Req 7.2).
     */
    data class Invalid(val errors: List<FieldError>) : EscalationSubmissionState

    /** Storage was full; the escalation is unsaved and selections are retained. */
    data object StorageFull : EscalationSubmissionState

    /** A persistence failure; the escalation is unsaved and selections retained. */
    data class Failed(val reason: String) : EscalationSubmissionState
}

/**
 * Immutable snapshot the escalation capture screen renders.
 *
 * @property selectedWellId selected well identifier, if any. Retained across a
 *   blocked save.
 * @property priority selected priority, if any. Retained across a blocked save.
 * @property hasPhoto whether a photo has been attached (required before a save
 *   is meaningful).
 * @property submissionState the outcome of the latest save attempt.
 */
data class EscalationCaptureUiState(
    val selectedWellId: String? = null,
    val priority: EscalationPriority? = null,
    val hasPhoto: Boolean = false,
    val submissionState: EscalationSubmissionState = EscalationSubmissionState.Editing,
) {
    /** The field identifiers currently flagged as missing, for inline marking. */
    val missingFields: Set<String>
        get() = (submissionState as? EscalationSubmissionState.Invalid)
            ?.errors?.map { it.field }?.toSet()
            ?: emptySet()

    /** Whether a save is in flight; the UI disables the Save control. */
    val isSaving: Boolean get() = submissionState is EscalationSubmissionState.Saving
}

/**
 * Drives the escalation capture screen.
 *
 * Photo acquisition is a platform concern: the screen hands captured bytes to
 * [attachPhoto], so the view model stays testable and free of camera plumbing.
 *
 * @param availableWells wells the operator can associate the escalation with
 *   (supplied by the caller, e.g. from recently used / pulled wells).
 * @param validator pre-checks priority + well for inline feedback (Req 7.2);
 *   the coordinator revalidates authoritatively.
 * @param coordinator validates, geotags, assembles, and persists the escalation
 *   and its photo (Req 7.1).
 * @param scope coroutine scope for save work; defaults to [viewModelScope].
 *   Injectable so tests drive saves on a test dispatcher.
 */
class EscalationCaptureViewModel(
    val availableWells: List<Well>,
    private val validator: EscalationValidator,
    private val coordinator: EscalationCaptureCoordinator,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private var photoData: ByteArray? = null

    private val _uiState = MutableStateFlow(EscalationCaptureUiState())

    /** The single source of UI truth for the screen. */
    val uiState: StateFlow<EscalationCaptureUiState> = _uiState.asStateFlow()

    // region Selections

    /** Select a priority and clear any stale validation marks. */
    fun selectPriority(priority: EscalationPriority) =
        _uiState.update { it.copy(priority = priority).markEdited() }

    /** Select an associated well and clear any stale validation marks. */
    fun selectWell(wellId: String?) =
        _uiState.update { it.copy(selectedWellId = wellId).markEdited() }

    /** Attach captured photo bytes and clear any stale validation marks. */
    fun attachPhoto(data: ByteArray) {
        photoData = data
        _uiState.update { it.copy(hasPhoto = true).markEdited() }
    }

    /**
     * Reset the submission state to [EscalationSubmissionState.Editing] when the
     * operator changes a selection after a prior attempt, so stale validation
     * marks and confirmations clear. A no-op while a save is in flight.
     */
    private fun EscalationCaptureUiState.markEdited(): EscalationCaptureUiState =
        when (submissionState) {
            EscalationSubmissionState.Editing, EscalationSubmissionState.Saving -> this
            else -> copy(submissionState = EscalationSubmissionState.Editing)
        }

    // endregion

    // region Save

    /**
     * Validate and save the escalation.
     *
     * Runs the validator first for immediate inline feedback on a missing
     * selection (Req 7.2); only when both selections are present does it hand
     * off to the coordinator (which revalidates, geotags, and persists). A save
     * requires an attached photo; without one the form stays in editing (the UI
     * disables Save until a photo is attached, so this is a defensive guard).
     */
    fun save() {
        val state = _uiState.value
        val input = EscalationInput(
            wellId = state.selectedWellId,
            priority = state.priority,
            photoRef = null,
        )

        // Inline pre-check: block and mark missing selections without starting
        // any capture work, retaining the operator's data (Req 7.2).
        when (val result = validator.validate(input)) {
            is ValidationResult.Invalid -> {
                _uiState.update { it.copy(submissionState = EscalationSubmissionState.Invalid(result.errors)) }
                return
            }
            is ValidationResult.Valid -> Unit
        }

        val photo = photoData ?: run {
            _uiState.update { it.copy(submissionState = EscalationSubmissionState.Editing) }
            return
        }

        _uiState.update { it.copy(submissionState = EscalationSubmissionState.Saving) }
        workScope.launch {
            val next = when (val capture = coordinator.capture(input, photo)) {
                is EscalationCaptureResult.Saved ->
                    EscalationSubmissionState.Saved(
                        escalationId = capture.escalationId,
                        locationUnavailable = capture.locationUnavailable,
                    )
                is EscalationCaptureResult.Invalid ->
                    EscalationSubmissionState.Invalid(capture.errors)
                EscalationCaptureResult.StorageFull ->
                    EscalationSubmissionState.StorageFull
                is EscalationCaptureResult.PersistenceFailed ->
                    EscalationSubmissionState.Failed(capture.reason)
            }
            _uiState.update { it.copy(submissionState = next) }
        }
    }

    // endregion

    companion object {
        /** A display label for a priority. */
        fun priorityLabel(priority: EscalationPriority): String = when (priority) {
            EscalationPriority.HIGH -> "High"
            EscalationPriority.MEDIUM -> "Medium"
            EscalationPriority.LOW -> "Low"
        }
    }
}
