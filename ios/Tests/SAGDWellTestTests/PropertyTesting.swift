//
//  PropertyTesting.swift
//  Property-based testing helper for the iOS test target.
//
//  Every design correctness property is covered by exactly one property-based
//  test running a minimum of 100 iterations, tagged
//  `Feature: sagd-welltest-mobile, Property {n}: {text}`.
//
//  This helper centralizes that 100-iteration floor so individual property
//  tests cannot accidentally run fewer iterations. It wraps SwiftCheck's
//  `CheckerArguments`, clamping `maxAllowableSuccessfulTests` up to at least
//  `minimumIterations`.

import SwiftCheck

/// Minimum number of successful iterations every property test must run.
let minimumPropertyIterations = 100

/// Builds SwiftCheck arguments that guarantee at least `minimumPropertyIterations`
/// successful cases. Pass the result as the `with:` argument to `property`.
///
/// Example:
/// ```swift
/// property("Feature: sagd-welltest-mobile, Property 1: persistence round-trip",
///          arguments: pbtArguments())
///     <- forAll { (record: WellTest) in
///         // ... round-trip assertion ...
///     }
/// ```
func pbtArguments(
    iterations: Int = minimumPropertyIterations,
    name: String? = nil
) -> CheckerArguments {
    let successful = max(iterations, minimumPropertyIterations)
    return CheckerArguments(
        replay: nil,
        maxAllowableSuccessfulTests: successful,
        // Allow enough discards headroom so constrained generators still reach
        // the required number of successful cases.
        maxAllowableDiscardedTests: successful * 10,
        maxTestCaseSize: 100
    )
}

/// Builds a tagged property-test name following the required convention:
/// `Feature: sagd-welltest-mobile, Property {n}: {text}`.
func propertyName(_ number: Int, _ text: String) -> String {
    "Feature: sagd-welltest-mobile, Property \(number): \(text)"
}
