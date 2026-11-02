/*
 * UI layer — field validation message formatting.
 *
 * Maps a domain [FieldError.Kind] onto the operator-facing validation message
 * the forms display beneath each input, mirroring the iOS `FieldErrorMessage`
 * exactly. Message wording is a presentation concern that lives in the UI
 * layer; the *decision* that a field is invalid (and why) is made by the pure
 * domain validators (`DefaultProductionLogValidator` /
 * `DefaultInjectionLogValidator`). Keeping only the wording here means the
 * composables and the view model stay free of range literals and grammar rules.
 *
 * The range bounds embedded in an out-of-range message come straight from the
 * validator's [FieldError], so the message always agrees with the rule that
 * rejected the value — there is no second, drifting copy of the bounds in the
 * UI.
 *
 * Implemented by task 21.1.
 * Requirements: 3.2, 3.3, 3.4, 4.2, 4.3 (per-field validation messages).
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.FieldError

/** Formats domain validation failures into operator-facing text. */
object FieldErrorMessage {

    /**
     * A human-readable validation message for a single field error.
     *
     * - [FieldError.Kind.Empty] → the field is required (Req 3.5, 4.4).
     * - [FieldError.Kind.NotNumeric] → only numeric input is accepted
     *   (Req 3.3, 4.2).
     * - [FieldError.Kind.OutOfRange] → the accepted range, naming the inclusive
     *   bounds (Req 3.2, 3.4, 4.3).
     */
    fun text(error: FieldError): String = text(error.kind)

    /** As [text], from a bare [FieldError.Kind]. */
    fun text(kind: FieldError.Kind): String = when (kind) {
        FieldError.Kind.Empty ->
            "This field is required."
        FieldError.Kind.NotNumeric ->
            "Enter a numeric value (digits, one optional decimal point, optional leading minus)."
        is FieldError.Kind.OutOfRange ->
            "Enter a value from ${number(kind.min)} to ${number(kind.max)}."
    }

    /**
     * Formats a bound without a trailing ".0" for whole numbers and with
     * grouping separators so large bounds (e.g. 999,999.99) read naturally,
     * matching the iOS en_US_POSIX decimal formatting.
     */
    private fun number(value: Double): String {
        val isWhole = value == value.toLong().toDouble()
        return if (isWhole) {
            "%,d".format(value.toLong())
        } else {
            // Up to two fraction digits, grouped thousands.
            "%,.2f".format(value).trimEnd('0').trimEnd('.')
        }
    }
}
