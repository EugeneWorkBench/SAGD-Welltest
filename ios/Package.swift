// swift-tools-version: 5.9
import PackageDescription

// SAGD WellTest Mobile — iOS client package.
//
// This package models the layered architecture described in the spec design
// document as a single library product composed of per-layer source groups.
// It is intended to be opened in Xcode (File > Open > this folder) as a Swift
// Package, where the `SAGDWellTest` library is consumed by the iOS app target,
// or built directly with `swift build` / `swift test` on a macOS toolchain.
//
// Layer source groups live under Sources/SAGDWellTest/{Domain, Repository,
// LocalStore, Sync, Integrations, UI, Network}. Dependencies point downward
// only (UI -> Domain -> Repository -> LocalStore; Sync -> LocalStore), matching
// the design's dependency rule.
let package = Package(
    name: "SAGDWellTest",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "SAGDWellTest",
            targets: ["SAGDWellTest"]
        )
    ],
    dependencies: [
        // Local store: SQLite via GRDB (used by the app/library target).
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "6.0.0"),
        // Property-based testing: SwiftCheck (test target only).
        .package(url: "https://github.com/typelift/SwiftCheck.git", from: "0.12.0")
    ],
    targets: [
        .target(
            name: "SAGDWellTest",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift")
            ],
            path: "Sources/SAGDWellTest"
        ),
        .testTarget(
            name: "SAGDWellTestTests",
            dependencies: [
                "SAGDWellTest",
                // SwiftCheck is referenced only by the test target.
                .product(name: "SwiftCheck", package: "SwiftCheck")
            ],
            path: "Tests/SAGDWellTestTests"
        )
    ]
)
