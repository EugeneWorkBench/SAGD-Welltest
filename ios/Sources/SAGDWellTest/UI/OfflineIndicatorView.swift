//
//  UI layer — global offline indicator
//
//  A single app-wide banner reflecting whether the device currently has no
//  network path. The view model binds to `OfflineIndicator.observe()` (an
//  `AsyncStream<Bool>` the sync layer publishes to), which emits the current
//  value immediately and each change thereafter. The sync engine flips the
//  indicator within 5 s of a connectivity-loss transition (Req 2.8); this view
//  simply mirrors whatever the stream reports, so the banner appears within the
//  same budget.
//
//  The view is thin: it renders a banner only while offline and holds no
//  connectivity logic — observation lives in the view model. Introduced by
//  task 20.3.

import Foundation

#if canImport(SwiftUI)
import SwiftUI

/// Observes the global offline indicator and exposes a single `isOffline` flag
/// the UI binds to. Backed by the sync layer's `OfflineIndicator` actor.
///
/// Lifecycle: call `start()` once near the app root (e.g. on the root view's
/// `.task`) to begin consuming the stream, and `stop()` to cancel it. The first
/// value emitted seeds `isOffline`, so the banner is correct as soon as
/// observation starts.
@MainActor
public final class OfflineIndicatorViewModel: ObservableObject {
    /// Whether the device currently has no network connectivity. Drives whether
    /// the offline banner is shown.
    @Published public private(set) var isOffline: Bool

    private let indicator: OfflineIndicator
    private var observationTask: Task<Void, Never>?

    /// - Parameters:
    ///   - indicator: The sync layer's offline indicator to observe.
    ///   - initiallyOffline: The value to show before the first stream value
    ///     arrives.
    public init(indicator: OfflineIndicator, initiallyOffline: Bool = false) {
        self.indicator = indicator
        self.isOffline = initiallyOffline
    }

    /// Begin observing the offline indicator. Idempotent. Each emitted value
    /// updates `isOffline` on the main actor so SwiftUI re-renders the banner.
    public func start() {
        guard observationTask == nil else { return }
        observationTask = Task { [weak self] in
            guard let indicator = self?.indicator else { return }
            let stream = await indicator.observe()
            for await offline in stream {
                guard let self else { return }
                self.isOffline = offline
            }
        }
    }

    /// Stop observing and release the subscription. Safe to call repeatedly.
    public func stop() {
        observationTask?.cancel()
        observationTask = nil
    }

    deinit {
        observationTask?.cancel()
    }
}

/// A thin global banner shown only while the device is offline. Mirrors the
/// view model's `isOffline` flag.
public struct OfflineIndicatorView: View {
    @ObservedObject private var viewModel: OfflineIndicatorViewModel

    public init(viewModel: OfflineIndicatorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        Group {
            if viewModel.isOffline {
                HStack(spacing: 6) {
                    Image(systemName: "wifi.slash")
                    Text("Offline — changes will sync when reconnected")
                        .font(.footnote)
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity)
                .background(Color.orange.opacity(0.2))
                .foregroundStyle(.orange)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Text("Device is offline"))
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.isOffline)
        .task { viewModel.start() }
    }
}
#endif
