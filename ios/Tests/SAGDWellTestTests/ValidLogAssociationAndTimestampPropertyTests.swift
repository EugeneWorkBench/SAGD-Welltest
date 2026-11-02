//
//  ValidLogAssociationAndTimestampPropertyTests.swift
//
//  Property 13: Valid log association and timestamp.
//
//  For any valid wellhead log (production or injection) that is validated, the
//  normalized `WellheadLog` is associated with the selected well identifier,
//  carries the correct `logType`, and has a capture timestamp expressed in UTC
//  at one-second precision.
//
//  Expressed concretely against the validators: a known clock is injected into
//  `ProductionLogValidator` / `InjectionLogValidator` via `now:`. For any
//  arbitrary well identifier and any all-valid, in-range field set, the
//  validator returns `.valid(log)` where:
//
//    * `log.wellId == input.wellId` (associated with the selected well);
//    * `log.logType` matches the validator kind (`.production` / `.injection`);
//    * `log.capturedAt` equals the injected instant truncated to whole seconds,
//      i.e. its `timeIntervalSince1970` is an integer (no sub-second component)
//      and equals `floor(injectedInstant.timeIntervalSince1970)`.
//
//  Validates: Requirements 3.6, 4.5.
//
//  Implements task 9.5. Runs a minimum of 100 iterations via `pbtArguments()`,
//  tagged `Feature: sagd-welltest-mobile, Property 13: ...`.

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class ValidLogAssociationAndTimestampPropertyTests: XCTestCase {

    // MARK: - Generators

    /// Formats a non-negative `Double` as a well-formed numeric string under the
    /// accepted grammar (digits with at most one decimal point, no exponent, no
    /// grouping separators), preserving up to two fractional digits.
    private static func numericString(_ value: Double) -> String {
        String(format: "%.2f", value)
    }

    /// Generates a well-formed, in-range numeric string for `[min, max]`,
    /// spanning the interior and both inclusive boundaries. After two-decimal
    /// rounding the parsed value still lies in `[min, max]` because every range
    /// bound used here is a multiple of 1/100.
    private static func inRange(min: Double, max: Double) -> Gen<String> {
        let minH = Int((min * 100).rounded())
        let maxH = Int((max * 100).rounded())
        let interior: Gen<String> = Gen<Int>.choose((minH, maxH)).map {
            numericString(Double($0) / 100.0)
        }
        let boundaries: Gen<String> = Gen<String>.fromElements(of: [
            numericString(min),
            numericString(max),
        ])
        return Gen<String>.one(of: [interior, boundaries])
    }

    /// Generates an arbitrary, non-empty well identifier. The well id is opaque
    /// to the validator (it is copied through verbatim), so a mix of QR-style
    /// identifiers and free-form strings exercises the association faithfully.
    private static let wellId: Gen<String> = {
        let token = Gen<Character>.fromElements(of: Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"))
        let body = token.proliferateNonEmpty.map { String($0) }
        let fixed = Gen<String>.fromElements(of: [
            "WELL-1", "WELL-42", "PAD-7/INJ-3", "a well with spaces", "井-123",
        ])
        return Gen<String>.one(of: [body, fixed])
    }()

    /// Generates an injected instant as an arbitrary `Date`, including instants
    /// with a sub-second component so truncation to whole seconds is actually
    /// exercised. The whole-second offset keeps the instant in a realistic
    /// range; the fractional part (in milliseconds) forces a non-zero
    /// sub-second component on many cases.
    private static let injectedInstant: Gen<Date> = {
        // Whole seconds since the epoch within a wide but realistic window
        // (roughly 1970..2065 at ~1e9..3e9 seconds).
        let wholeSeconds = Gen<Int>.choose((0, 3_000_000_000))
        // Sub-second component in [0, 999] milliseconds.
        let millis = Gen<Int>.choose((0, 999))
        return Gen<(Int, Int)>.zip(wholeSeconds, millis).map { secs, ms in
            Date(timeIntervalSince1970: Double(secs) + Double(ms) / 1000.0)
        }
    }()

    // MARK: - Helpers

    /// Whether `date`'s epoch offset has no sub-second component (whole second).
    private static func isWholeSecond(_ date: Date) -> Bool {
        let t = date.timeIntervalSince1970
        return t == t.rounded(.down)
    }

    /// The expected UTC one-second-precision instant for a given injected
    /// instant: the epoch offset floored to a whole second.
    private static func expectedCapturedAt(_ injected: Date) -> Date {
        Date(timeIntervalSince1970: injected.timeIntervalSince1970.rounded(.down))
    }

    // MARK: - Property 13

    func testValidLogAssociationAndTimestamp() {
        let production = Self.inRange(
            min: WellheadLogFieldRange.productionMin,
            max: WellheadLogFieldRange.productionMax
        )
        let waterCut = Self.inRange(
            min: WellheadLogFieldRange.waterCutMin,
            max: WellheadLogFieldRange.waterCutMax
        )
        let steamPressure = Self.inRange(
            min: WellheadLogFieldRange.steamPressureMin,
            max: WellheadLogFieldRange.steamPressureMax
        )
        let steamTemp = Self.inRange(
            min: WellheadLogFieldRange.steamTemperatureMin,
            max: WellheadLogFieldRange.steamTemperatureMax
        )
        let steamFlow = Self.inRange(
            min: WellheadLogFieldRange.steamFlowMin,
            max: WellheadLogFieldRange.steamFlowMax
        )

        // Each case draws an arbitrary well id and an arbitrary injected instant
        // (with a possible sub-second component). Inside, both the production and
        // injection validators are exercised with all-valid field sets so the
        // association + timestamp property is asserted for both log types.
        property(
            propertyName(13, "valid log association and timestamp"),
            arguments: pbtArguments()
        ) <- forAll(Self.wellId, Self.injectedInstant) { (wellId, injected) in
            let expected = Self.expectedCapturedAt(injected)

            // Inject the known clock so `capturedAt` is deterministic.
            let productionValidator = ProductionLogValidator(now: { injected })
            let injectionValidator = InjectionLogValidator(now: { injected })

            // --- Production branch. ---
            let productionProperty = forAll(production, production, production, waterCut) {
                (emulsion, casing, esp, water) in
                let input = ProductionLogInput(
                    wellId: wellId,
                    emulsionFlowRate: emulsion,
                    casingGasPressure: casing,
                    espMotorTemperature: esp,
                    waterCutPercent: water
                )
                switch productionValidator.validate(input) {
                case .invalid(let errs):
                    return false
                        <?> "all-valid production input unexpectedly rejected: \(errs)"
                case .valid(let log):
                    let associated = (log.wellId == wellId)
                        <?> "production log wellId \(log.wellId) != input \(wellId)"
                    let typed = (log.logType == .production)
                        <?> "production log logType is \(log.logType), expected .production"
                    let truncated = Self.isWholeSecond(log.capturedAt)
                        <?> "production capturedAt \(log.capturedAt) has a sub-second component"
                    let stamped = (log.capturedAt == expected)
                        <?> "production capturedAt \(log.capturedAt) != truncated instant \(expected)"
                    return associated ^&&^ typed ^&&^ truncated ^&&^ stamped
                }
            }

            // --- Injection branch. ---
            let injectionProperty = forAll(steamPressure, steamTemp, steamFlow) {
                (pressure, temp, flow) in
                let input = InjectionLogInput(
                    wellId: wellId,
                    steamInjectionPressure: pressure,
                    steamTemperature: temp,
                    steamFlowRate: flow
                )
                switch injectionValidator.validate(input) {
                case .invalid(let errs):
                    return false
                        <?> "all-valid injection input unexpectedly rejected: \(errs)"
                case .valid(let log):
                    let associated = (log.wellId == wellId)
                        <?> "injection log wellId \(log.wellId) != input \(wellId)"
                    let typed = (log.logType == .injection)
                        <?> "injection log logType is \(log.logType), expected .injection"
                    let truncated = Self.isWholeSecond(log.capturedAt)
                        <?> "injection capturedAt \(log.capturedAt) has a sub-second component"
                    let stamped = (log.capturedAt == expected)
                        <?> "injection capturedAt \(log.capturedAt) != truncated instant \(expected)"
                    return associated ^&&^ typed ^&&^ truncated ^&&^ stamped
                }
            }

            return productionProperty ^&&^ injectionProperty
        }
    }
}
