//
//  UI layer — per-record sync-state badge
//
//  Shows a record's synchronization state (pending / synchronized / failed) as
//  a small badge next to the record, and — for `failed` records only — a manual
//  retry control. The badge updates reactively as the record's state changes:
//  the view model subscribes to `SyncRepository.observeSyncState(recordId:)`
//  (an `AsyncStream<SyncState>` driven by GRDB observation), which emits the
//  current state immediately and every change thereafter. Because the stream
//  yields within the repository's observation latency, the displayed state
//  reflects a transition well within the 5 s budget (Req 2.7).
//
//  The view is thin: it renders the view model's published state and forwards
//  the retry tap. All subscription and retry logic lives in the view model
//  (Req 2.6 pending display, Req 2.7 reactive update, Req 2.5/7.7 manual retry
//  entry point). Introduced by task 20.3.

import Foundation

#if canImport(SwiftUI)
import SwiftUI
import Combine

/// Observes a single record's sync state and exposes it for display, plus a
/// manual-retry action for records that have reached `failed`.
///
/// Lifecycle: call `start()` when the badge appears (e.g. `.task`/`.onAppear`)
/// to begin consuming the state stream, and `stop()` when it disappears to
/// cancel the subscription and release the underlying observation. The initial
/// value emitted by `observeSyncState` seeds `state`, so the badge shows the
/// authoritative persisted state as soon as the stream starts.
@MainActor
public final class SyncStateBadgeViewModel: ObservableObject {
    /// The record whose sync state this badge reflects.
    public let recordId: UUID
    /// The latest observed sync state. Drives the badge's label and styling and
    /// whether the manual-retry control is shown.
    @Published public private(set) var state: SyncState

    private let repository: SyncRepository
    private var observationTask: Task<Void, Never>?

    /// - Parameters:
    ///   - recordId: The record to observe.
    ///   - repository: The sync repository providing the observation stream and
    ///     the manual-retry mutation.
    ///   - initialState: The state to show before the first stream value
    ///     arrives. Defaults to `.pending`, the state of a freshly created,
    ///     not-yet-acknowledged record (Req 2.6).
    public init(
        recordId: UUID,
        repository: SyncRepository,
        initialState: SyncState = .pending
    ) {
        self.recordId = recordId
        self.repository = repository
        self.state = initialState
    }

    /// True only when the record has permanently failed; the view shows the
    /// manual-retry control exactly in this case.
    public var showsRetryControl: Bool { state == .failed }

    /// Begin observing the record's sync state. Idempotent: calling it while
    /// already observing is a no-op. Each emitted value updates `state` on the
    /// main actor so SwiftUI re-renders the badge.
    public func start() {
        guard observationTask == nil else { return }
        let stream = repository.observeSyncState(recordId: recordId)
        observationTask = Task { [weak self] in
            for await newState in stream {
                guard let self else { return }
                self.state = newState
            }
        }
    }

    /// Stop observing and release the subscription. Safe to call repeatedly.
    public func stop() {
        observationTask?.cancel()
        observationTask = nil
    }

    /// Trigger a manual retry for a `failed` record, resetting it to `pending`
    /// so automatic sync picks it up again (Req 2.5, 7.7). The repository
    /// ignores records that are not in `failed`, so this is safe to call
    /// unconditionally; the observation stream reports the resulting transition.
    public func retry() async {
        await repository.manualRetry(recordId: recordId)
    }

    deinit {
        observationTask?.cancel()
    }
}

/// A thin badge that renders a record's sync state and, for failed records, a
/// manual-retry button. All state and actions come from the view model.
public struct SyncStateBadgeView: View {
    @ObservedObject private var viewModel: SyncStateBadgeViewModel

    public init(viewModel: SyncStateBadgeViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        HStack(spacing: 6) {
            Label {
                Text(viewModel.state.displayLabel)
            } icon: {
                Image(systemName: viewModel.state.symbolName)
            }
            .font(.caption)
            .foregroundStyle(viewModel.state.tint)
            .accessibilityLabel(Text(viewModel.state.accessibilityLabel))

            if viewModel.showsRetryControl {
                Button {
                    Task { await viewModel.retry() }
                } label: {
                    Label("Retry", systemImage: "arrow.clockwise")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Retry synchronization"))
            }
        }
        .task { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }
}

// MARK: - Presentation mapping

extension SyncState {
    /// Short human-readable label shown in the badge.
    var displayLabel: String {
        switch self {
        case .pending: return "Pending"
        case .synchronized: return "Synced"
        case .failed: return "Failed"
        }
    }

    /// SF Symbol representing the state.
    var symbolName: String {
        switch self {
        case .pending: return "clock"
        case .synchronized: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        }
    }

    /// Color used to distinguish the state at a glance.
    var tint: Color {
        switch self {
        case .pending: return .orange
        case .synchronized: return .green
        case .failed: return .red
        }
    }

    /// Fuller description for assistive technology.
    var accessibilityLabel: String {
        switch self {
        case .pending: return "Sync pending"
        case .synchronized: return "Synchronized"
        case .failed: return "Sync failed"
        }
    }
}
#endif
