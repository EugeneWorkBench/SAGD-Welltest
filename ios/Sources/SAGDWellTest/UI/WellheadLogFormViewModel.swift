//
//  UI layer — wellhead log form view model
//
//  Drives the production/injection wellhead log form. The view model holds the
//  as-entered field strings, runs the pure domain validators, maps a
//  `ValidationResult.invalid` onto per-field messages (retaining the operator's
//  entered values and blocking the save), calls the `LogRepository` only on a
//  valid input, and maps the repository's `SaveResult` onto a save-status the
//  view renders (success indicator vs storage-full / failure messages).
//
//  The view is thin: it binds its text fields to this view model and reads the
//  published state. All business rules live in the Domain layer (validators)
//  and the Repository layer (transactional, storage-aware saves) — this view
//  model only orchestrates them and shapes the result for display.
//
//  Implemented by task 20.1.
//  Requirements: 1.1 (success indicator), 1.5 (storage-full message),
//  3.1/4.1 (field set chosen by `Well.type`), 3.2–3.5 and 4.2–4.4 (per-field
//  validation, value retention, save blocking).

#if canImport(SwiftUI)
import Foundation
import SwiftUI

/// The outcome of the most recent save attempt, as the form should present it.
public enum WellheadLogSaveStatus: Equatable {
    /// No save has been attempted since the last edit.
    case idle
    /// A save is in flight.
    case saving
    /// The record was persisted; the view shows a success indicator (Req 1.1).
    case saved
    /// Device storage is full; nothing was written and entered values are
    /// retained (Req 1.5).
    case storageFull
    /// The save failed for another reason; entered values are retained (Req 1.4).
    case failure(String)
}

/// View model backing the wellhead log form for a single selected well.
///
/// The field set is chosen by `well.type`: a `.production` well shows emulsion
/// flow rate, casing gas pressure, ESP motor temperature, and water cut
/// percentage; an `.injection` well shows steam injection pressure, steam
/// temperature, and steam flow rate (Req 3.1, 4.1). Both paths funnel through
/// the same validate → save → map-result flow.
@MainActor
public final class WellheadLogFormViewModel: ObservableObject {

    // MARK: - Selected well

    /// The well whose log is being captured. Its `type` selects the form.
    public let well: Well

    // MARK: - As-entered fields (production)

    @Published public var emulsionFlowRate: String = ""
    @Published public var casingGasPressure: String = ""
    @Published public var espMotorTemperature: String = ""
    @Published public var waterCutPercent: String = ""

    // MARK: - As-entered fields (injection)

    @Published public var steamInjectionPressure: String = ""
    @Published public var steamTemperature: String = ""
    @Published public var steamFlowRate: String = ""

    // MARK: - Published UI state

    /// Per-field validation messages, keyed by the field identifier
    /// (`WellheadLogField.*`). Populated from the validator's offending-field
    /// set on a blocked save; empty when there is nothing to correct. The
    /// entered field strings are never cleared, so values are retained (Req
    /// 3.5, 4.4).
    @Published public private(set) var fieldErrors: [String: String] = [:]

    /// The outcome of the most recent save attempt.
    @Published public private(set) var saveStatus: WellheadLogSaveStatus = .idle

    // MARK: - Dependencies

    private let productionValidator: any ProductionLogValidating
    private let injectionValidator: any InjectionLogValidating
    private let logRepository: any LogRepository

    /// - Parameters:
    ///   - well: the selected well; `well.type` selects the form.
    ///   - logRepository: persists the validated log transactionally.
    ///   - productionValidator: validates a production input (injectable for tests).
    ///   - injectionValidator: validates an injection input (injectable for tests).
    public init(
        well: Well,
        logRepository: any LogRepository,
        productionValidator: any ProductionLogValidating = ProductionLogValidator(),
        injectionValidator: any InjectionLogValidating = InjectionLogValidator()
    ) {
        self.well = well
        self.logRepository = logRepository
        self.productionValidator = productionValidator
        self.injectionValidator = injectionValidator
    }

    // MARK: - Derived

    /// Whether a success indicator should be shown.
    public var didSave: Bool { saveStatus == .saved }

    /// A form-level error banner message for a save that was rejected by the
    /// store (storage-full or I/O failure). Field-level problems are surfaced
    /// per field via `fieldErrors`, not here.
    public var saveErrorMessage: String? {
        switch saveStatus {
        case .storageFull:
            return "Storage is full. The log was not saved; your entries are kept."
        case .failure(let reason):
            return "The log could not be saved: \(reason). Your entries are kept."
        case .idle, .saving, .saved:
            return nil
        }
    }

    /// The validation message for a specific field, if any.
    public func errorMessage(for field: String) -> String? {
        fieldErrors[field]
    }

    // MARK: - Editing

    /// Clears a transient save result when the operator edits any field, so a
    /// prior success/failure banner does not linger over fresh input. Field
    /// errors are left in place until the next save attempt re-evaluates them.
    public func markEdited() {
        if saveStatus != .idle {
            saveStatus = .idle
        }
    }

    // MARK: - Save

    /// Validate the current field set for `well.type`, and on success persist
    /// the normalized log through the repository.
    ///
    /// On an invalid input the save is blocked, `fieldErrors` is populated with
    /// exactly the offending fields, and the entered values are retained (Req
    /// 3.5, 4.4). On a valid input the repository is called and its
    /// `SaveResult` is mapped onto `saveStatus`: `.success` → `.saved` (the
    /// view shows a success indicator within 2 seconds — Req 1.1);
    /// `.storageFull` → `.storageFull` (Req 1.5); `.failure` → `.failure`
    /// (Req 1.4). In every non-success case the field strings are untouched.
    public func save() async {
        let result = validate()

        switch result {
        case .invalid(let errors):
            applyFieldErrors(errors)
            // Blocked: do not call the repository, retain values.
            return

        case .valid(let log):
            fieldErrors = [:]
            saveStatus = .saving
            let saveResult = await logRepository.saveWellheadLog(log)
            apply(saveResult)
        }
    }

    /// Runs the validator matching the selected well's type.
    private func validate() -> ValidationResult<WellheadLog> {
        switch well.type {
        case .production:
            return productionValidator.validate(
                ProductionLogInput(
                    wellId: well.id,
                    emulsionFlowRate: emulsionFlowRate,
                    casingGasPressure: casingGasPressure,
                    espMotorTemperature: espMotorTemperature,
                    waterCutPercent: waterCutPercent
                )
            )
        case .injection:
            return injectionValidator.validate(
                InjectionLogInput(
                    wellId: well.id,
                    steamInjectionPressure: steamInjectionPressure,
                    steamTemperature: steamTemperature,
                    steamFlowRate: steamFlowRate
                )
            )
        }
    }

    /// Maps the offending-field set onto per-field messages and blocks the save.
    private func applyFieldErrors(_ errors: [FieldError]) {
        var messages: [String: String] = [:]
        for error in errors {
            messages[error.field] = FieldErrorMessage.text(for: error.kind)
        }
        fieldErrors = messages
        saveStatus = .idle
    }

    /// Maps a repository `SaveResult` onto the published save status.
    private func apply(_ result: SaveResult) {
        switch result {
        case .success:
            saveStatus = .saved
        case .storageFull:
            saveStatus = .storageFull
        case .failure(let reason):
            saveStatus = .failure(reason)
        }
    }
}
#endif
