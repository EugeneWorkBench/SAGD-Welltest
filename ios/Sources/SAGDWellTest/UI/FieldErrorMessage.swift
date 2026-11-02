//
//  UI layer — field validation message formatting
//
//  Maps a domain `FieldError.Kind` onto the operator-facing validation message
//  the forms display beneath each input. Message text is a thin presentation
//  concern that lives in the UI layer; the *decision* that a field is invalid
//  (and why) is made by the pure domain validators (`ProductionLogValidator` /
//  `InjectionLogValidator`). Keeping only the wording here means the views and
//  the view model stay free of range literals and grammar rules.
//
//  Implemented by task 20.1.
//  Requirements: 3.2, 3.3, 3.4, 4.2, 4.3 (per-field validation messages).

import Foundation

/// Formats domain validation failures into operator-facing text.
///
/// The ranges embedded in `.outOfRange` come straight from the validator's
/// `FieldError`, so these messages always agree with the rule that rejected the
/// value — there is no second, drifting copy of the bounds in the UI.
enum FieldErrorMessage {

    /// A human-readable validation message for a single field error.
    ///
    /// - `empty` → the field is required (Req 3.5, 4.4).
    /// - `notNumeric` → only numeric input is accepted (Req 3.3, 4.2).
    /// - `outOfRange` → the accepted range, naming the inclusive bounds
    ///   (Req 3.2, 3.4, 4.3).
    /// - `missingSelection` → a required selection was not made (Req 7.2).
    static func text(for kind: FieldError.Kind) -> String {
        switch kind {
        case .empty:
            return "This field is required."
        case .notNumeric:
            return "Enter a numeric value (digits, one optional decimal point, optional leading minus)."
        case .outOfRange(let min, let max):
            return "Enter a value from \(number(min)) to \(number(max))."
        case .missingSelection:
            return "A selection is required."
        }
    }

    /// Formats a bound without a trailing `.0` for whole numbers and with
    /// grouping separators so large bounds (e.g. 999,999.99) read naturally.
    private static func number(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = true
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: value as NSNumber) ?? String(value)
    }
}
