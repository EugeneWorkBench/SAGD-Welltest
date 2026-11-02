//
//  Escalation capture screen (UI)
//
//  Implements the capture-form half of the design's "Geotagged Photo
//  Escalation Flow" for iOS. The operator selects a priority (High/Medium/Low),
//  selects an associated well, and attaches a photo, then taps Save. The screen
//  delegates to:
//
//    * `EscalationValidator` (via the view model) to pre-check the priority and
//      well selections so a missing selection is surfaced inline before any
//      capture work, retaining the entered selections (Req 7.2); and
//    * `EscalationCaptureCoordinator` to validate, obtain a bounded GPS fix,
//      assemble, and persist the escalation and its photo (Req 7.1, and the
//      persistence/outcome mapping behind it).
//
//  The screen is thin: `EscalationCaptureViewModel` owns the selections, the
//  photo bytes, and the mapped outcome; the view binds controls and renders
//  state. Photo acquisition itself is a platform concern injected as raw
//  `Data` (a camera/picker integration supplies it), so the screen stays
//  testable and free of capture-UI plumbing.
//
//  Implemented by task 20.2.
//
//  Requirements: 7.1, 7.2.

import Foundation

// MARK: - Submission state

/// The outcome of a save attempt, mapped for the view from the coordinator's
/// `EscalationCaptureResult` and the validator's pre-check.
public enum EscalationSubmissionState: Sendable, Equatable {
    /// No save attempted yet, or the form was edited after a prior attempt.
    case editing
    /// A save is in flight (GPS fix + persistence).
    case saving
    /// Saved successfully; carries the persisted escalation so the UI can
    /// confirm and show the geotag / location-unavailable state (Req 7.1).
    case saved(Escalation)
    /// Blocked by a missing selection; carries exactly the offending fields so
    /// the view can mark them and retain entered data (Req 7.2).
    case invalid([FieldError])
    /// Storage was full; the escalation is unsaved and selections are retained.
    case storageFull
    /// A persistence failure; the escalation is unsaved and selections retained.
    case failed(reason: String)
}

// MARK: - View model

/// Drives the escalation capture screen. Holds the operator's selections (well,
/// priority) and the attached photo bytes, runs the validator for inline
/// pre-check, and delegates a save to the `EscalationCaptureCoordinator`.
///
/// `@MainActor` because it is bound to SwiftUI; the save is async (it awaits a
/// bounded GPS fix and a persistence write).
@MainActor
public final class EscalationCaptureViewModel: ObservableObject {

    /// The wells the operator can associate the escalation with (supplied by
    /// the caller, e.g. from recently used / pulled wells).
    public let availableWells: [Well]

    /// Selected well identifier, if any. Retained across a blocked save.
    @Published public var selectedWellId: String?
    /// Selected priority, if any. Retained across a blocked save.
    @Published public var priority: EscalationPriority?
    /// Attached photo bytes, if any. A photo is required to save.
    @Published public var photoData: Data?
    /// The current submission state. Starts `.editing`.
    @Published public private(set) var submissionState: EscalationSubmissionState = .editing

    private let validator: EscalationValidating
    private let coordinator: EscalationCaptureCoordinator

    /// - Parameters:
    ///   - availableWells: Wells selectable for association.
    ///   - validator: Pre-checks priority + well for inline feedback (Req 7.2);
    ///     defaults to the standard `EscalationValidator`. The coordinator
    ///     revalidates authoritatively.
    ///   - coordinator: Validates, geotags, assembles, and persists the
    ///     escalation (Req 7.1).
    public init(
        availableWells: [Well],
        validator: EscalationValidating = EscalationValidator(),
        coordinator: EscalationCaptureCoordinator
    ) {
        self.availableWells = availableWells
        self.validator = validator
        self.coordinator = coordinator
    }

    /// The field identifiers currently flagged as missing, for inline marking.
    /// Empty unless the last save was blocked as `.invalid`.
    public var missingFields: Set<String> {
        if case let .invalid(errors) = submissionState {
            return Set(errors.map(\.field))
        }
        return []
    }

    /// Whether a photo has been attached (required before a save is meaningful).
    public var hasPhoto: Bool { photoData != nil }

    /// Reset the submission state to `.editing` when the operator changes a
    /// selection after a prior attempt, so stale validation marks clear.
    public func markEdited() {
        if case .editing = submissionState { return }
        if case .saving = submissionState { return }
        submissionState = .editing
    }

    /// Attach captured photo bytes.
    public func attachPhoto(_ data: Data) {
        photoData = data
        markEdited()
    }

    /// Validate and save the escalation.
    ///
    /// Runs the validator first for immediate inline feedback on a missing
    /// selection (Req 7.2); only when both selections are present does it hand
    /// off to the coordinator (which revalidates, geotags, and persists). A
    /// save requires an attached photo; without one the form stays in editing.
    public func save() async {
        let input = EscalationInput(wellId: selectedWellId, priority: priority)

        // Inline pre-check: block and mark missing selections without starting
        // any capture work, retaining the operator's data (Req 7.2).
        if case let .invalid(errors) = validator.validate(input) {
            submissionState = .invalid(errors)
            return
        }

        guard let photoData else {
            // A photo is required; keep editing (the view disables Save until a
            // photo is attached, so this is a defensive guard).
            submissionState = .editing
            return
        }

        submissionState = .saving
        switch await coordinator.capture(input: input, photoData: photoData) {
        case let .saved(escalation):
            submissionState = .saved(escalation)
        case let .invalid(errors):
            submissionState = .invalid(errors)
        case .storageFull:
            submissionState = .storageFull
        case let .failed(reason):
            submissionState = .failed(reason: reason)
        }
    }

    // MARK: - Presentation helpers (pure, view-agnostic)

    /// A display label for a priority.
    public static func priorityLabel(_ priority: EscalationPriority) -> String {
        switch priority {
        case .high: return "High"
        case .medium: return "Medium"
        case .low: return "Low"
        }
    }
}

#if canImport(SwiftUI)
import SwiftUI

// MARK: - View

/// Thin SwiftUI escalation capture form. Binds priority, well, and photo, and
/// renders the submission state including inline missing-selection marks
/// (Req 7.2) and the saved confirmation with the geotag state (Req 7.1).
public struct EscalationCaptureScreen: View {

    @ObservedObject private var viewModel: EscalationCaptureViewModel

    /// Supplies photo bytes when the operator taps "Attach photo". Injected so
    /// the platform camera/picker stays outside this screen. Returns `nil` if
    /// the operator cancels.
    private let capturePhoto: () async -> Data?

    public init(
        viewModel: EscalationCaptureViewModel,
        capturePhoto: @escaping () async -> Data?
    ) {
        self.viewModel = viewModel
        self.capturePhoto = capturePhoto
    }

    public var body: some View {
        Form {
            prioritySection
            wellSection
            photoSection
            saveSection
            statusSection
        }
        .navigationTitle("Escalation")
    }

    private var prioritySection: some View {
        Section("Priority") {
            Picker("Priority", selection: Binding(
                get: { viewModel.priority },
                set: { viewModel.priority = $0; viewModel.markEdited() }
            )) {
                Text("Select…").tag(EscalationPriority?.none)
                ForEach(EscalationPriority.allCases, id: \.self) { p in
                    Text(EscalationCaptureViewModel.priorityLabel(p))
                        .tag(EscalationPriority?.some(p))
                }
            }
            .pickerStyle(.segmented)
            if viewModel.missingFields.contains(EscalationField.priority) {
                validationMessage("Select a priority.")
            }
        }
    }

    private var wellSection: some View {
        Section("Well") {
            Picker("Well", selection: Binding(
                get: { viewModel.selectedWellId },
                set: { viewModel.selectedWellId = $0; viewModel.markEdited() }
            )) {
                Text("Select…").tag(String?.none)
                ForEach(viewModel.availableWells) { well in
                    Text("\(well.name) (\(well.id))").tag(String?.some(well.id))
                }
            }
            if viewModel.missingFields.contains(EscalationField.wellId) {
                validationMessage("Select a well.")
            }
        }
    }

    private var photoSection: some View {
        Section("Photo") {
            if viewModel.hasPhoto {
                Label("Photo attached", systemImage: "checkmark.circle")
                    .foregroundStyle(.green)
            }
            Button(viewModel.hasPhoto ? "Replace photo" : "Attach photo") {
                Task {
                    if let data = await capturePhoto() {
                        viewModel.attachPhoto(data)
                    }
                }
            }
        }
    }

    private var saveSection: some View {
        Section {
            Button("Save escalation") {
                Task { await viewModel.save() }
            }
            .disabled(!viewModel.hasPhoto || isSaving)
        }
    }

    @ViewBuilder
    private var statusSection: some View {
        switch viewModel.submissionState {
        case .editing:
            EmptyView()

        case .saving:
            Section {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Saving…").foregroundStyle(.secondary)
                }
            }

        case let .saved(escalation):
            Section {
                Label("Escalation saved", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
                if escalation.locationUnavailable {
                    Text("Location unavailable — saved without GPS coordinates.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else if let lat = escalation.latitude, let lon = escalation.longitude {
                    Text(String(format: "Geotagged at %.5f, %.5f", lat, lon))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

        case .invalid:
            // Inline marks are rendered within the priority/well sections.
            EmptyView()

        case .storageFull:
            Section {
                Label("Storage full — escalation not saved.", systemImage: "externaldrive.badge.xmark")
                    .foregroundStyle(.orange)
            }

        case let .failed(reason):
            Section {
                Label("Save failed: \(reason)", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            }
        }
    }

    private var isSaving: Bool {
        if case .saving = viewModel.submissionState { return true }
        return false
    }

    private func validationMessage(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.circle")
            .font(.footnote)
            .foregroundStyle(.orange)
    }
}
#endif
