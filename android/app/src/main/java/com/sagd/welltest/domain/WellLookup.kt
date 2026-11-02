/*
 * Well-lookup use case.
 *
 * Resolves a well identifier (decoded from a QR code) against the local store
 * and assembles the view model shown after a scan: the well, its well tests
 * ordered most-recent-first, and its open maintenance tasks. The use case reads
 * *only* the local store via [WellRepository] and never touches the network, so
 * a local hit returns the same [WellLookupResult.Found] result regardless of
 * connectivity. Connectivity is consulted only to classify a *miss*: an online
 * miss is [WellLookupResult.NotFound] (the well genuinely is not known), while
 * an offline miss is [WellLookupResult.UnavailableOffline] (the well may exist
 * but has not been pulled yet).
 *
 * Mirrors the iOS `WellLookup.swift` behavior exactly. Deterministic over its
 * inputs and the injected collaborators, with no I/O of its own beyond the
 * repository reads. Implemented by task 13.1.
 *
 * Requirements: 6.2, 6.5.
 * Correctness properties: 19 (lookup ordering + open-task filtering, exercised
 * via the repository), 20 (lookup independent of connectivity).
 */
package com.sagd.welltest.domain

// region Connectivity provider

/**
 * A minimal connectivity signal the well-lookup use case consults *only* to
 * classify a lookup miss. It is deliberately tiny — a single boolean — so the
 * domain layer stays free of any platform networking type
 * (`ConnectivityManager`, `NetworkCallback`, …); those platform monitors
 * implement this interface in the integration layer, and tests supply a trivial
 * fake. Mirrors the iOS `ConnectivityProviding` protocol.
 *
 * The provider is never consulted on a local hit: when the well exists in the
 * local store the result is identical whether online or offline (Req 6.5).
 */
interface ConnectivityProviding {
    /**
     * Whether the device currently has network connectivity. Used only to
     * distinguish an online miss ([WellLookupResult.NotFound]) from an offline
     * miss ([WellLookupResult.UnavailableOffline]).
     */
    val isOnline: Boolean
}

/** A fixed connectivity signal, useful for wiring a known state and for tests. */
data class StaticConnectivityProvider(
    override val isOnline: Boolean,
) : ConnectivityProviding

// endregion

// region Well-lookup use case

/**
 * The default [WellLookupUseCase]: looks a well up in the local store and, on a
 * hit, assembles the well together with its tests (most-recent-first, as ordered
 * by the repository) and its open maintenance tasks. On a miss it consults the
 * injected [ConnectivityProviding] to return [WellLookupResult.NotFound] (online)
 * or [WellLookupResult.UnavailableOffline] (offline).
 *
 * The repository is the sole source of data: [lookup] issues only local reads
 * and performs no network access. This is what makes a `Found` result
 * independent of connectivity (Req 6.5, Property 20) and what keeps the
 * most-recent-first / open-only ordering a property of the repository layer
 * (Req 6.2, Property 19).
 *
 * @property repository reads wells, tests, and tasks from the local store. Only
 *   its read methods are used.
 * @property connectivity supplies the online/offline signal used to classify a
 *   lookup miss. It is never consulted on a hit.
 */
class LocalWellLookupUseCase(
    private val repository: WellRepository,
    private val connectivity: ConnectivityProviding,
) : WellLookupUseCase {

    override suspend fun lookup(wellId: String): WellLookupResult {
        // Local read only — never the network. A present well yields the same
        // `Found` result whether the device is online or offline.
        val well = repository.getWell(wellId)

        if (well == null) {
            // Miss: branch on connectivity. Online means the well is genuinely
            // not known; offline means it may simply not have been pulled yet.
            return if (connectivity.isOnline) {
                WellLookupResult.NotFound
            } else {
                WellLookupResult.UnavailableOffline
            }
        }

        // Hit: assemble the well with its tests (most-recent-first per the
        // repository contract) and its open maintenance tasks (the repository
        // filters to status == open). No connectivity check on this path.
        val tests = repository.getWellTests(wellId)
        val openTasks = repository.getOpenMaintenanceTasks(wellId)

        return WellLookupResult.Found(
            well = well,
            wellTests = tests,
            openMaintenanceTasks = openTasks,
        )
    }
}

// endregion
