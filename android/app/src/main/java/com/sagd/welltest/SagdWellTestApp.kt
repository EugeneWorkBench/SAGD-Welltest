/*
 * Application entry point and composition-root owner.
 *
 * Builds the process-wide [AppGraph] (the hand-wired dependency graph: Room
 * database + DAOs, repositories, domain use cases, REST push/pull clients, the
 * sync engine, and the platform integrations) and finishes startup wiring via
 * [AppGraph.start]: it publishes the sync-cycle runner to the WorkManager worker
 * seam, reclaims orphan photo blobs, begins connectivity monitoring, and
 * schedules the periodic fallback sync.
 *
 * The graph is exposed as a property so the UI (MainActivity) can read the
 * collaborators it needs to construct view models, matching the project's
 * manual-DI style (no framework). Mirrors the iOS composition root in
 * `SAGDWellTestApp` / `RootView`.
 *
 * Finalized by task 23.2.
 */
package com.sagd.welltest

import android.app.Application

class SagdWellTestApp : Application() {

    /**
     * The process dependency graph. Built in [onCreate] before any Activity or
     * WorkManager worker can need it. `lateinit` because it needs the
     * Application context, which is available only once [onCreate] runs.
     */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this).also { it.start() }
    }
}
