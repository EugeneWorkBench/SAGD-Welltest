/*
 * Escalation validator.
 *
 * Pure, platform-agnostic validator for the escalation capture form. An
 * escalation may be saved only when both a priority (High/Medium/Low) and an
 * associated well are present. When either selection is absent the validator
 * blocks the save and reports exactly the missing selections (one [FieldError]
 * per absent field) so the UI can name what is missing while retaining the
 * operator's entered data. When both are present it echoes back the validated
 * input unchanged.
 *
 * Mirrors the iOS `EscalationValidator.swift` behavior exactly. Deterministic
 * over its input with no I/O. Implemented by task 13.1.
 *
 * The iOS validator classifies each absent selection with the
 * `FieldError.Kind.missingSelection` case. The Android [FieldError.Kind] (the
 * shared result contract from task 2.2) models a required-but-absent value as
 * [FieldError.Kind.Empty], so missing escalation selections use that kind here;
 * the behavior — block the save, report exactly the absent fields, retain data —
 * matches iOS.
 *
 * Requirements: 7.2.
 * Correctness property: 22 (escalation save requires priority and well).
 */
package com.sagd.welltest.domain

// region Field identifiers

/**
 * Stable field identifiers used in [FieldError.field] for escalation selections,
 * so the UI can map a missing-selection failure back to the specific control and
 * the offending-field set is deterministic and testable. These strings match the
 * iOS `EscalationField` constants verbatim.
 */
object EscalationField {
    /** The selected priority (High/Medium/Low). */
    const val PRIORITY: String = "priority"

    /** The associated well identifier. */
    const val WELL_ID: String = "wellId"
}

// endregion

// region Escalation validator

/**
 * Validates an as-selected [EscalationInput].
 *
 * Both the priority and the well id are required. On success the validated input
 * is returned unchanged (its optional fields are known non-null, but the input is
 * echoed as the normalized value so callers retain exactly what the operator
 * selected). On failure the result carries exactly the offending fields —
 * `priority` and/or `wellId` — each reported as [FieldError.Kind.Empty], in a
 * fixed order (priority first, then well) so the set is deterministic.
 *
 * A well id that is present but empty (or whitespace only) counts as absent,
 * matching the UI contract that "no well selected" and "blank well" are the same
 * missing selection.
 */
class DefaultEscalationValidator : EscalationValidator {

    override fun validate(input: EscalationInput): ValidationResult<EscalationInput> {
        val errors = mutableListOf<FieldError>()

        // Priority must be selected.
        if (input.priority == null) {
            errors.add(FieldError.of(EscalationField.PRIORITY, FieldError.Kind.Empty))
        }

        // Well must be selected; a null or blank identifier is "not selected".
        // Trim only spaces and horizontal tabs, matching the iOS `.whitespaces`
        // character set (which excludes newlines) so "blank" is judged
        // identically on both platforms.
        val hasWell = input.wellId?.trim(' ', '\t')?.isNotEmpty() ?: false
        if (!hasWell) {
            errors.add(FieldError.of(EscalationField.WELL_ID, FieldError.Kind.Empty))
        }

        if (errors.isNotEmpty()) {
            // Block the save and report exactly the absent selections; the UI
            // retains the operator's entered data.
            return ValidationResult.Invalid(errors)
        }

        // Both selections present: echo the validated input back unchanged.
        return ValidationResult.Valid(input)
    }
}

// endregion
