package app.murmur

import android.app.ActivityManager
import android.content.Context
import app.murmur.core.benchmark.BenchmarkRunner
import app.murmur.core.benchmark.DefaultBenchmarkRunner
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.catalog.UnitHealthRepository
import app.murmur.core.context.DefaultContextAssembler
import app.murmur.core.dictation.DictationDeps
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.dictation.AppNavigator
import app.murmur.core.dictation.Feedback
import app.murmur.core.dictation.OpenTarget
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.MemoryGate
import app.murmur.core.dictation.SystemConditions
import app.murmur.core.engine.Engines
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.SystemMurmurClock
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.prompt.GuardPyPort
import app.murmur.core.prompt.RenderPyPort
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.DefaultDeliveryMetrics
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.data.db.DataStoreSettingsRepository
import app.murmur.data.db.DbModule
import app.murmur.data.db.RoomBenchmarkRepository
import app.murmur.data.db.RoomHistoryRepository
import app.murmur.data.db.RoomMetricsRepository
import app.murmur.data.models.DefaultCatalogRepository
import app.murmur.data.models.DefaultDeviceProfileRepository
import app.murmur.data.models.FileUnitHealthRepository
import app.murmur.data.models.DefaultInstalledModelsRepository
import app.murmur.data.models.DefaultModelResolver
import app.murmur.data.models.ModelDownloads
import app.murmur.data.models.RoomCustomModelStore
import app.murmur.data.models.ModelsModule
import app.murmur.infer.api.BinderEngines
import app.murmur.platform.a11y.A11yBridge
import app.murmur.platform.a11y.BridgedInserter
import app.murmur.platform.a11y.BridgedTargetReader
import app.murmur.platform.audio.AndroidAudioCapture
import app.murmur.platform.audio.OpusAudioStore
import app.murmur.platform.overlay.OverlayFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The :main object graph (ADR-010: manual constructor injection). Everything is lazy so that a skeleton
 * module whose implementation is still TODO only fails when it is actually used.
 * Owner: WP-15 in wave 2 (feature deps, fake wiring for screens), WP-20 from wave 3 (real modules); see docs/wp/README.md.
 */
class AppContainer(private val context: Context) {
    val clock: MurmurClock = SystemMurmurClock
    /** Application scope; the orchestrator gets its own serial view of it (threading policy, ARCHITECTURE.md 14). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serviceConnected = MutableStateFlow(false)

    private val debug = DebugEngines(context)

    /** Binder clients bind from the accessibility service when connected (process importance), else from the app. */
    private val bindContext: () -> Context = { A11yBridge.service.value ?: context }

    // --- data
    private val db by lazy { DbModule.database(context) }
    val audioStore: AudioStore by lazy { OpusAudioStore(context) }
    val history: HistoryRepository by lazy { RoomHistoryRepository(db, audioStore) }
    val metrics: MetricsRepository by lazy { RoomMetricsRepository(db) }
    val benchmarks: BenchmarkRepository by lazy { RoomBenchmarkRepository(db) }
    val settings: SettingsRepository by lazy { DataStoreSettingsRepository(context) }

    // --- models
    val catalog: CatalogRepository by lazy { DefaultCatalogRepository(store = RoomCustomModelStore(db), scope = appScope) }
    /** Downloads of this process; MurmurApp hands it to ModelDownloadJob (ModelJobHost). Preparing = the orchestrator. */
    val modelDownloads: ModelDownloads by lazy { ModelsModule.downloads(context, catalog) { orchestrator } }
    val installed: InstalledModelsRepository by lazy { DefaultInstalledModelsRepository(modelDownloads) }
    // --- engines (real binder clients, or :testing fakes in debug fake mode)
    val engines: Engines by lazy { debug.fakeEnginesOrNull() ?: BinderEngines(bindContext) }
    /** The only owner of the probe (WP-13, ARCHITECTURE 8.4); never throws (CPU-only profile on failure). */
    val deviceProfile: DeviceProfileRepository by lazy { DefaultDeviceProfileRepository(context, engines.asrHost, engines.llmHost) }
    val unitHealth: UnitHealthRepository by lazy { FileUnitHealthRepository(ModelsModule.storage(context), BuildConfig.VERSION_NAME) }
    private val resolver by lazy { DefaultModelResolver(catalog, installed, deviceProfile, unitHealth, benchmarks) { ModelsModule.storage(context).dir(it).path } }
    /** WP-14 runner over the production engines; specs from the resolver's loadSpec (ADR-080). */
    val benchmarkRunner: BenchmarkRunner by lazy {
        DefaultBenchmarkRunner(
            engines = engines, specs = resolver.benchSpecs, records = benchmarks, health = unitHealth, clock = clock,
            environment = AndroidBenchEnvironment(context), renderer = renderer, guard = guard, assets = prompts,
        )
    }

    // --- prompt
    val prompts by lazy { ClasspathPromptAssets() }
    val renderer by lazy { RenderPyPort(prompts) }
    val guard by lazy { GuardPyPort() }

    // --- dictation
    val orchestrator: DictationOrchestrator by lazy {
        DictationOrchestrator(
            DictationDeps(
                clock = clock, settings = settings, models = resolver, engines = engines,
                audio = AndroidAudioCapture(context, audioStore), target = BridgedTargetReader(), inserter = BridgedInserter(),
                context = DefaultContextAssembler(), renderer = renderer, guard = guard, prompts = prompts,
                history = history, metrics = metrics, audioStore = audioStore, feedback = feedback, memory = memory,
                unitHealth = unitHealth, navigator = navigator, permissions = permissions, deliveryMetrics = DefaultDeliveryMetrics(),
                system = system,
            ),
            appScope,
            onInternalError = { android.util.Log.e("MurmurDictation", "internal_error", it) },
        )
    }

    /** WP-03: haptics from the bubble view per Settings.feedback (ADR-044); earcons silent until designed (ADR-062). */
    private val feedback: Feedback = OverlayFeedback { settings.settings.value.feedback }

    /**
     * Opens the app (launcher activity, new task). WP-15 adds the shell's deep-link extras (History entry / Models);
     * until then every target opens the start screen instead of crashing a result-pill tap.
     */
    private val navigator = AppNavigator { _: OpenTarget ->
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { context.startActivity(it) } }
    }

    /** Microphone / notifications / accessibility (A11yStatus) / battery; MurmurApp refreshes it on service state changes. */
    val permissions: PermissionMonitor by lazy { AndroidPermissionMonitor(context) }

    /** Preflight row 6: free storage (filesDir volume) and thermal status. */
    private val system = object : SystemConditions {
        override fun freeStorageBytes(): Long = android.os.StatFs(context.filesDir.path).availableBytes
        override fun thermalCritical(): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus >=
                android.os.PowerManager.THERMAL_STATUS_CRITICAL
    }

    private val memory = MemoryGate {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    }
}
