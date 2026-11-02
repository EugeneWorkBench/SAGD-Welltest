# SAGD WellTest Mobile — iOS

Offline-first SwiftUI client for SAGD field data capture. This package implements
the layered architecture from the spec design document.

## Opening the project

This is a Swift Package. On a macOS machine with Xcode:

- Open the `ios/` folder directly in Xcode (`File > Open…`), or
- Run `swift build` / `swift test` from the `ios/` directory.

Xcode resolves the Swift Package dependencies automatically on first open.

## Layer structure

Source groups under `Sources/SAGDWellTest/` follow the design's dependency rule
(dependencies point downward only):

| Group          | Responsibility                                                      |
| -------------- | ------------------------------------------------------------------- |
| `UI`           | SwiftUI screens, view models, app entry point                       |
| `Domain`       | Pure logic: subcool estimator, validators, well lookup, escalation  |
| `Repository`   | Read/write facade over the local store; assigns sync metadata       |
| `LocalStore`   | SQLite via GRDB + photo blob file store (source of truth on-device) |
| `Sync`         | Connectivity monitor, oldest-first push queue, retry/backoff        |
| `Integrations` | Camera/QR (AVFoundation), location (CoreLocation)                   |
| `Network`      | URLSession REST client for the Cloud_Backend                        |

## Dependencies

| Package                                        | Scope             | Use                      |
| ---------------------------------------------- | ----------------- | ------------------------ |
| [GRDB](https://github.com/groue/GRDB.swift)    | app + library     | SQLite local store       |
| [SwiftCheck](https://github.com/typelift/SwiftCheck) | test target only | property-based testing |

## Property-based testing

`Tests/SAGDWellTestTests/PropertyTesting.swift` provides `pbtArguments()`, which
clamps SwiftCheck to a **minimum of 100 successful iterations** per property.
Each correctness property is tagged
`Feature: sagd-welltest-mobile, Property {n}: {text}` via `propertyName(_:_:)`.
