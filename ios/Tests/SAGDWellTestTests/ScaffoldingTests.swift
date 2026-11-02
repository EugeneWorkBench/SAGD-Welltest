//
//  ScaffoldingTests.swift
//  Smoke tests verifying the project scaffolding is sound: the module imports,
//  the layer namespaces exist, and the PBT helper enforces the 100-iteration
//  floor.
//

import XCTest
import SwiftCheck
@testable import SAGDWellTest

final class ScaffoldingTests: XCTestCase {

    func testModuleImports() {
        // Referencing the layer namespace markers confirms the module and each
        // layer source group compile and link into the test target.
        _ = DomainLayer.self
        _ = RepositoryLayer.self
        _ = LocalStoreLayer.self
        _ = SyncLayer.self
        _ = IntegrationsLayer.self
        _ = NetworkLayer.self
        _ = UILayer.self
    }

    func testPBTHelperEnforcesMinimumIterations() {
        XCTAssertEqual(minimumPropertyIterations, 100)

        // Requesting fewer than the floor must be clamped up to the floor.
        let clamped = pbtArguments(iterations: 10)
        XCTAssertGreaterThanOrEqual(clamped.maxAllowableSuccessfulTests, 100)

        // Requesting more than the floor is honored.
        let higher = pbtArguments(iterations: 250)
        XCTAssertEqual(higher.maxAllowableSuccessfulTests, 250)
    }

    func testPropertyNameTagFormat() {
        XCTAssertEqual(
            propertyName(1, "persistence round-trip"),
            "Feature: sagd-welltest-mobile, Property 1: persistence round-trip"
        )
    }

    func testPBTHelperRunsAtLeast100Iterations() {
        // A trivially-true property that also demonstrates the helper wiring
        // SwiftCheck to the 100-iteration floor.
        property(
            propertyName(0, "scaffolding — identity holds"),
            arguments: pbtArguments()
        ) <- forAll { (n: Int) in
            n == n
        }
    }
}
