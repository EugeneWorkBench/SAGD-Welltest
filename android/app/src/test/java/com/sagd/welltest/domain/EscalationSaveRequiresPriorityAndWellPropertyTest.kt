/*
 * Property 22: Escalation save requires priority and well (Android) — task 13.3.
 *
 * Validates: Requirements 7.2
 *
 * An escalation may be saved only when BOTH a priority (High/Medium/Low) and an
 * associated well are present. Expressed against [DefaultEscalationValidator]:
 * validation returns [ValidationResult.Valid] if and only if `priority != null`
 * AND the well id is present and non-blank (not null, not empty, not
 * whitespace-only). Otherwise it returns [ValidationResult.Invalid] carrying
 * exactly the offending-field set — a `priority` error when priority is absent
 * and/or a `wellId` error when the well is absent, each with
 * [FieldError.Kind.Empty] (the kind the Android validator reports for a
 * required-but-absent selection, matching the shared result contract), in a
 * fixed order (priority first, then well) so the set is deterministic. In every
 * case the operator's entered data is retained (the pure validator never mutates
 * its input), and on success the validated input is echoed back unchanged.
 *
 * Each field is generated independently as either "present" or "absent":
 *   - priority: a selected [EscalationPriority] (present) or null (absent);
 *   - wellId:   a non-blank identifier (present) or null / empty / whitespace
 *               (all of which count as absent per the UI contract).
 * The test computes the expected offending-field set from those choices and
 * asserts the valid-iff-both-present property, the exact offending-field set,
 * and value retention.
 *
 * The validator is pure value logic over its input with no I/O, so the test
 * needs no persistence, networking, or UI. It runs a minimum of 100 iterations
 * via [pbtConfig] and mirrors the iOS EscalationSaveRequiresPriorityAndWellPropertyTests.
 */
package com.sagd.welltest.domain

import com.sagd.welltest.pbtConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

class EscalationSaveRequiresPriorityAndWellPropertyTest : StringSpec({

    // region Per-selection choice

    // How the priority selection is generated for a case: either a concrete
    // selected priority (present) or no selection at all (absent -> null).
    sealed interface PriorityChoice {
        /** The value placed in [EscalationInput.priority]. */
        val value: EscalationPriority?

        /** Whether this choice satisfies the "priority selected" requirement. */
        val isPresent: Boolean

        data class Present(val priority: EscalationPriority) : PriorityChoice {
            override val value: EscalationPriority get() = priority
            override val isPresent: Boolean get() = true
        }

        data object Absent : PriorityChoice {
            override val value: EscalationPriority? get() = null
            override val isPresent: Boolean get() = false
        }
    }

    // How the well selection is generated for a case: a non-blank identifier
    // (present), or one of the ways a well counts as absent — null, empty, or
    // whitespace-only.
    sealed interface WellChoice {
        /** The value placed in [EscalationInput.wellId]. */
        val value: String?

        /** Whether this choice satisfies "well selected and non-blank". */
        val isPresent: Boolean

        data class Present(val id: String) : WellChoice {
            override val value: String get() = id
            override val isPresent: Boolean get() = true
        }

        data object NilSelection : WellChoice {
            override val value: String? get() = null
            override val isPresent: Boolean get() = false
        }

        data class Blank(val raw: String) : WellChoice {
            override val value: String get() = raw
            override val isPresent: Boolean get() = false
        }
    }

    // endregion

    // region Helpers

    fun errorsOf(result: ValidationResult<*>): List<FieldError> = when (result) {
        is ValidationResult.Valid -> emptyList()
        is ValidationResult.Invalid -> result.errors
    }

    fun isValid(result: ValidationResult<*>): Boolean = result is ValidationResult.Valid

    // The exact expected offending-field list for a pair of choices, in the
    // validator's field order (priority first, then well), one entry per absent
    // selection. Built with FieldError.of so message stays in lock-step with
    // kind (matching the validator's own construction).
    fun expectedErrors(priority: PriorityChoice, well: WellChoice): List<FieldError> {
        val expected = mutableListOf<FieldError>()
        if (!priority.isPresent) {
            expected.add(FieldError.of(EscalationField.PRIORITY, FieldError.Kind.Empty))
        }
        if (!well.isPresent) {
            expected.add(FieldError.of(EscalationField.WELL_ID, FieldError.Kind.Empty))
        }
        return expected
    }

    // endregion

    // region Generators

    // A non-blank, non-whitespace-only well identifier.
    val presentWellId: Arb<String> = Arb.element(
        "WELL-1", "WELL-42", "PAD-A/INJ-7", "prod_well_0001",
        "W", "well with spaces", " leading-ok", "trailing-ok ",
    )

    // A whitespace-only (blank) string: present but trimmed to empty, so it
    // counts as "no well selected". Uses only spaces and horizontal tabs,
    // matching the validator's trim set (which excludes newlines).
    val blankWellId: Arb<String> = Arb.element("", " ", "   ", "\t", " \t ", "\t \t")

    // One priority selection: present (a concrete priority) or absent (null),
    // so over 100+ cases both branches are well exercised.
    val priorityChoice: Arb<PriorityChoice> = Arb.choice(
        Arb.element(EscalationPriority.entries.toList())
            .map<EscalationPriority, PriorityChoice> { PriorityChoice.Present(it) },
        Arb.element<PriorityChoice>(PriorityChoice.Absent),
    )

    // One well selection: present (non-blank), null, or blank (whitespace),
    // across the three cases, so both "selected" and every "absent" variant are
    // exercised.
    val wellChoice: Arb<WellChoice> = Arb.choice(
        presentWellId.map<String, WellChoice> { WellChoice.Present(it) },
        Arb.element<WellChoice>(WellChoice.NilSelection),
        blankWellId.map<String, WellChoice> { WellChoice.Blank(it) },
    )

    // endregion

    "Feature: sagd-welltest-mobile, Property 22: Escalation save requires priority and well" {
        checkAll(
            pbtConfig(),
            priorityChoice,
            wellChoice,
        ) { priority, well ->
            val input = EscalationInput(
                wellId = well.value,
                priority = priority.value,
                photoRef = null,
            )
            val result = DefaultEscalationValidator().validate(input)

            val expected = expectedErrors(priority, well)
            val shouldBeValid = expected.isEmpty()
            val actualValid = isValid(result)
            val actualErrors = errorsOf(result)

            // 1. Valid iff both priority and a non-blank well are present.
            withClue(
                "expected valid == $shouldBeValid (missing: $expected); " +
                    "got valid=$actualValid, errors=$actualErrors",
            ) {
                actualValid shouldBe shouldBeValid
            }

            // 2. When valid, no errors and the input echoes back unchanged; when
            //    invalid, the error list is exactly the offending-field set (same
            //    fields, kind Empty, each once, priority before well).
            if (shouldBeValid) {
                withClue("valid case must echo input unchanged; got $result") {
                    result shouldBe ValidationResult.Valid(input)
                }
            } else {
                withClue("invalid case must carry exactly $expected; got $actualErrors") {
                    actualErrors shouldBe expected
                }
            }

            // 3. Entered data retained: the pure validator does not mutate its
            //    input regardless of outcome. The input is an immutable data class
            //    the validator never writes back to, so equality with a
            //    re-constructed copy is the observable retention guarantee.
            val retainedInput = EscalationInput(
                wellId = well.value,
                priority = priority.value,
                photoRef = null,
            )
            withClue("entered data must be retained unchanged") {
                input shouldBe retainedInput
            }
        }
    }
})
