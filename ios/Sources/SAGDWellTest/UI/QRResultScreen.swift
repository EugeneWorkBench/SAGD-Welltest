//
//  QR scan result screen (UI)
//
//  Implements the display half of the design's "QR Scanning Flow" for iOS.
//  After a scan the screen renders one of the flow's branches:
//
//    * found      — the well's historical `WellTest` records ordered
//                   most-recent-first and all open `MaintenanceTask` records
//                   (Req 6.2); when the device is offline the view is annotated
//                   as sourced from local storage (Req 6.5).
//    * notFound   — "well not found" plus a rescan control (Req 6.3).
//    * unavailableOffline — "unavailable offline" (Req 6.6).
//    * unreadable — the scan could not be decoded; a rescan control (Req 6.4,
//                   surfaced here because the coordinator reports it).
//
//  The screen is thin: the `QRResultViewModel` runs the injected
//  `QRScannerCoordinator`, maps its `QRScanOutcome` onto renderable state, and
//  tracks whether the result was assembled offline (for the local-storage
//  annotation). The well-lookup ordering (tests most-recent-first, open tasks
//  only) is a property of the domain/repository layers; the view renders the
//  lists in the order it receives them.
//
//  Implemented by task 20.2.
//
//  Requirements: 6.2, 6.3, 6.5, 6.6.

import Foundation

// MARK: - Display state

/// What the QR result screen renders. Derived from `QRScanOutcome` so the view
/// only lays out the branch it is handed.
public enum QRResultDisplayState: Sendable, Equatable {
    /// No scan has completed yet (initial, or while a rescan is running).
    case scanning
    /// A matching well with its tests (most-recent-first) and open tasks.
    /// `fromLocalStorage` drives the local-storage annotation shown when the
    /// result was assembled offline (Req 6.5).
    case found(well: Well, wellTests: [WellTest], openTasks: [MaintenanceTask], fromLocalStorage: Bool)
    /// The decoded well was not found locally while online (Req 6.3). Carries
    /// the decoded identifier for the message.
    case notFound(decodedId: String)
    /// The decoded well was not found locally while offline (Req 6.6).
    case unavailableOffline(decodedId: String)
    /// Nothing decoded within the unreadable budget (Req 6.4).
    case unreadable
}

// MARK: - View model

/// Drives the QR result screen: runs a scan through the injected
/// `QRScannerCoordinator`, maps the outcome onto `displayState`, and exposes a
/// `rescan` action for the not-found / unreadable branches.
///
/// The view model consults an injected connectivity signal only to tag a
/// `found` result as sourced from local storage when offline (Req 6.5); the
/// lookup itself is connectivity-independent and already done inside the
/// coordinator. `@MainActor` because it is bound to SwiftUI; scanning is async.
@MainActor
public final class QRResultViewModel: ObservableObject {

    /// The current render state. Starts `.scanning`.
    @Published public private(set) var displayState: QRResultDisplayState = .scanning

    private let coordinator: QRScannerCoordinator
    private let connectivity: ConnectivityProviding

    /// - Parameters:
    ///   - coordinator: Runs a single scan (decode + domain lookup) to a
    ///     terminal `QRScanOutcome`.
    ///   - connectivity: Online/offline signal, consulted only to annotate a
    ///     `found` result as local-storage-sourced when offline (Req 6.5).
    public init(coordinator: QRScannerCoordinator, connectivity: ConnectivityProviding) {
        self.coordinator = coordinator
        self.connectivity = connectivity
    }

    /// Run a scan and publish the resulting display state.
    public func scan() async {
        displayState = .scanning
        apply(outcome: await coordinator.scan())
    }

    /// Restart the scan — the rescan control for the not-found / unreadable
    /// branches (Req 6.3, 6.4).
    public func rescan() async {
        displayState = .scanning
        apply(outcome: await coordinator.rescan())
    }

    /// Map a terminal scan outcome onto the render state.
    private func apply(outcome: QRScanOutcome) {
        switch outcome {
        case let .found(lookup, _, _):
            // The coordinator's lookup carries the assembled well data; render
            // it, annotating as local-storage-sourced when offline (Req 6.5).
            if case let .found(well, tests, tasks) = lookup {
                displayState = .found(
                    well: well,
                    wellTests: tests,
                    openTasks: tasks,
                    fromLocalStorage: !connectivity.isOnline
                )
            } else {
                // Defensive: a `.found` outcome always carries a `.found`
                // lookup, but fall back to unreadable rather than crash.
                displayState = .unreadable
            }
        case let .notFound(decodedId):
            displayState = .notFound(decodedId: decodedId)
        case let .unavailableOffline(decodedId):
            displayState = .unavailableOffline(decodedId: decodedId)
        case .unreadable:
            displayState = .unreadable
        }
    }

    // MARK: - Presentation helpers (pure, view-agnostic)

    /// Oil/water/gas ratios formatted for a test row.
    public static func ratioSummary(_ test: WellTest) -> String {
        String(
            format: "Oil %.2f · Water %.2f · Gas %.2f",
            test.oilRatio, test.waterRatio, test.gasRatio
        )
    }

    /// A UTC timestamp formatted for display (one-second precision).
    public static func formattedTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss 'UTC'"
        return formatter.string(from: date)
    }
}

#if canImport(SwiftUI)
import SwiftUI

// MARK: - View

/// Thin SwiftUI screen rendering the QR scan result branch. Runs a scan on
/// appear and renders `displayState`.
public struct QRResultScreen: View {

    @ObservedObject private var viewModel: QRResultViewModel

    public init(viewModel: QRResultViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        content
            .navigationTitle("Well Lookup")
            .task { await viewModel.scan() }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.displayState {
        case .scanning:
            scanningView

        case let .found(well, tests, tasks, fromLocalStorage):
            foundView(well: well, tests: tests, tasks: tasks, fromLocalStorage: fromLocalStorage)

        case let .notFound(decodedId):
            messageView(
                systemImage: "questionmark.circle",
                title: "Well not found",
                message: "No well matching \"\(decodedId)\" is in local storage.",
                showRescan: true
            )

        case let .unavailableOffline(decodedId):
            messageView(
                systemImage: "wifi.slash",
                title: "Unavailable offline",
                message: "\"\(decodedId)\" is not in local storage and the device is offline.",
                showRescan: true
            )

        case .unreadable:
            messageView(
                systemImage: "viewfinder",
                title: "Scan unreadable",
                message: "The code could not be read. Try again.",
                showRescan: true
            )
        }
    }

    private var scanningView: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("Scanning…")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func foundView(
        well: Well,
        tests: [WellTest],
        tasks: [MaintenanceTask],
        fromLocalStorage: Bool
    ) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(well.name).font(.headline)
                    Text("\(well.id) · \(well.type.rawValue.capitalized)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if fromLocalStorage {
                    // Local-storage annotation shown on an offline hit (Req 6.5).
                    Label("Showing data from local storage", systemImage: "internaldrive")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Section("Well tests (most recent first)") {
                if tests.isEmpty {
                    Text("No well tests recorded.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(tests) { test in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(QRResultViewModel.ratioSummary(test))
                            Text(QRResultViewModel.formattedTimestamp(test.capturedAt))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section("Open maintenance tasks") {
                if tasks.isEmpty {
                    Text("No open tasks.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(tasks) { task in
                        Text(task.description)
                    }
                }
            }
        }
    }

    private func messageView(
        systemImage: String,
        title: String,
        message: String,
        showRescan: Bool
    ) -> some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if showRescan {
                Button("Rescan") {
                    Task { await viewModel.rescan() }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif
