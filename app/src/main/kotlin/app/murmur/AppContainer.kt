package app.murmur

import android.app.ActivityManager
import android.content.Context
import app.murmur.core.benchmark.BenchmarkRunner
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
import app.murmur.core.dictation.HapticKind
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
    private val resolver by lazy { DefaultModelResolver(catalog, installed, deviceProfile, unitHealth, benchmarks) }
    val benchmarkRunner: BenchmarkRunner by lazy { TODO("WP-14: BenchmarkRunner over engines + hosts") }

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
        )
    }

    /** WP-03 provides the overlay-view implementation; this placeholder does nothing. */
    private val feedback = object : Feedback {
        override fun haptic(kind: HapticKind) {}
        override fun sound(kind: HapticKind) {}
    }

    /** WP-15: Intent to MainActivity with the shell's deep-link extras (open History entry / Models). */
    private val navigator = AppNavigator { _: OpenTarget -> TODO("WP-15") }

    /** WP-15: PermissionMonitor over PackageManager / NotificationManager / PowerManager / Settings.Secure + RestrictedSettingsProbe. */
    private val permissions: PermissionMonitor by lazy { TODO("WP-15") }

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
