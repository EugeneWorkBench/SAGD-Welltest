//
//  Composition root — the app dependency graph
//
//  Assembles every layer into one running graph and owns the objects whose
//  lifetime is the app's: the `AppDatabase` (GRDB) and blob store, the
//  repositories, the pure domain use cases, the `CloudBackendClient` (over
//  `URLSessionHTTPTransport` + `GRDBRecordPayloadSource` + `GRDBPulledDataCache`
//  + `CloudBackendConfiguration`), the `DefaultSyncEngine` with all its injected
//  seams (`SyncClock`, `BackoffPolicy`, `ConnectivityProvider`,
//  `BackgroundSyncScheduling`, `OfflineIndicatorPublishing`), and the platform
//  integrations (camera/QR, location).
//
//  This is where task 22.1's `CloudBackendClient` is dropped into the engine's
//  `pushClient` *and* `blobUploadClient` slots — the client conforms to both
//  `SyncPushClient` and `BlobUploadClient`, so one instance fills both seams
//  (task 22.2). The engine never learns any REST mechanics; it only sees the
//  two protocols.
//
//  Dependency rule: construction flows downward only. The one assembly-time
//  cycle — repositories need a `SyncWorkSignaling` to be built, and the engine
//  needs the repositories' `SyncRepository` — is broken with
//  `EngineSyncWorkSignal`: the signal is created first, the repositories and
//  engine are built with it, then the engine is attached to the signal.
//
//  The configuration seam (base URL + auth token) is the single input the graph
//  needs from the environment; everything else is wired here. `AppGraph.make`
//  is `throws` because opening the on-disk store and blob store can fail; the
//  app entry point degrades to a diagnostic screen when it does.
//
//  Wired by task 22.2.

import Foundation

/// The external configuration the composition root needs: where the backend
/// lives and how to authenticate to it. Supplied by the app entry point (from a
/// build setting / environment / secure store) so the graph itself holds no
/// hard-coded endpoint or token.
///
/// `authToken` is modeled as an optional static token here (wrapped into a
/// `StaticAuthTokenProvider`); swapping in a refreshing provider is a one-line
/// change in `make` for an app that needs token refresh.
public struct AppConfiguration: Sendable {
    /// The backend base URL (origin + any common prefix). Endpoint paths
    /// (`/v1/well-tests`, …) are resolved against this by the REST client.
    public var baseURL: URL
    /// The bearer token, or `nil` to run unauthenticated (the backend answers
    /// 401, which the client classifies as retryable).
    public var authToken: String?

    public init(baseURL: URL, authToken: String? = nil) {
        self.baseURL = baseURL
        self.authToken = authToken
    }
}

extension AppConfiguration {

    /// The `Info.plist` / environment key for the backend base URL.
    public static let baseURLKey = "SAGDBackendBaseURL"
    /// The `Info.plist` / environment key for the bearer token.
    public static let authTokenKey = "SAGDBackendAuthToken"
    /// The base URL used when none is configured — a localhost default that is
    /// clearly non-production, so a missing configuration fails loudly (every
    /// push retryably fails) rather than silently targeting a real backend.
    public static let fallbackBaseURL = URL(string: "https://localhost")!

    /// Resolve the configuration from the environment: the app bundle's
    /// `Info.plist` first (where a build setting injects the per-environment
    /// base URL / token), then the process environment (useful for tests and
    /// local runs). This is the single configuration seam the composition root
    /// reads; everything else is wired in code.
    ///
    /// A malformed or absent base URL falls back to a localhost default so the
    /// graph still assembles — the app runs offline-first and simply never
    /// syncs until a valid URL is configured.
    public static func fromEnvironment(
        bundle: Bundle = .main,
        processInfo: ProcessInfo = .processInfo
    ) -> AppConfiguration {
        let rawBaseURL = string(forKey: baseURLKey, bundle: bundle, processInfo: processInfo)
        let token = string(forKey: authTokenKey, bundle: bundle, processInfo: processInfo)
        let baseURL = rawBaseURL.flatMap(URL.init(string:)) ?? fallbackBaseURL
        return AppConfiguration(baseURL: baseURL, authToken: token)
    }

    /// Reads a configuration value from the bundle's `Info.plist`, falling back
    /// to the process environment. Empty strings are treated as absent.
    private static func string(
        forKey key: String,
        bundle: Bundle,
        processInfo: ProcessInfo
    ) -> String? {
        if let value = bundle.object(forInfoDictionaryKey: key) as? String,
           !value.isEmpty {
            return value
        }
        if let value = processInfo.environment[key], !value.isEmpty {
            return value
        }
        return nil
    }
}

/// The assembled application graph. Holds the long-lived collaborators and
/// vends the UI entry points (view models / screens) wired to them.
///
/// Construct once at launch with `AppGraph.make(configuration:)`, call
/// `start()` to begin reacting to connectivity and background execution, and
/// keep the instance alive for the app's lifetime.
public final class AppGraph: @unchecked Sendable {

    // MARK: - Long-lived collaborators

    /// The on-device source of truth.
    public let appDatabase: AppDatabase
    /// The escalation photo blob store.
    public let blobStore: BlobStore

    /// Repositories over the local store.
    public let wellRepository: WellRepository
    public let logRepository: LogRepository
    public let escalationRepository: EscalationRepository
    public let syncRepository: SyncRepository

    /// The REST client — conforms to both `SyncPushClient` and
    /// `BlobUploadClient`, so the single instance fills both engine seams.
    public let backendClient: CloudBackendClient

    /// The global offline indicator the UI observes; the engine flips it.
    public let offlineIndicator: OfflineIndicator

    /// The background sync engine.
    public let syncEngine: DefaultSyncEngine

    // MARK: - Shared seams

    private let connectivity: ConnectivityProvider
    private let connectivitySignal: ConnectivityProviding
    private let clock: SyncClock
    private let workSignal: EngineSyncWorkSignal

    // MARK: - Construction

    private init(
        appDatabase: AppDatabase,
        blobStore: BlobStore,
        wellRepository: WellRepository,
        logRepository: LogRepository,
        escalationRepository: EscalationRepository,
        syncRepository: SyncRepository,
        backendClient: CloudBackendClient,
        offlineIndicator: OfflineIndicator,
        syncEngine: DefaultSyncEngine,
        connectivity: ConnectivityProvider,
        connectivitySignal: ConnectivityProviding,
        clock: SyncClock,
        workSignal: EngineSyncWorkSignal
    ) {
        self.appDatabase = appDatabase
        self.blobStore = blobStore
        self.wellRepository = wellRepository
        self.logRepository = logRepository
        self.escalationRepository = escalationRepository
        self.syncRepository = syncRepository
        self.backendClient = backendClient
        self.offlineIndicator = offlineIndicator
        self.syncEngine = syncEngine
        self.connectivity = connectivity
        self.connectivitySignal = connectivitySignal
        self.clock = clock
        self.workSignal = workSignal
    }

    /// Build the full application graph.
    ///
    /// Opens (or creates and migrates) the on-disk database and blob store,
    /// then wires every layer:
    ///   1. the local store (GRDB + blob store),
    ///   2. the repositories — the save-side ones share one
    ///      `EngineSyncWorkSignal` so a save schedules a sync cycle,
    ///   3. the REST client over the real transport + payload source + cache +
    ///      configuration,
    ///   4. the sync engine, with the REST client in *both* the `pushClient`
    ///      and `blobUploadClient` slots and all timing/connectivity seams,
    ///   5. finally, the engine is attached to the work signal, closing the
    ///      assembly-time cycle.
    ///
    /// - Parameters:
    ///   - configuration: base URL + auth token for the backend.
    ///   - fileManager: injectable for testing; defaults to `.default`.
    ///   - clock: injectable time source; production uses the wall clock.
    ///   - backgroundScheduler: injectable so tests can pass a no-op; the app
    ///     passes the `BGTaskScheduler`-backed scheduler where available.
    /// - Throws: if the database or blob store cannot be opened.
    public static func make(
        configuration: AppConfiguration,
        fileManager: FileManager = .default,
        clock: SyncClock = SystemSyncClock(),
        backgroundScheduler: BackgroundSyncScheduling = AppGraph.defaultBackgroundScheduler()
    ) throws -> AppGraph {

        // 1. Local store: open the existing DB in place (persisted records
        //    reload after restart — Req 1.3) and the blob store, then reclaim
        //    any orphan blobs left by a crash between blob and row writes.
        let appDatabase = try AppDatabase.open(fileManager: fileManager)
        let blobStore = try BlobStore.makeDefault(fileManager: fileManager)
        Self.collectOrphanBlobs(appDatabase: appDatabase, blobStore: blobStore)

        // 2a. The seam that lets a repository save schedule a sync cycle without
        //     the Repository layer depending on the Sync layer. Created first so
        //     it can be injected into the save-side repositories, then attached
        //     to the engine below.
        let workSignal = EngineSyncWorkSignal()

        // 2b. Repositories over the local store. Read-only repositories need no
        //     signal; the save-side repositories share the one work signal.
        let wellRepository = GRDBWellRepository(appDatabase: appDatabase)
        let logRepository = GRDBLogRepository(
            appDatabase: appDatabase,
            syncSignal: workSignal
        )
        let escalationRepository = GRDBEscalationRepository(
            appDatabase: appDatabase,
            blobStore: blobStore,
            syncSignal: workSignal
        )
        let syncRepository = GRDBSyncRepository(appDatabase: appDatabase)

        // 3. REST client. The payload source loads a record's payload for push;
        //    the cache writes pulled reference data back into the store for
        //    offline QR lookup. The configuration carries the base URL and the
        //    auth token provider (the config seam).
        let backendConfiguration = CloudBackendConfiguration(
            baseURL: configuration.baseURL,
            tokenProvider: StaticAuthTokenProvider(token: configuration.authToken)
        )
        let transport = URLSessionHTTPTransport(session: Self.makeURLSession())
        let payloadSource = GRDBRecordPayloadSource(appDatabase: appDatabase)
        let pulledDataCache = GRDBPulledDataCache(appDatabase: appDatabase)
        let backendClient = CloudBackendClient(
            configuration: backendConfiguration,
            transport: transport,
            payloadSource: payloadSource,
            cache: pulledDataCache
        )

        // 4. Sync engine. The escalation blob source resolves an escalation and
        //    its photo blob URL for multipart upload; connectivity and the
        //    offline indicator drive the 60 s restore trigger and the 5 s
        //    offline flip. The REST client fills BOTH push seams — it conforms
        //    to `SyncPushClient` and `BlobUploadClient` (task 22.2).
        let connectivity = Self.makeConnectivityProvider()
        let offlineIndicator = OfflineIndicator()
        let escalationBlobSource = GRDBEscalationBlobSource(
            appDatabase: appDatabase,
            blobStore: blobStore
        )
        let syncEngine = DefaultSyncEngine(
            repository: syncRepository,
            pushClient: backendClient,
            blobUploadClient: backendClient,
            escalationBlobSource: escalationBlobSource,
            backoff: ExponentialBackoffPolicy(),
            clock: clock,
            connectivity: connectivity,
            offlineIndicator: offlineIndicator,
            backgroundScheduler: backgroundScheduler
        )

        // 5. Close the assembly-time cycle: the work signal now drives the
        //    engine, so a save schedules a cycle.
        workSignal.attach(syncEngine)

        // The Domain-layer connectivity signal (`isOnline`) the lookup use case
        // and QR result screen consult. Derived from the same provider so the
        // UI's online/offline annotation matches the engine's view.
        let connectivitySignal = ConnectivityProviderAdapter(provider: connectivity)

        return AppGraph(
            appDatabase: appDatabase,
            blobStore: blobStore,
            wellRepository: wellRepository,
            logRepository: logRepository,
            escalationRepository: escalationRepository,
            syncRepository: syncRepository,
            backendClient: backendClient,
            offlineIndicator: offlineIndicator,
            syncEngine: syncEngine,
            connectivity: connectivity,
            connectivitySignal: connectivitySignal,
            clock: clock,
            workSignal: workSignal
        )
    }

    // MARK: - Lifecycle

    /// Start the engine's connectivity monitoring and background registration.
    /// Call once after `make`, at app launch.
    public func start() async {
        await syncEngine.start()
    }

    // MARK: - Domain entry points (use cases / coordinators)

    /// The domain well-lookup use case, reading only the local store and
    /// consulting connectivity only to classify a miss.
    public func makeWellLookupUseCase() -> WellLookupUseCase {
        LocalWellLookupUseCase(
            repository: wellRepository,
            connectivity: connectivitySignal
        )
    }

    /// A QR scanner coordinator over the production camera source and the
    /// domain lookup use case.
    public func makeQRScannerCoordinator() -> QRScannerCoordinator {
        QRScannerCoordinator(
            source: Self.makeQRCodeSource(),
            lookup: makeWellLookupUseCase(),
            clock: clock
        )
    }

    /// The escalation capture coordinator over the production location provider
    /// and the escalation repository.
    public func makeEscalationCaptureCoordinator() -> EscalationCaptureCoordinator {
        EscalationCaptureCoordinator(
            locationProvider: Self.makeLocationProvider(clock: clock),
            repository: escalationRepository
        )
    }

    // MARK: - Launch housekeeping

    /// Reclaim orphan photo blobs left on disk by a crash between the blob write
    /// and the referencing row commit (Req 7.3/7.4). Best-effort: a failure to
    /// enumerate the blob directory is ignored so launch is never blocked.
    private static func collectOrphanBlobs(appDatabase: AppDatabase, blobStore: BlobStore) {
        let referenced = (try? appDatabase.dbWriter.read { db in
            try EscalationRecord
                .fetchAll(db)
                .map(\.photoRef)
        }) ?? []
        _ = try? blobStore.garbageCollect(referencedRefs: Set(referenced))
    }

    // MARK: - Platform factories

    /// The connectivity provider. Uses the `NWPathMonitor`-backed provider
    /// where the Network framework is available, otherwise an always-satisfied
    /// stub so the graph still assembles on platforms without it.
    private static func makeConnectivityProvider() -> ConnectivityProvider {
        #if canImport(Network)
        return NWPathConnectivityProvider()
        #else
        return AlwaysSatisfiedConnectivityProvider()
        #endif
    }

    /// The background sync scheduler. `BGTaskScheduler`-backed where available,
    /// otherwise a no-op.
    private static func defaultBackgroundScheduler() -> BackgroundSyncScheduling {
        #if canImport(BackgroundTasks)
        return BGTaskSyncScheduler()
        #else
        return NoopBackgroundSyncScheduler()
        #endif
    }

    /// The production QR code source where AVFoundation is available; otherwise
    /// a source that never emits, which the coordinator surfaces as
    /// `unreadable` after its budget — safe on platforms without a camera.
    private static func makeQRCodeSource() -> QRCodeSource {
        #if canImport(AVFoundation)
        return AVFoundationQRCodeSource()
        #else
        return NeverDecodingQRCodeSource()
        #endif
    }

    /// The production location provider where CoreLocation is available;
    /// otherwise a provider that always times out so escalation capture still
    /// proceeds with `locationUnavailable` (Req 7.5).
    private static func makeLocationProvider(clock: SyncClock) -> LocationProviding {
        #if canImport(CoreLocation)
        return CoreLocationProvider(clock: clock)
        #else
        return AlwaysTimingOutLocationProvider()
        #endif
    }

    /// The `URLSession` the REST transport runs on.
    ///
    /// Task 22.1's `URLSessionHTTPTransport` performs every request through the
    /// `async` `session.data(for:)` API, which a *background*
    /// `URLSessionConfiguration` does not support (background sessions deliver
    /// results through a delegate and require `uploadTask`/`downloadTask`, not
    /// the async convenience). So this wiring uses a default-configuration
    /// session: it is the session the transport can actually drive, and it
    /// still survives foreground/backgrounded cycles because the
    /// `BGTaskScheduler`-backed `BackgroundSyncScheduling` is what grants the
    /// engine background time to run a cycle (design "Connectivity and
    /// background execution"). A delegate-based background-upload session for
    /// escalation photos is a focused follow-up that would extend the transport
    /// seam, not the composition root, so it is intentionally out of scope here.
    private static func makeURLSession() -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = true
        return URLSession(configuration: configuration)
    }
}

// MARK: - Connectivity signal adapter

/// Adapts the Sync layer's `ConnectivityProvider` (which reports a
/// `ConnectivityStatus` and a stream) to the Domain layer's tiny
/// `ConnectivityProviding` (a single `isOnline` boolean) the well-lookup use
/// case and the QR result screen consult. Reading `currentStatus` is the whole
/// job — the Domain signal is only ever queried to classify a lookup miss or to
/// annotate a found result as local-storage-sourced.
private struct ConnectivityProviderAdapter: ConnectivityProviding {
    let provider: ConnectivityProvider
    var isOnline: Bool { provider.currentStatus == .satisfied }
}

// MARK: - Fallback seams (platforms without the system frameworks)

#if !canImport(Network)
/// Always-satisfied connectivity for platforms without the Network framework,
/// so the graph assembles and cycles run in those environments.
private struct AlwaysSatisfiedConnectivityProvider: ConnectivityProvider {
    var currentStatus: ConnectivityStatus { .satisfied }
    func statusStream() -> AsyncStream<ConnectivityStatus> {
        AsyncStream { continuation in
            continuation.yield(.satisfied)
            continuation.finish()
        }
    }
}
#endif

#if !canImport(AVFoundation)
/// A QR source that never decodes; the coordinator reports `unreadable` after
/// its unreadable budget. Used where AVFoundation (a camera) is unavailable.
private struct NeverDecodingQRCodeSource: QRCodeSource {
    func decodedStrings() -> AsyncStream<String> {
        AsyncStream { $0.finish() }
    }
    func start() async {}
    func stop() async {}
}
#endif

#if !canImport(CoreLocation)
/// A location provider that always times out, so escalation capture proceeds
/// with `locationUnavailable` set (Req 7.5). Used where CoreLocation is
/// unavailable.
private struct AlwaysTimingOutLocationProvider: LocationProviding {
    func requestFix() async -> LocationResult { .timedOut }
}
#endif

// MARK: - SwiftUI view-model factories

// The view models that bind directly to SwiftUI live behind `canImport(SwiftUI)`
// in their own files, so the factories that vend them are guarded the same way.
// The Domain use cases and integration coordinators above are platform-agnostic
// and remain available everywhere.
#if canImport(SwiftUI)
extension AppGraph {

    /// The well-selection view model, backed by the GRDB well list.
    @MainActor
    public func makeWellSelectionViewModel() -> WellSelectionViewModel {
        WellSelectionViewModel(provider: GRDBWellListProvider(appDatabase: appDatabase))
    }

    /// The offline-indicator view model bound to the shared indicator.
    @MainActor
    public func makeOfflineIndicatorViewModel() -> OfflineIndicatorViewModel {
        OfflineIndicatorViewModel(indicator: offlineIndicator)
    }

    /// A sync-state badge view model for a specific record.
    @MainActor
    public func makeSyncStateBadgeViewModel(
        recordId: UUID,
        initialState: SyncState = .pending
    ) -> SyncStateBadgeViewModel {
        SyncStateBadgeViewModel(
            recordId: recordId,
            repository: syncRepository,
            initialState: initialState
        )
    }

    /// The subcool estimator view model (pure domain, no graph collaborators).
    @MainActor
    public func makeSubcoolEstimatorViewModel() -> SubcoolEstimatorViewModel {
        SubcoolEstimatorViewModel()
    }

    /// The QR result view model wired to a fresh scanner coordinator and the
    /// connectivity signal used for the local-storage annotation.
    @MainActor
    public func makeQRResultViewModel() -> QRResultViewModel {
        QRResultViewModel(
            coordinator: makeQRScannerCoordinator(),
            connectivity: connectivitySignal
        )
    }

    /// The escalation capture view model for a set of selectable wells.
    @MainActor
    public func makeEscalationCaptureViewModel(
        availableWells: [Well]
    ) -> EscalationCaptureViewModel {
        EscalationCaptureViewModel(
            availableWells: availableWells,
            coordinator: makeEscalationCaptureCoordinator()
        )
    }
}
#endif
