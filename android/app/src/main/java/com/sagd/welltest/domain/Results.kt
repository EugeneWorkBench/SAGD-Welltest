/*
 * Result types returned by the repository and domain layers.
 *
 * Modeled as sealed hierarchies so callers must handle every outcome
 * exhaustively. Mirrors the iOS result enums exactly. See design.md
 * "Components and Interfaces" and "Offline-First Local Persistence".
 */
package com.sagd.welltest.domain

/**
 * Outcome of a transactional save (Req 1.1, 1.4, 1.5).
 *
 * - [Success] commits the record (syncState = pending).
 * - [StorageFull] is returned by the pre-write free-space check; nothing is
 *   written and the UI retains in-form data (Req 1.5).
 * - [Failure] signals an I/O error; the transaction rolled back and prior
 *   persisted state is untouched (Req 1.4).
 */
sealed interface SaveResult {
    data object Success : SaveResult

    data object StorageFull : SaveResult

    data class Failure(val reason: String) : SaveResult
}

/**
 * A single field-level validation error. [field] names the offending input so
 * the UI can place the message and retain the value (Req 3.5, 4.4, 5.5, 7.2);
 * [kind] mirrors the iOS `FieldError.Kind` so both platforms classify failures
 * identically, and [message] is a deterministic human-readable rendering of the
 * kind for the UI.
 *
 * Construct via [FieldError.of] so the [message] stays in lock-step with the
 * [kind]. [equals]/[hashCode] follow the data-class default over all three
 * properties; because [message] is a pure function of [kind], two errors with
 * the same [field] and [kind] are always equal.
 */
data class FieldError(
    val field: String,
    val kind: Kind,
    val message: String,
) {
    /**
     * Why a field failed validation. Mirrors the iOS `FieldError.Kind` cases and
     * their meaning exactly so the offending-field classification is identical on
     * both platforms.
     */
    sealed interface Kind {
        /** The field was empty or whitespace-only. */
        data object Empty : Kind

        /**
         * The field did not match the accepted numeric grammar (digits, at most
         * one decimal point, optional single leading minus).
         */
        data object NotNumeric : Kind

        /**
         * The field parsed as a number but fell outside its inclusive
         * `[min, max]` range.
         */
        data class OutOfRange(val min: Double, val max: Double) : Kind
    }

    companion object {
        /**
         * Builds a [FieldError] for [field] and [kind], deriving the [message]
         * from the kind so callers never hand-write (and risk diverging) the two.
         */
        fun of(field: String, kind: Kind): FieldError =
            FieldError(field = field, kind = kind, message = messageFor(kind))

        private fun messageFor(kind: Kind): String = when (kind) {
            Kind.Empty -> "This field is required."
            Kind.NotNumeric -> "Enter a number."
            is Kind.OutOfRange ->
                "Enter a value between ${format(kind.min)} and ${format(kind.max)}."
        }

        /** Renders a bound without a trailing ".0" for whole numbers. */
        private fun format(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}

/**
 * Outcome of validating a form (Req 3.2-3.5, 4.2-4.4, 7.2). Generic over the
 * [T] normalized record a successful validation produces, mirroring the iOS
 * `ValidationResult<Normalized>`.
 *
 * - [Valid] carries the normalized record (e.g. a `WellheadLog`); save may
 *   proceed.
 * - [Invalid] carries exactly the set of offending fields, one per field.
 */
sealed interface ValidationResult<out T> {
    data class Valid<T>(val value: T) : ValidationResult<T>

    data class Invalid(val errors: List<FieldError>) : ValidationResult<Nothing>
}

/**
 * Outcome of a subcool estimate (Req 5.1, 5.3, 5.4, 5.5).
 *
 * - [Success] carries the computed subcool value and the flashing-zone flag.
 * - [OutOfRange] means the pressure fell outside the steam-table domain (Req 5.4).
 * - [InvalidInput] names a non-numeric or empty input field (Req 5.5).
 */
sealed interface SubcoolResult {
    data class Success(
        val subcoolValue: Double,
        val isFlashing: Boolean,
    ) : SubcoolResult

    data class OutOfRange(val field: String) : SubcoolResult

    data class InvalidInput(val field: String) : SubcoolResult
}

/**
 * Outcome of a QR well lookup against the local store (Req 6.2, 6.3, 6.5, 6.6).
 *
 * - [Found] carries the well, its tests most-recent-first, and open tasks.
 * - [NotFound] means no local record exists and the device is online (Req 6.3).
 * - [UnavailableOffline] means no local record and the device is offline (Req 6.6).
 */
sealed interface WellLookupResult {
    data class Found(
        val well: Well,
        val wellTests: List<WellTest>,
        val openMaintenanceTasks: List<MaintenanceTask>,
    ) : WellLookupResult

    data object NotFound : WellLookupResult

    data object UnavailableOffline : WellLookupResult
}
