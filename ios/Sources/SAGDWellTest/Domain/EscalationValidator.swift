//
//  Escalation validator
//
//  Pure, platform-agnostic validator for the escalation capture form. An
//  escalation may be saved only when both a priority (High/Medium/Low) and an
//  associated well are present. When either selection is absent the validator
//  blocks the save and reports exactly the missing selections (one
//  `FieldError` per absent field, kind `.missingSelection`) so the UI can name
//  what is missing while retaining the operator's entered data. When both are
//  present it echoes back the validated input unchanged.
//
//  Deterministic over its input with no I/O. Implemented by task 10.1.
//
//  Requirements: 7.2.
//  Correctness property: 22 (escalation save requires priority and well).

import Foundation

// MARK: - Field identifiers

/// Stable field identifiers used in `FieldError.field` for escalation
/// selections, so the UI can map a missing-selection failure back to the
/// specific control and the offending-field set is deterministic and testable.
public enum EscalationField {
    /// The selected priority (High/Medium/Low).
    public static let priority = "priority"
    /// The associated well identifier.
    public static let wellId = "wellId"
}

// MARK: - Escalation validator

/// Validates an as-selected `EscalationInput`.
///
/// Both the priority and the well id are required. On success the validated
/// input is returned unchanged (its optional fields are known non-nil, but the
/// input is echoed as the normalized value so callers retain exactly what the
/// operator selected). On failure the result carries exactly the offending
/// fields — `priority` and/or `wellId` — each with kind `.missingSelection`,
/// in a fixed order (priority first, then well) so the set is deterministic.
///
/// A well id that is present but empty (or whitespace only) counts as absent,
/// matching the UI contract that "no well selected" and "blank well" are the
/// same missing selection.
public struct EscalationValidator: EscalationValidating {

    public init() {}

    public func validate(_ input: EscalationInput) -> ValidationResult<EscalationInput> {
        var errors: [FieldError] = []

        // Priority must be selected.
        if input.priority == nil {
            errors.append(FieldError(field: EscalationField.priority, kind: .missingSelection))
        }

        // Well must be selected; a nil or blank identifier is "not selected".
        let hasWell = input.wellId
            .map { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? false
        if !hasWell {
            errors.append(FieldError(field: EscalationField.wellId, kind: .missingSelection))
        }

        guard errors.isEmpty else {
            // Block the save and report exactly the absent selections; the UI
            // retains the operator's entered data.
            return .invalid(errors)
        }

        // Both selections present: echo the validated input back unchanged.
        return .valid(input)
    }
}
