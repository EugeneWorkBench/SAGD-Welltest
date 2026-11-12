//
//  App target entry point for the SAGD WellTest iOS app.
//
//  The reusable UI, domain, sync, and storage code all live in the
//  `SAGDWellTest` Swift Package (../../). This thin app target exists only to
//  produce a runnable `.app` bundle: it depends on the package and presents the
//  package's `RootView`, which builds the full `AppGraph` composition root and
//  starts the sync engine.
//
//  The package's own `@main struct SAGDWellTestApp` is only compiled into the
//  library for reference/preview use; this target provides the real process
//  entry point so there is exactly one `@main` in the app binary.
//

import SwiftUI
import SAGDWellTest

@main
struct SAGDWellTestAppMain: App {
    var body: some Scene {
        WindowGroup {
            SAGDWellTestRootView()
        }
    }
}
