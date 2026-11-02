//
//  UI layer — wellhead log form view
//
//  A thin SwiftUI form for capturing a wellhead log against a selected well.
//  The field set is chosen by `well.type` (Req 3.1, 4.1): a production well
//  shows the four production fields, an injection well shows the three steam
//  fields. Each field binds to the view model's as-entered string and renders
//  its per-field validation message beneath it when a save was blocked. The
//  view carries no business rules — it reads published state and forwards the
//  Save action to the view model.
//
//  Implemented by task 20.1.
//  Requirements: 1.1, 1.5, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4.

#if canImport(SwiftUI)
import SwiftUI

/// Captures a production or injection wellhead log for a single well.
public struct WellheadLogFormView: View {

    @ObservedObject private var viewModel: WellheadLogFormViewModel

    public init(viewModel: WellheadLogFormViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        Form {
            Section {
                fields
            } header: {
                Text(headerTitle)
            }

            Section {
                saveButton
                statusBanner
            }
        }
        .navigationTitle(viewModel.well.name)
    }

    // MARK: - Fields (chosen by well type)

    @ViewBuilder
    private var fields: some View {
        switch viewModel.well.type {
        case .production:
            NumericFieldRow(
                title: "Emulsion flow rate",
                text: binding(\.emulsionFlowRate),
                message: viewModel.errorMessage(for: WellheadLogField.emulsionFlowRate)
            )
            NumericFieldRow(
                title: "Casing gas pressure",
                text: binding(\.casingGasPressure),
                message: viewModel.errorMessage(for: WellheadLogField.casingGasPressure)
            )
            NumericFieldRow(
                title: "ESP motor temperature",
                text: binding(\.espMotorTemperature),
                message: viewModel.errorMessage(for: WellheadLogField.espMotorTemperature)
            )
            NumericFieldRow(
                title: "Water cut (%)",
                text: binding(\.waterCutPercent),
                message: viewModel.errorMessage(for: WellheadLogField.waterCutPercent)
            )

        case .injection:
            NumericFieldRow(
                title: "Steam injection pressure (kPa)",
                text: binding(\.steamInjectionPressure),
                message: viewModel.errorMessage(for: WellheadLogField.steamInjectionPressure)
            )
            NumericFieldRow(
                title: "Steam temperature (°C)",
                text: binding(\.steamTemperature),
                message: viewModel.errorMessage(for: WellheadLogField.steamTemperature)
            )
            NumericFieldRow(
                title: "Steam flow rate (m³/day)",
                text: binding(\.steamFlowRate),
                message: viewModel.errorMessage(for: WellheadLogField.steamFlowRate)
            )
        }
    }

    private var headerTitle: String {
        switch viewModel.well.type {
        case .production: return "Production wellhead log"
        case .injection: return "Injection wellhead log"
        }
    }

    // MARK: - Save and status

    private var saveButton: some View {
        Button {
            Task { await viewModel.save() }
        } label: {
            HStack {
                Text("Save log")
                if viewModel.saveStatus == .saving {
                    Spacer()
                    ProgressView()
                }
            }
        }
        .disabled(viewModel.saveStatus == .saving)
    }

    @ViewBuilder
    private var statusBanner: some View {
        if viewModel.didSave {
            Label("Log saved", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .accessibilityIdentifier("saveSuccessIndicator")
        } else if let message = viewModel.saveErrorMessage {
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
                .accessibilityIdentifier("saveErrorBanner")
        }
    }

    // MARK: - Binding helper

    /// A two-way binding to a view-model field string that also clears any
    /// transient save banner as soon as the operator edits, while retaining the
    /// entered value (the view model never clears field strings).
    private func binding(_ keyPath: ReferenceWritableKeyPath<WellheadLogFormViewModel, String>) -> Binding<String> {
        Binding(
            get: { viewModel[keyPath: keyPath] },
            set: { newValue in
                viewModel[keyPath: keyPath] = newValue
                viewModel.markEdited()
            }
        )
    }
}

/// A single labelled numeric text field with an optional validation message
/// rendered beneath it. Reused by both the production and injection forms.
struct NumericFieldRow: View {
    let title: String
    @Binding var text: String
    let message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(title, text: $text)
                #if os(iOS)
                .keyboardType(.numbersAndPunctuation)
                #endif
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(title)
            if let message {
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("fieldError")
            }
        }
        .padding(.vertical, 2)
    }
}
#endif
