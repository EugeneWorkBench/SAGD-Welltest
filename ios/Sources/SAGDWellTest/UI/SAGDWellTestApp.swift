//
//  UI layer — SwiftUI application entry point
//
//  Renders screens (well selection, production/injection log forms, subcool
//  estimator, QR scan results, escalation capture) and reflects per-record sync
//  state and the global offline indicator. Holds no business rules; delegates
//  validation and computation to the Domain layer.
//
//  Task 20.1 implements well selection and the wellhead log forms and makes the
//  well-selection screen the app root. Tasks 20.2/20.3 added the remaining
//  screens (subcool, QR result, escalation) and the sync-state/offline
//  indicators. Task 22.2 replaces the partial wiring here with the full
//  `AppGraph` composition root: the entry point now builds the whole dependency
//  graph (store, repositories, REST client, sync engine, integrations), starts
//  the sync engine, and hosts the well-selection screen under the global
//  offline banner.

#if canImport(SwiftUI)
import SwiftUI

@main
struct SAGDWellTestApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Composition root host: builds the `AppGraph`, starts the sync engine, and
/// presents the app UI wired to the graph. If the store cannot be opened the
/// graph fails to build and a diagnostic placeholder is shown rather than
/// crashing on launch.
struct RootView: View {

    /// The assembled application graph, built once on first render. `nil` only
    /// if the local store / blob store could not be opened.
    @State private var graph: AppGraph? = AppGraph.tryMake()

    var body: some View {
        Group {
            if let graph {
                AppRootView(graph: graph)
                    // Start the sync engine once the graph is live so it begins
                    // reacting to connectivity and registers background work.
                    .task { await graph.start() }
            } else {
                StoreUnavailableView()
            }
        }
    }
}

/// The running app UI: the well-selection screen under the global offline
/// banner, both wired to the shared graph.
struct AppRootView: View {
    let graph: AppGraph

    @StateObject private var offlineIndicatorViewModel: OfflineIndicatorViewModel
    @StateObject private var selectionViewModel: WellSelectionViewModel

    @MainActor
    init(graph: AppGraph) {
        self.graph = graph
        _offlineIndicatorViewModel = StateObject(
            wrappedValue: graph.makeOfflineIndicatorViewModel()
        )
        _selectionViewModel = StateObject(
            wrappedValue: graph.makeWellSelectionViewModel()
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            OfflineIndicatorView(viewModel: offlineIndicatorViewModel)
            WellSelectionView(
                viewModel: selectionViewModel,
                logRepository: graph.logRepository
            )
        }
    }
}

extension AppGraph {
    /// Build the graph from the app's configuration, returning `nil` if the
    /// local store could not be opened so the entry point can degrade to a
    /// diagnostic screen.
    static func tryMake() -> AppGraph? {
        try? AppGraph.make(configuration: .fromEnvironment())
    }
}

/// Shown when the local store cannot be opened on launch.
struct StoreUnavailableView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(.red)
            Text("SAGD WellTest")
                .font(.title)
            Text("The local database could not be opened.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
#endif

/// Namespace marker for the UI layer.
enum UILayer {}
