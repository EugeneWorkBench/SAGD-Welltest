//
//  Subcool estimator screen (UI)
//
//  Implements the design's "Subcool Estimator" display for iOS: the operator
//  enters a casing pressure and a pump temperature, submits, and the computed
//  subcool value is shown effectively immediately (the calculation is pure and
//  returns well under the 1-second display requirement, Req 5.2). When the
//  value is at or below the flashing threshold the result is rendered in a
//  visually distinguished flashing-zone alert state (Req 5.3). Out-of-range
//  pressure and empty/non-numeric input are surfaced as per-field messages with
//  the entered values retained (Req 5.4, 5.5).
//
//  The screen is thin: all logic lives in `SubcoolEstimatorViewModel`, which
//  wraps the pure `SubcoolEstimating` domain use case and maps its
//  `SubcoolResult` onto renderable display state. The view only binds fields,
//  invokes the view model, and renders `displayState`.
//
//  Implemented by task 20.2.
//
//  Requirements: 5.2, 5.3, 5.4, 5.5.

import Foundation

// MARK: - Display state

/// What the subcool screen renders after a submit. Derived from the pure
/// `SubcoolResult` so the view has nothing to decide beyond layout.
public enum SubcoolDisplayState: Sendable, Equatable {
    /// No submit yet (or the form was cleared): show the empty prompt.
    case idle
    /// A computed value. `isFlashing` drives the distinguished alert state
    /// (Req 5.3).
    case value(subcool: Double, isFlashing: Bool)
    /// The casing pressure is outside the steam-table domain (Req 5.4). Carries
    /// the offending field identifier so the UI can anchor the message.
    case outOfRange(field: String)
    /// An input was empty or non-numeric (Req 5.5). Carries the offending field
    /// identifier.
    case invalidInput(field: String)
}

// MARK: - View model

/// Drives the subcool estimator screen. Holds the as-entered field strings
/// (retained across a submit so Req 5.4/5.5 keep the operator's values) and the
/// current `displayState`. Calls the injected pure estimator and maps its
/// result; performs no I/O of its own.
///
/// `@MainActor` because it is bound directly to SwiftUI; the estimate itself is
/// synchronous and pure, so the value is available within the same run loop
/// turn, comfortably inside the 1-second budget (Req 5.2).
@MainActor
public final class SubcoolEstimatorViewModel: ObservableObject {

    /// Casing pressure as entered (kPa). Retained across submits.
    @Published public var casingPressure: String = ""
    /// Pump temperature as entered (°C). Retained across submits.
    @Published public var pumpTemperature: String = ""
    /// The current render state. Starts `.idle`.
    @Published public private(set) var displayState: SubcoolDisplayState = .idle

    private let estimator: SubcoolEstimating

    /// - Parameter estimator: The pure subcool estimator (defaults to the
    ///   standard `SubcoolEstimator` over the bundled steam table).
    public init(estimator: SubcoolEstimating = SubcoolEstimator()) {
        self.estimator = estimator
    }

    /// Compute the subcool from the entered fields and update `displayState`.
    /// The field strings are left untouched so an out-of-range or invalid
    /// submission retains the operator's entries (Req 5.4, 5.5).
    public func estimate() {
        switch estimator.estimate(casingPressure: casingPressure, pumpTemperature: pumpTemperature) {
        case let .success(subcool, isFlashing):
            displayState = .value(subcool: subcool, isFlashing: isFlashing)
        case let .outOfRange(field):
            displayState = .outOfRange(field: field)
        case let .invalidInput(field):
            displayState = .invalidInput(field: field)
        }
    }

    /// Clear the result back to the idle prompt (e.g. when an input changes).
    /// Field values are left as the operator typed them.
    public func reset() {
        displayState = .idle
    }

    // MARK: - Presentation helpers (pure, view-agnostic)

    /// A human label for an offending field identifier.
    public static func fieldLabel(_ field: String) -> String {
        switch field {
        case SubcoolEstimator.Field.casingPressure: return "Casing pressure"
        case SubcoolEstimator.Field.pumpTemperature: return "Pump temperature"
        default: return field
        }
    }

    /// The subcool value formatted for display (one decimal place, °C).
    public static func formattedSubcool(_ value: Double) -> String {
        String(format: "%.1f °C", value)
    }
}

#if canImport(SwiftUI)
import SwiftUI

// MARK: - View

/// Thin SwiftUI screen for the subcool estimator. Binds the two input fields,
/// invokes the view model on submit, and renders `displayState` — including the
/// distinguished flashing-zone alert (Req 5.3) and the out-of-range / invalid
/// messages (Req 5.4, 5.5).
public struct SubcoolEstimatorScreen: View {

    @ObservedObject private var viewModel: SubcoolEstimatorViewModel

    public init(viewModel: SubcoolEstimatorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        Form {
            Section("Inputs") {
                LabeledContent("Casing pressure (kPa)") {
                    TextField("e.g. 2000", text: $viewModel.casingPressure)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: viewModel.casingPressure) { _ in viewModel.reset() }
                        #if os(iOS)
                        .keyboardType(.numbersAndPunctuation)
                        #endif
                }
                LabeledContent("Pump temperature (°C)") {
                    TextField("e.g. 180", text: $viewModel.pumpTemperature)
                        .multilineTextAlignment(.trailing)
                        .onChange(of: viewModel.pumpTemperature) { _ in viewModel.reset() }
                        #if os(iOS)
                        .keyboardType(.numbersAndPunctuation)
                        #endif
                }
                Button("Estimate subcool") { viewModel.estimate() }
            }

            Section("Result") {
                resultView
            }
        }
        .navigationTitle("Subcool Estimator")
    }

    @ViewBuilder
    private var resultView: some View {
        switch viewModel.displayState {
        case .idle:
            Text("Enter a casing pressure and pump temperature, then tap Estimate.")
                .foregroundStyle(.secondary)

        case let .value(subcool, isFlashing):
            if isFlashing {
                flashingAlert(subcool: subcool)
            } else {
                normalValue(subcool: subcool)
            }

        case let .outOfRange(field):
            messageRow(
                systemImage: "exclamationmark.triangle",
                tint: .orange,
                text: "\(SubcoolEstimatorViewModel.fieldLabel(field)) is out of range for the steam table."
            )

        case let .invalidInput(field):
            messageRow(
                systemImage: "exclamationmark.circle",
                tint: .orange,
                text: "\(SubcoolEstimatorViewModel.fieldLabel(field)) must be a number."
            )
        }
    }

    /// Normal (non-flashing) subcool value.
    private func normalValue(subcool: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Subcool")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(SubcoolEstimatorViewModel.formattedSubcool(subcool))
                .font(.largeTitle.weight(.semibold))
                .monospacedDigit()
        }
    }

    /// Distinguished flashing-zone alert state (Req 5.3): a filled, high-contrast
    /// banner with a warning icon so the value reads as a hazard at a glance.
    private func flashingAlert(subcool: Double) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Flashing zone", systemImage: "flame.fill")
                .font(.headline)
            Text(SubcoolEstimatorViewModel.formattedSubcool(subcool))
                .font(.largeTitle.weight(.bold))
                .monospacedDigit()
            Text("Subcool is at or below the flashing threshold.")
                .font(.footnote)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color.red.opacity(0.15))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.red, lineWidth: 2)
        )
        .foregroundStyle(Color.red)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Flashing zone alert, subcool \(SubcoolEstimatorViewModel.formattedSubcool(subcool))")
    }

    private func messageRow(systemImage: String, tint: Color, text: String) -> some View {
        Label(text, systemImage: systemImage)
            .foregroundStyle(tint)
    }
}
#endif
