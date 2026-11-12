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

// NOTE: This `App` type is defined without `@main`. The process entry point is
// provided by the host app target (ios/App), which links this package as a
// library and presents `SAGDWellTestRootView`. Declaring `@main` here too would
// produce a duplicate `_main` symbol when linked into the app. This type is
// retained for SwiftUI previews and as the canonical scene definition.
struct SAGDWellTestApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Public entry view for host app targets.
///
/// The package builds a library, which cannot launch on its own, so a thin
/// app target provides the process entry point and presents this view. It
/// forwards to the internal `RootView`, keeping the composition root (store,
/// repositories, sync engine) encapsulated in the package.
public struct SAGDWellTestRootView: View {
    public init() {}
    public var body: some View {
        RootView()
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
                logRepository: graph.logRepository,
                graph: graph
            )
        }
        // Fill the local store from the backend at launch (a no-op when
        // offline or unconfigured), then reload the list from the store.
        .task {
            await graph.refreshReferenceData()
            await selectionViewModel.load()
        }
    }
}

extension AppGraph {
    /// Build the graph from the app's configuration, returning `nil` if the
    /// local store could not be opened so the entry point can degrade to a
    /// diagnostic screen.
    static func tryMake() -> AppGraph? {
        let configuration = AppConfiguration.fromEnvironment()
        let graph = try? AppGraph.make(configuration: configuration)
        #if DEBUG
        // Debug builds only, and only with no backend configured: populate
        // sample wells when the store is empty. With a real backend the list
        // comes from the server, so fake wells are never mixed in.
        if !AppGraph.isBackendConfigured(configuration) {
            graph?.seedSampleDataIfEmpty()
        }
        #endif
        return graph
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
