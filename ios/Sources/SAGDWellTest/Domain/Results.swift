//
//  Result types
//
//  Discriminated result enums returned by the domain and repository layers.
//  Each mirrors the design document's Components/Interfaces section exactly.
//
//  Introduced by task 1.2.

import Foundation

// MARK: - SaveResult

/// The outcome of a transactional save through the repository layer.
///
/// `StorageFull` is distinguished from a generic `Failure` so the UI can show
/// a dedicated storage-full message and retain in-form data. All saves are
/// transactional: a `Failure` leaves the prior persisted state untouched.
public enum SaveResult: Sendable, Equatable {
    /// The record was committed (`syncState = pending`, `createdAt = now`,
    /// `retryCount = 0`).
    case success
    /// Insufficient free storage; nothing was written and form data is retained.
    case storageFull
    /// An I/O failure rolled the transaction back; the reason is diagnostic.
    case failure(reason: String)
}

// MARK: - FieldError

/// A single field-level validation failure: which field failed and why.
public struct FieldError: Sendable, Equatable, Hashable {
    /// The reason a field failed validation.
    public enum Kind: Sendable, Equatable, Hashable {
        /// The field was empty but a value is required.
        case empty
        /// The field is not a valid number (digits, at most one decimal point,
        /// optional leading minus).
        case notNumeric
        /// The field parsed as a number but falls outside its allowed range.
        case outOfRange(min: Double, max: Double)
        /// A required selection (e.g. priority, well) was not made.
        case missingSelection
    }

    /// Identifier of the offending field (e.g. "waterCutPercent").
    public var field: String
    /// Why the field failed.
    public var kind: Kind

    public init(field: String, kind: Kind) {
        self.field = field
        self.kind = kind
    }
}

// MARK: - ValidationResult

/// The outcome of validating a form's field set.
///
/// On `valid`, carries the normalized record parsed from the valid input
/// (`Normalized` is the concrete record type a given validator produces, e.g.
/// `WellheadLog` or `Escalation`). On `invalid`, the error list contains
/// exactly the offending fields so the UI can show per-field messages, retain
/// entered values, and block the save.
public enum ValidationResult<Normalized: Sendable & Equatable>: Sendable, Equatable {
    /// All fields valid; carries the normalized record.
    case valid(Normalized)
    /// One or more fields failed; carries exactly the offending fields.
    case invalid([FieldError])
}

// MARK: - SubcoolResult

/// The outcome of a subcool estimate.
public enum SubcoolResult: Sendable, Equatable {
    /// A computed subcool value and whether it falls in the flashing zone.
    case success(subcool: Double, isFlashing: Bool)
    /// The casing pressure is outside the steam-table domain; carries the
    /// offending field name.
    case outOfRange(field: String)
    /// An input was empty or non-numeric; carries the offending field name.
    case invalidInput(field: String)
}

// MARK: - WellLookupResult

/// The outcome of a QR / well-id lookup against the local store.
public enum WellLookupResult: Sendable, Equatable {
    /// The well exists locally; carries the well, its tests (most-recent
    /// first), and its open maintenance tasks.
    case found(well: Well, wellTests: [WellTest], openMaintenanceTasks: [MaintenanceTask])
    /// The well was not found locally and the device is online.
    case notFound
    /// The well was not found locally and the device is offline.
    case unavailableOffline
}
