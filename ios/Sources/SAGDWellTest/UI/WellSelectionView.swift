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
    private let graph: AppGraph?

    #if os(iOS)
    /// Screens reachable from the bottom toolbar.
    private enum Tool: Hashable {
        case scan, escalate, subcool
    }
    @State private var path = NavigationPath()
    #endif

    /// - Parameters:
    ///   - viewModel: loads the selectable wells.
    ///   - logRepository: passed to each well's form view model on selection.
    ///   - graph: when provided, enables pull-to-refresh from the backend and
    ///     the QR-scan and escalation tools. The subcool estimator needs no
    ///     graph and is always available.
    public init(
        viewModel: WellSelectionViewModel,
        logRepository: any LogRepository,
        graph: AppGraph? = nil
    ) {
        _viewModel = StateObject(wrappedValue: viewModel)
        self.logRepository = logRepository
        self.graph = graph
    }

    public var body: some View {
        #if os(iOS)
        NavigationStack(path: $path) {
            content
                .navigationTitle("Select a well")
                .navigationDestination(for: Well.self) { well in
                    WellheadLogFormView(
                        viewModel: WellheadLogFormViewModel(
                            well: well,
                            logRepository: logRepository
                        )
                    )
                }
                .navigationDestination(for: Tool.self) { tool in
                    toolDestination(tool)
                }
                .toolbar {
                    ToolbarItemGroup(placement: .bottomBar) {
                        toolButtons
                    }
                }
        }
        .task { await viewModel.load() }
        #else
        NavigationStack {
            content
                .navigationTitle("Select a well")
                .navigationDestination(for: Well.self) { well in
                    WellheadLogFormView(
                        viewModel: WellheadLogFormViewModel(
                            well: well,
                            logRepository: logRepository
                        )
                    )
                }
        }
        .task { await viewModel.load() }
        #endif
    }

    #if os(iOS)
    @ViewBuilder
    private var toolButtons: some View {
        if graph != nil {
            Button { path.append(Tool.scan) } label: {
                Label("Scan", systemImage: "qrcode.viewfinder")
            }
            Spacer()
            Button { path.append(Tool.escalate) } label: {
                Label("Escalate", systemImage: "exclamationmark.triangle")
            }
            Spacer()
        }
        Button { path.append(Tool.subcool) } label: {
            Label("Subcool", systemImage: "thermometer.medium")
        }
    }

    @ViewBuilder
    private func toolDestination(_ tool: Tool) -> some View {
        switch tool {
        case .subcool:
            SubcoolHost()
        case .scan:
            if let graph { QRScanHost(graph: graph) }
        case .escalate:
            if let graph { EscalationHost(graph: graph, wells: viewModel.wells) }
        }
    }
    #endif

    /// Pull the latest reference data from the backend, then reload the list.
    private func refresh() async {
        if let graph {
            await graph.refreshReferenceData()
        }
        await viewModel.load()
    }

    @ViewBuilder
    private var content: some View {
        // Show the spinner only for the very first load. A later refresh keeps
        // the list on screen so navigation state is not torn down.
        if viewModel.isLoading && viewModel.wells.isEmpty {
            ProgressView("Loading wells…")
        } else if viewModel.wells.isEmpty {
            ScrollView {
                VStack(spacing: 8) {
                    Image(systemName: "externaldrive.badge.xmark")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("No wells available")
                        .font(.headline)
                    Text("Wells sync from the cloud. None are stored locally yet. Pull down to refresh.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
                .frame(maxWidth: .infinity, minHeight: 300)
            }
            .refreshable { await refresh() }
        } else {
            List(viewModel.wells) { well in
                NavigationLink(value: well) {
                    WellRow(well: well)
                }
            }
            .refreshable { await refresh() }
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
