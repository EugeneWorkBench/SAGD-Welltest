/*
 * Application composition root — the hand-wired dependency graph.
 *
 * This is the single place the whole app is assembled: the Room database and
 * its DAOs, the blob store, the repositories, the pure-domain use cases, the
 * REST push/pull clients over the real HTTP transport, the sync engine with its
 * injected seams, the platform-integration factories (QR scanner, location),
 * and the configuration seam (base URL + auth token). It matches the project's
 * existing style — plain constructor wiring, no DI framework — mirroring the
 * iOS `RootView` composition root while covering the full task-23.2 graph
 * (design.md "Sync engine component", "Cloud Backend API Contract").
 *
 * Lifecycle and ownership:
 *   - One [AppGraph] per process, created by [SagdWellTestApp] on startup via
 *     [create] and exposed through `SagdWellTestApp.graph`.
 *   - Collaborators are built eagerly in constructor order (each depends only on
 *     earlier ones) so a miswiring surfaces at startup, not lazily mid-session.
 *   - The graph owns the process [CoroutineScope] used for connectivity
 *     monitoring; [shutdown] cancels it (used in tests / teardown).
 *
 * Worker seam: WorkManager constructs [SyncWorker] with only a Context, so it
 * cannot receive the [SyncCycleRunner] by constructor injection. The graph
 * publishes the runner to the process-wide [SyncWorkerDependencies] holder
 * (the seam the worker reads) in [start], matching the pattern that holder's
 * KDoc already documents. No custom WorkerFactory is needed because the worker
 * takes no injected constructor parameters; the default factory instantiates it
 * and it reads the runner from the holder.
 *
 * Introduced by task 23.2.
 */
package com.sagd.welltest

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.sagd.welltest.domain.ConnectivityProviding
import com.sagd.welltest.domain.EscalationValidator
import com.sagd.welltest.domain.InjectionLogValidator
import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.ProductionLogValidator
import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.WellLookupUseCase
import com.sagd.welltest.domain.WellRepository
import com.sagd.welltest.domain.DefaultEscalationValidator
import com.sagd.welltest.domain.DefaultInjectionLogValidator
import com.sagd.welltest.domain.DefaultProductionLogValidator
import com.sagd.welltest.domain.LocalWellLookupUseCase
import com.sagd.welltest.domain.SubcoolEstimator
import com.sagd.welltest.integrations.EscalationCaptureCoordinator
import com.sagd.welltest.localstore.AppDatabase
import com.sagd.welltest.localstore.BlobStore
import com.sagd.welltest.network.AuthTokenProvider
import com.sagd.welltest.network.BackendConfig
import com.sagd.welltest.network.RestPullClient
import com.sagd.welltest.network.RestSyncPushClient
import com.sagd.welltest.network.StaticAuthTokenProvider
import com.sagd.welltest.network.UrlConnectionHttpTransport
import com.sagd.welltest.repository.EscalationRepositoryImpl
import com.sagd.welltest.repository.FileSystemStorageGuard
import com.sagd.welltest.repository.LogRepositoryImpl
import com.sagd.welltest.repository.RoomEscalationBlobSource
import com.sagd.welltest.repository.RoomPullCache
import com.sagd.welltest.repository.RoomPushPayloadSource
import com.sagd.welltest.repository.SyncRepositoryImpl
import com.sagd.welltest.repository.WellListProvider
import com.sagd.welltest.repository.WellRepositoryImpl
import com.sagd.welltest.sync.ConnectivityManagerConnectivityProvider
import com.sagd.welltest.sync.ConnectivityProvider
import com.sagd.welltest.sync.ConnectivityStatus
import com.sagd.welltest.sync.OfflineIndicator
import com.sagd.welltest.sync.SyncCycleRunner
import com.sagd.welltest.sync.SyncWorkScheduler
import com.sagd.welltest.sync.SyncWorkerDependencies
import com.sagd.welltest.sync.WorkManagerSyncEngine
import com.sagd.welltest.sync.WorkManagerSyncWorkScheduler
import com.sagd.welltest.integrations.FusedLocationProvider
import com.sagd.welltest.integrations.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Static configuration seam for the Cloud_Backend connection.
 *
 * The base URL and auth token are the two things a deployment points at a real
 * environment; they are grouped here so the single call site that builds the
 * graph decides them. The defaults are safe placeholders for a dev build —
 * override by passing an [AppConfig] to [AppGraph.create] (e.g. from
 * `BuildConfig` fields or a secure token store).
 *
 * @property baseUrl the Cloud_Backend origin without a trailing slash
 *   (e.g. `https://api.sagd.example.com`); endpoint paths are appended to it.
 * @property tokenProvider supplies the bearer token for the `Authorization`
 *   header. Defaults to a no-token provider so an unconfigured build still
 *   builds its graph; the backend's 401 then surfaces as a retryable failure
 *   until a token is wired in.
 */
data class AppConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val tokenProvider: AuthTokenProvider = StaticAuthTokenProvider(null),
) {
    companion object {
        /** Placeholder dev base URL; a real build overrides this. */
        const val DEFAULT_BASE_URL: String = "https://api.sagd.example.com"
    }
}

/**
 * The fully wired application graph.
 *
 * Build it with [create]; call [start] once after construction to publish the
 * worker seam, reclaim orphan blobs, begin connectivity monitoring, and
 * schedule the periodic sync fallback.
 */
class AppGraph private constructor(
    private val appContext: Context,
    val config: AppConfig,
) {
    /** Process scope for long-lived observers (connectivity monitoring). */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    // ---- Local store ----

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val blobStore: BlobStore = BlobStore(appContext.filesDir)

    private val wellDao = database.wellDao()
    private val wellTestDao = database.wellTestDao()
    private val wellheadLogDao = database.wellheadLogDao()
    private val subcoolReadingDao = database.subcoolReadingDao()
    private val maintenanceTaskDao = database.maintenanceTaskDao()
    private val escalationDao = database.escalationDao()

    /** Free-space guard rooted at the files dir, which hosts both DB and blobs. */
    private val storageGuard = FileSystemStorageGuard(appContext.filesDir)

    // ---- Repositories ----

    val wellRepository: WellRepository =
        WellRepositoryImpl(wellDao, wellTestDao, maintenanceTaskDao)

    /** Full well list for the selection screen (outside the narrow domain contract). */
    val wellListProvider: WellListProvider = WellListProvider(wellDao)

    val logRepository: LogRepository =
        LogRepositoryImpl(database, wellheadLogDao, wellTestDao, storageGuard)

    val escalationRepository =
        EscalationRepositoryImpl(database, escalationDao, blobStore, storageGuard)

    val syncRepository: SyncRepository =
        SyncRepositoryImpl(wellTestDao, wellheadLogDao, subcoolReadingDao, escalationDao)

    // ---- Pure domain use cases ----

    val productionValidator: ProductionLogValidator = DefaultProductionLogValidator()
    val injectionValidator: InjectionLogValidator = DefaultInjectionLogValidator()
    val escalationValidator: EscalationValidator = DefaultEscalationValidator()

    /** A subcool estimator over the bundled steam table (pure). */
    val subcoolEstimator = SubcoolEstimator()

    // ---- Network layer ----

    /** Base URL + auth token seam (design "Cloud Backend API Contract"). */
    val backendConfig = BackendConfig(config.baseUrl, config.tokenProvider)

    /** The one socket-touching component; everything above it is stubbed in tests. */
    private val httpTransport = UrlConnectionHttpTransport()

    /** Loads a queued record's payload from Room for the push client. */
    private val pushPayloadSource =
        RoomPushPayloadSource(wellTestDao, wellheadLogDao, subcoolReadingDao)

    /**
     * The REST client. It is both [com.sagd.welltest.network.SyncPushClient] and
     * [com.sagd.welltest.network.BlobUploadClient]; the sync engine routes JSON
     * records through its push() and escalations through its upload() (Req 2.1,
     * 2.3, 7.6).
     */
    val restClient = RestSyncPushClient(
        config = backendConfig,
        transport = httpTransport,
        payloadSource = pushPayloadSource,
    )

    /** Writes pulled reference data into Room so QR lookup works offline. */
    private val pullCache = RoomPullCache(wellDao, wellTestDao, maintenanceTaskDao)

    /** Pulls reference data (wells, tests, open tasks) into the local store. */
    val pullClient = RestPullClient(
        config = backendConfig,
        transport = httpTransport,
        cache = pullCache,
    )

    // ---- Sync engine ----

    /** Resolves an escalation and its on-disk photo blob for the multipart push. */
    private val escalationBlobSource = RoomEscalationBlobSource(escalationDao, blobStore)

    /** The global offline indicator the UI banner observes (Req 2.8). */
    val offlineIndicator = OfflineIndicator()

    private val connectivityManager: ConnectivityManager =
        requireNotNull(appContext.getSystemService(ConnectivityManager::class.java)) {
            "ConnectivityManager is unavailable on this device"
        }

    /** Production connectivity source backed by ConnectivityManager callbacks. */
    val connectivityProvider: ConnectivityProvider =
        ConnectivityManagerConnectivityProvider(connectivityManager)

    private val workScheduler: SyncWorkScheduler =
        WorkManagerSyncWorkScheduler(WorkManager.getInstance(appContext))

    /**
     * The platform-agnostic push loop. The [SyncWorker] delegates to this via
     * the [SyncWorkerDependencies] holder (published in [start]).
     */
    val cycleRunner = SyncCycleRunner(
        syncRepository = syncRepository,
        pushClient = restClient,
        blobUploadClient = restClient,
        blobSource = escalationBlobSource,
    )

    /** The WorkManager-backed sync engine (connectivity triggers + blob upload). */
    val syncEngine = WorkManagerSyncEngine(
        cycleRunner = cycleRunner,
        syncRepository = syncRepository,
        blobUploadClient = restClient,
        blobSource = escalationBlobSource,
        connectivityProvider = connectivityProvider,
        offlineIndicator = offlineIndicator,
        workScheduler = workScheduler,
    )

    // ---- Integrations ----

    /** Bounded (30s) GPS fix source over the fused location provider (Req 7.1, 7.5). */
    val locationProvider: LocationProvider =
        FusedLocationProvider.create(
            LocationServices.getFusedLocationProviderClient(appContext),
        )

    /** Validate -> geotag -> persist for one escalation (Req 7.1-7.5). */
    val escalationCaptureCoordinator = EscalationCaptureCoordinator(
        validator = escalationValidator,
        locationProvider = locationProvider,
        repository = escalationRepository,
    )

    // ---- Domain use cases that depend on connectivity ----

    /**
     * Domain-level connectivity signal (a single boolean) the well-lookup use
     * case and the QR result view model consult. It is an adapter over the sync
     * layer's richer [ConnectivityProvider] so there is one source of truth for
     * connectivity: SATISFIED maps to online.
     */
    val connectivityProviding: ConnectivityProviding = object : ConnectivityProviding {
        override val isOnline: Boolean
            get() = connectivityProvider.currentStatus == ConnectivityStatus.SATISFIED
    }

    /** QR well lookup over the local store, connectivity used only to classify a miss. */
    val wellLookupUseCase: WellLookupUseCase =
        LocalWellLookupUseCase(wellRepository, connectivityProviding)

    // ---- Lifecycle ----

    private var monitoringJob: Job? = null

    /**
     * Finish startup wiring. Idempotent — calling twice re-publishes the same
     * runner and re-ensures the periodic schedule without duplicating observers.
     *
     *   1. Publish the [SyncCycleRunner] to the worker seam so a WorkManager-
     *      instantiated [SyncWorker] can run the cycle (task 23.2 note in the
     *      holder's KDoc).
     *   2. Reclaim orphan photo blobs left by a crash between blob-write and
     *      row-commit (Req 7.3, 7.4): sweep every blob not referenced by a
     *      persisted escalation row.
     *   3. Start connectivity monitoring so the offline indicator flips within
     *      5s of a loss and a sync is enqueued within 60s of a restore (Req 2.2,
     *      2.8).
     *   4. Ensure the periodic fallback sync is scheduled so the queue still
     *      drains if no connectivity callback fires.
     */
    fun start() {
        SyncWorkerDependencies.cycleRunner = cycleRunner

        appScope.launchGarbageCollectOrphanBlobs()

        if (monitoringJob == null) {
            monitoringJob = syncEngine.startMonitoring(appScope)
        }

        workScheduler.ensurePeriodicSync()
    }

    /** Cancel process-scoped observers. Primarily for tests / teardown. */
    fun shutdown() {
        appScope.cancel()
        monitoringJob = null
        // Only clear the holder if it still points at this graph's runner.
        if (SyncWorkerDependencies.cycleRunner === cycleRunner) {
            SyncWorkerDependencies.cycleRunner = null
        }
    }

    /**
     * Launch the launch-time orphan-blob sweep: read every referenced photoRef
     * from the escalation table and ask the blob store to delete the rest
     * (Req 7.3, 7.4). Runs off the main thread on [appScope].
     */
    private fun CoroutineScope.launchGarbageCollectOrphanBlobs(): Job =
        launch {
            val referenced = escalationDao.allPhotoRefs().toSet()
            blobStore.garbageCollect(referenced)
        }

    companion object {
        /**
         * Build the process graph. Call [start] afterward to publish the worker
         * seam, run the orphan-blob sweep, and begin connectivity monitoring.
         *
         * @param context any context; the application context is used so the
         *   graph never holds an Activity.
         * @param config the base-URL + auth-token seam; defaults to a dev
         *   placeholder with no token.
         */
        fun create(context: Context, config: AppConfig = AppConfig()): AppGraph =
            AppGraph(context.applicationContext, config)
    }
}
