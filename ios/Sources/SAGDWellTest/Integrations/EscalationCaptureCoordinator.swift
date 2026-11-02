//
//  Escalation capture coordinator
//
//  Orchestrates the capture half of the design's "Geotagged Photo Escalation
//  Flow": it validates the operator's selections, obtains (or times out on) a
//  GPS fix, assembles the `Escalation`, and persists it through the
//  `EscalationRepository`.
//
//  The steps follow the flow exactly:
//    1. Validate priority + well via `EscalationValidator`. A missing selection
//       blocks the capture and reports exactly the absent fields, retaining the
//       operator's data (Req 7.2) — nothing is written and no location is
//       requested.
//    2. Request a single bounded location fix. A fix attaches decimal-degree
//       latitude/longitude and a UTC one-second timestamp (Req 7.1); a 30-second
//       timeout records `locationUnavailable` and proceeds (Req 7.5).
//    3. Persist the escalation and its photo through the repository, which
//       writes the blob first and then the row within the 5-second budget
//       (Req 7.3); a persistence failure leaves the escalation unsaved with a
//       distinguishable result (Req 7.4).
//
//  The coordinator holds no platform dependencies directly — the location
//  provider and repository are injected behind their protocol seams — so it is
//  exercised end-to-end with a fake provider and an in-memory repository.
//
//  Implemented by task 18.2.
//
//  Requirements: 7.1, 7.3, 7.4, 7.5.

import Foundation

// MARK: - Capture outcome

/// The outcome of an escalation capture attempt.
///
/// `saved` carries the assembled escalation as persisted (including the GPS
/// fix or the `locationUnavailable` flag) so the UI can confirm the save and
/// show the geotag. `invalid` carries exactly the absent selections so the UI
/// can name what is missing and retain the operator's data (Req 7.2).
/// `storageFull` and `failed` mirror the repository's save outcomes so the UI
/// can keep the escalation unsaved with the entered data retained (Req 7.4).
public enum EscalationCaptureResult: Sendable, Equatable {
    /// Validated, geotagged (or marked location-unavailable), and persisted.
    case saved(Escalation)
    /// A required selection was missing; carries the offending fields.
    case invalid([FieldError])
    /// Insufficient storage to persist the escalation and its photo.
    case storageFull
    /// A persistence I/O failure; the escalation is left unsaved.
    case failed(reason: String)
}

// MARK: - Coordinator

/// Coordinates validation, geotagging, and persistence for a single escalation
/// capture. Stateless between calls; each `capture` is independent.
public struct EscalationCaptureCoordinator: Sendable {

    private let validator: EscalationValidating
    private let locationProvider: LocationProviding
    private let repository: EscalationRepository
    private let now: @Sendable () -> Date

    /// - Parameters:
    ///   - validator: Checks priority + well presence (Req 7.2); defaults to
    ///     the standard `EscalationValidator`.
    ///   - locationProvider: Supplies the bounded GPS fix (Req 7.1, 7.5).
    ///   - repository: Persists the escalation and photo blob (Req 7.3, 7.4).
    ///   - now: Supplies the capture instant stamped onto the escalation when
    ///     no fix is available; defaults to the wall clock.
    public init(
        validator: EscalationValidating = EscalationValidator(),
        locationProvider: LocationProviding,
        repository: EscalationRepository,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.validator = validator
        self.locationProvider = locationProvider
        self.repository = repository
        self.now = now
    }

    /// Capture an escalation: validate, geotag, assemble, and persist.
    ///
    /// - Parameters:
    ///   - input: The operator's selected priority and well.
    ///   - photoData: The captured photo bytes, written to the blob store by
    ///     the repository before the referencing row.
    /// - Returns: The capture outcome.
    public func capture(
        input: EscalationInput,
        photoData: Data
    ) async -> EscalationCaptureResult {
        // 1. Validate selections first. A missing priority or well blocks the
        //    capture and names the offending fields; no location is requested
        //    and nothing is written, so the operator's data is retained (Req 7.2).
        let priority: EscalationPriority
        let wellId: String
        switch validator.validate(input) {
        case .invalid(let errors):
            return .invalid(errors)
        case .valid(let validated):
            // A `.valid` result guarantees both selections are present; treat a
            // nil (which the validator never returns on success) as missing.
            guard let p = validated.priority, let w = validated.wellId else {
                return .invalid([
                    FieldError(field: EscalationField.priority, kind: .missingSelection),
                    FieldError(field: EscalationField.wellId, kind: .missingSelection)
                ])
            }
            priority = p
            wellId = w
        }

        // 2. Request a bounded location fix. A fix carries decimal-degree
        //    coordinates and its own UTC one-second timestamp (Req 7.1); a
        //    timeout records `locationUnavailable` and proceeds (Req 7.5).
        let locationResult = await locationProvider.requestFix()
        let latitude: Double?
        let longitude: Double?
        let locationUnavailable: Bool
        let capturedAt: Date
        switch locationResult {
        case .fix(let fix):
            latitude = fix.latitude
            longitude = fix.longitude
            locationUnavailable = false
            capturedAt = fix.capturedAt
        case .timedOut:
            latitude = nil
            longitude = nil
            locationUnavailable = true
            capturedAt = utcSecondTruncated(now())
        }

        // 3. Assemble the escalation. The repository owns `sync` and `photoRef`
        //    (blob-first persistence), so `photoRef` is a placeholder it
        //    overwrites with the stored blob key.
        let escalation = Escalation(
            wellId: wellId,
            priority: priority,
            photoRef: "",
            latitude: latitude,
            longitude: longitude,
            locationUnavailable: locationUnavailable,
            capturedAt: capturedAt
        )

        // 4. Persist through the repository: blob first, then row, within the
        //    5-second budget (Req 7.3). Map the save outcome to the capture
        //    result so a failure leaves the escalation unsaved (Req 7.4).
        switch await repository.saveEscalation(escalation, photoData: photoData) {
        case .success:
            return .saved(escalation)
        case .storageFull:
            return .storageFull
        case .failure(let reason):
            return .failed(reason: reason)
        }
    }
}
