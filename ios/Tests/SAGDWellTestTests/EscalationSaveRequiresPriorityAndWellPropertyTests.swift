//
//  EscalationSaveRequiresPriorityAndWellPropertyTests.swift
//
//  Property 22: Escalation save requires priority and well.
//
//  An escalation may be saved only when BOTH a priority (High/Medium/Low) and
//  an associated well are present. Expressed against `EscalationValidator`:
//  validation returns `.valid` if and only if `priority != nil` AND the well id
//  is present and non-blank (not nil, not empty, not whitespace-only).
//  Otherwise it returns `.invalid` carrying exactly the missing-selection
//  `FieldError` set — a `priority` error when priority is absent and/or a
//  `wellId` error when the well is absent, each with kind `.missingSelection`,
//  in a fixed order (priority first, then well) so the set is deterministic. In
//  every case the operator's entered data is retained (the pure validator never
//  mutates its input), and on success the validated input is echoed unchanged.
//
//  Each field is generated independently as either "present" or "absent":
//    * priority: a selected `EscalationPriority` (present) or nil (absent);
//    * wellId:   a non-blank identifier (present) or nil / empty / whitespace
//                (all of which count as absent per the UI contract).
//  The test computes the expected missing-selection set from those choices and
//  asserts the valid-iff-both-present property, the exact offending-field set,
//  and value retention.
//
//  Validates: Requirements 7.2.
//
//  Implements task 10.3. Runs a minimum of 100 iterations via `pbtArguments()`,
//  tagged `Feature: sagd-welltest-mobile, Property 22: ...`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class EscalationSaveRequiresPriorityAndWellPropertyTests: XCTestCase {

    // MARK: - Per-selection choice

    /// How the priority selection is generated for a case: either a concrete
    /// selected priority (present) or no selection at all (absent → nil).
    private enum PriorityChoice: Equatable {
        case present(EscalationPriority)
        case absent

        /// The value placed in `EscalationInput.priority`.
        var value: EscalationPriority? {
            switch self {
            case .present(let p): return p
            case .absent: return nil
            }
        }

        /// Whether this choice satisfies the "priority selected" requirement.
        var isPresent: Bool {
            if case .present = self { return true }
            return false
        }
    }

    /// How the well selection is generated for a case: a non-blank identifier
    /// (present), or one of the three ways a well counts as absent — nil,
    /// empty, or whitespace-only.
    private enum WellChoice: Equatable {
        case present(String)
        case nilSelection
        case blank(String)

        /// The value placed in `EscalationInput.wellId`.
        var value: String? {
            switch self {
            case .present(let s): return s
            case .nilSelection: return nil
            case .blank(let s): return s
            }
        }

        /// Whether this choice satisfies the "well selected and non-blank"
        /// requirement.
        var isPresent: Bool {
            if case .present = self { return true }
            return false
        }
    }

    // MARK: - Helpers

    private static func errors<T>(_ result: ValidationResult<T>) -> [FieldError] {
        switch result {
        case .valid: return []
        case .invalid(let e): return e
        }
    }

    private static func isValid<T>(_ result: ValidationResult<T>) -> Bool {
        if case .valid = result { return true }
        return false
    }

    /// The exact expected missing-selection error list for a pair of choices,
    /// in the validator's field order (priority first, then well), one entry
    /// per absent selection.
    private static func expectedErrors(
        priority: PriorityChoice,
        well: WellChoice
    ) -> [FieldError] {
        var expected: [FieldError] = []
        if !priority.isPresent {
            expected.append(FieldError(field: EscalationField.priority, kind: .missingSelection))
        }
        if !well.isPresent {
            expected.append(FieldError(field: EscalationField.wellId, kind: .missingSelection))
        }
        return expected
    }

    // MARK: - Generators

    /// A non-blank, non-whitespace-only well identifier.
    private static let presentWellId: Gen<String> =
        Gen<String>.fromElements(of: [
            "WELL-1", "WELL-42", "PAD-A/INJ-7", "prod_well_0001",
            "W", "well with spaces", " leading-ok", "trailing-ok ",
        ])

    /// A whitespace-only (blank) string: present but trimmed to empty, so it
    /// counts as "no well selected".
    private static let blankWellId: Gen<String> =
        Gen<String>.fromElements(of: ["", " ", "   ", "\t", " \t ", "\t \t"])

    /// One priority selection: present (a concrete priority) or absent (nil),
    /// uniformly, so over 100+ cases both branches are well exercised.
    private static let priorityChoice: Gen<PriorityChoice> =
        Gen<PriorityChoice>.one(of: [
            Gen<EscalationPriority>
                .fromElements(of: EscalationPriority.allCases)
                .map { PriorityChoice.present($0) },
            Gen<PriorityChoice>.fromElements(of: [.absent]),
        ])

    /// One well selection: present (non-blank), nil, or blank (whitespace),
    /// uniformly across the three cases, so both "selected" and every "absent"
    /// variant are exercised.
    private static let wellChoice: Gen<WellChoice> =
        Gen<WellChoice>.one(of: [
            presentWellId.map { WellChoice.present($0) },
            Gen<WellChoice>.fromElements(of: [.nilSelection]),
            blankWellId.map { WellChoice.blank($0) },
        ])

    // MARK: - Property 22

    func testEscalationSaveRequiresPriorityAndWell() {
        property(
            propertyName(22, "escalation save requires priority and well"),
            arguments: pbtArguments()
        ) <- forAll(Self.priorityChoice, Self.wellChoice) {
            (priority: PriorityChoice, well: WellChoice) in

            let input = EscalationInput(wellId: well.value, priority: priority.value)
            let result = EscalationValidator().validate(input)

            let expected = Self.expectedErrors(priority: priority, well: well)
            let shouldBeValid = expected.isEmpty
            let actualValid = Self.isValid(result)
            let actualErrors = Self.errors(result)

            // 1. `.valid` iff both priority and a non-blank well are present.
            let validIff = (actualValid == shouldBeValid)
                <?> "expected .valid == \(shouldBeValid) (missing: \(expected)); got valid=\(actualValid), errors=\(actualErrors)"

            // 2. When invalid, the error list is exactly the missing-selection
            //    set (same fields, kind `.missingSelection`, each once, priority
            //    before well). When valid, no errors and the input echoes back
            //    unchanged.
            let payload: Property
            if shouldBeValid {
                let echoed: Bool
                if case .valid(let normalized) = result {
                    echoed = (normalized == input)
                } else {
                    echoed = false
                }
                payload = (actualErrors.isEmpty && echoed)
                    <?> "valid case must carry no errors and echo input unchanged; errors=\(actualErrors)"
            } else {
                payload = (actualErrors == expected)
                    <?> "invalid case must carry exactly \(expected); got \(actualErrors)"
            }

            // 3. Entered data retained: the pure validator does not mutate its
            //    input regardless of outcome.
            let retainedInput = EscalationInput(wellId: well.value, priority: priority.value)
            let retained = (input == retainedInput)
                <?> "entered data must be retained unchanged"

            return validIff ^&&^ payload ^&&^ retained
        }
    }
}
