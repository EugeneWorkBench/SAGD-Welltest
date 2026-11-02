//
//  UI layer — well selection view
//
//  Lists the locally cached wells and lets the operator pick one. Choosing a
//  well navigates to its wellhead log form; because the form is selected by
//  `Well.type`, picking a production well leads to the production form and an
//  injection well to the injection form (Req 3.1, 4.1). A `WellheadLogFormViewModel`
//  is constructed for the chosen well, wired to the shared `LogRepository`.
//
//  Implemented by task 20.1.
//  Requirements: 3.1, 4.1 (form chosen by well type).

#if canImport(SwiftUI)
import SwiftUI

/// The well-selection screen; the entry point into wellhead-log capture.
public struct WellSelectionView: View {

    @StateObject private var viewModel: WellSelectionViewModel
    private let logRepository: any LogRepository

    /// - Parameters:
    ///   - viewModel: loads the selectable wells.
    ///   - logRepository: passed to each well's form view model on selection.
    public init(
        viewModel: WellSelectionViewModel,
        logRepository: any LogRepository
    ) {
        _viewModel = StateObject(wrappedValue: viewModel)
        self.logRepository = logRepository
    }

    public var body: some View {
        NavigationStack {
            content
                .navigationTitle("Select a well")
        }
        .task { await viewModel.load() }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading {
            ProgressView("Loading wells…")
        } else if viewModel.wells.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "externaldrive.badge.xmark")
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                Text("No wells available")
                    .font(.headline)
                Text("Wells sync from the cloud. None are stored locally yet.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding()
        } else {
            List(viewModel.wells) { well in
                NavigationLink(value: well) {
                    WellRow(well: well)
                }
            }
            .navigationDestination(for: Well.self) { well in
                WellheadLogFormView(
                    viewModel: WellheadLogFormViewModel(
                        well: well,
                        logRepository: logRepository
                    )
                )
            }
        }
    }
}

/// A single row in the well list showing the well name and its type.
struct WellRow: View {
    let well: Well

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(well.name)
                    .font(.body)
                if let padId = well.padId {
                    Text("Pad \(padId)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(typeLabel)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(typeColor.opacity(0.15), in: Capsule())
                .foregroundStyle(typeColor)
        }
    }

    private var typeLabel: String {
        switch well.type {
        case .production: return "Production"
        case .injection: return "Injection"
        }
    }

    private var typeColor: Color {
        switch well.type {
        case .production: return .blue
        case .injection: return .orange
        }
    }
}
#endif
