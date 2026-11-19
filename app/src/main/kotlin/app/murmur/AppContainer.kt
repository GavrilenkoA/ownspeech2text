package app.murmur

import android.app.ActivityManager
import android.content.Context
import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.benchmark.BenchmarkRunner
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.CustomModelResolver
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.UnitHealthRepository
import app.murmur.core.context.DefaultContextAssembler
import app.murmur.core.dictation.AppNavigator
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.DictationDeps
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.dictation.Feedback
import app.murmur.core.dictation.HapticKind
import app.murmur.core.dictation.MemoryGate
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.SystemConditions
import app.murmur.core.engine.Engines
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.MurmurError
import app.murmur.core.model.SystemMurmurClock
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.prompt.GuardPyPort
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.prompt.RenderPyPort
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.DefaultDeliveryMetrics
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.data.db.DbModule
import app.murmur.data.db.Retention
import app.murmur.data.models.DefaultCatalogRepository
import app.murmur.data.models.DefaultDeviceProfileRepository
import app.murmur.data.models.DefaultInstalledModelsRepository
import app.murmur.data.models.DefaultModelResolver
import app.murmur.data.models.FileUnitHealthRepository
import app.murmur.data.models.HfCustomModelResolver
import app.murmur.data.models.ModelDownloads
import app.murmur.data.models.ModelsModule
import app.murmur.data.models.RoomCustomModelStore
import app.murmur.feature.dashboard.DashboardDeps
import app.murmur.feature.history.HistoryDeps
import app.murmur.feature.models.ModelsDeps
import app.murmur.feature.onboarding.OnboardingDeps
import app.murmur.feature.settings.SettingsDeps
import app.murmur.infer.api.BinderEngines
import app.murmur.platform.a11y.A11yBridge
import app.murmur.platform.a11y.A11yStatus
import app.murmur.platform.a11y.BridgedInserter
import app.murmur.platform.a11y.BridgedTargetReader
import app.murmur.platform.audio.AndroidAudioCapture
import app.murmur.platform.audio.AndroidStatusNotifier
import app.murmur.platform.audio.OpusAudioStore
import app.murmur.shell.IntentAppNavigator
import app.murmur.ui.design.AppInfoProvider
import app.murmur.wiring.AndroidPermissionMonitor
import app.murmur.wiring.FakeData
import app.murmur.wiring.PackageAppInfoProvider
import app.murmur.wiring.StatusNotificationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * The :main object graph (ADR-010: manual constructor injection). Everything is lazy: the container is built in
 * Application.onCreate and a part is constructed when first used. Debug builds can swap inference or the whole data
 * side for :testing fakes ([DebugWiring], D6); release builds use the real modules only.
 * Owner: WP-15 in wave 2 (feature deps, ports, fake wiring for screens), WP-20 from wave 3 (real modules); docs/wp/README.
 */
class AppContainer(private val context: Context) {
    val clock: MurmurClock = SystemMurmurClock
    /** Application scope; the orchestrator gets its own serial view of it (threading policy, ARCHITECTURE.md 14). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** onServiceConnected / onUnbind of the accessibility service (A11yServiceHost.onServiceState). */
    val serviceConnected = MutableStateFlow(false)

    private val replacements by lazy { DebugWiring(context, appScope).replacements() }
    private val fake: FakeData? get() = replacements.data
    /** True when the debug fake container replaces the data side (shell and screenshot tests). */
    val fakeContainer: Boolean get() = fake != null

    /** Binder clients bind from the accessibility service when connected (process importance), else from the app. */
    private val serviceOrApp: () -> Context = { A11yBridge.service.value ?: context }

    // --- data (WP-12): one database and one settings DataStore per process (ADR-072)
    private val db by lazy { DbModule.database(context) }
    val audioStore: AudioStore by lazy { fake?.audioStore ?: OpusAudioStore(context) }
    val history: HistoryRepository by lazy { fake?.history ?: DbModule.history(db, audioStore) }
    val metrics: MetricsRepository by lazy { fake?.metrics ?: DbModule.metrics(db) }
    val benchmarks: BenchmarkRepository by lazy { fake?.benchmarks ?: DbModule.benchmarks(db) }
    val settings: SettingsRepository by lazy { fake?.settings ?: DbModule.settings(context, appScope) }
    /** Retention now (Settings "Keep history" shortening, WP-12 runNow); the scheduler only for the real database. */
    private val retentionScheduler by lazy { DbModule.retention(context, db, history, metrics, audioStore, settings, clock, appScope) }
    private val fakeRetention by lazy {
        Retention(history, metrics, audioStore, clock) { history.entries().first().mapNotNull { it.audio?.path }.toSet() }
    }
    private val retentionNow: suspend (Int) -> Int = { days -> if (fake != null) fakeRetention.runNow(days) else retentionScheduler.runNow(days) }

    // --- models (WP-13)
    val catalog: CatalogRepository by lazy { fake?.catalog ?: DefaultCatalogRepository(store = RoomCustomModelStore(db), scope = appScope) }
    /** Downloads of this process; MurmurApp hands it to ModelDownloadJob (ModelJobHost). Preparing = the orchestrator. */
    val modelDownloads: ModelDownloads by lazy { ModelsModule.downloads(context, catalog, scope = appScope) { orchestrator } }
    val installed: InstalledModelsRepository by lazy { fake?.installed ?: DefaultInstalledModelsRepository(modelDownloads) }
    // --- engines (real binder clients, or :testing fakes in debug)
    val engines: Engines by lazy { replacements.engines ?: BinderEngines(serviceOrApp) }
    /** The only owner of the probe (WP-13, ARCHITECTURE 8.4); never throws (CPU-only profile on failure). */
    val deviceProfile: DeviceProfileRepository by lazy { fake?.device ?: DefaultDeviceProfileRepository(context, engines.asrHost, engines.llmHost) }
    val unitHealth: UnitHealthRepository by lazy { fake?.unitHealth ?: FileUnitHealthRepository(ModelsModule.storage(context), ModelsModule.appVersion(context)) }
    /** The production resolver; kept as its own type for the benchmark runner's specs (ADR-080, `resolver.benchSpecs`). */
    private val resolver by lazy { DefaultModelResolver(catalog, installed, deviceProfile, unitHealth, benchmarks) { ModelsModule.storage(context).dir(it).path } }
    /** What the orchestrator and the status notification resolve with: the debug fake container's resolver, else [resolver]. */
    private val models: ModelResolver by lazy { fake?.resolver ?: resolver }
    private val customModels: CustomModelResolver by lazy { HfCustomModelResolver() }
    /**
     * WIRING SLOT (WP-14, ADR-080): main wires `DefaultBenchmarkRunner(engines, specs = resolver.benchSpecs, ...)` here;
     * at the merge that runner replaces [RunnerNotWired]. The :testing runner exists only inside the debug fake
     * container ([FakeData.benchmarkRunner], over the in-memory [benchmarks]); a normal debug build, fake engines
     * included, never stores its made-up records. This branch's base has no WP-14 runner yet.
     */
    val benchmarkRunner: BenchmarkRunner by lazy { fake?.benchmarkRunner ?: RunnerNotWired }

    // --- prompt (WP-04)
    val prompts by lazy { ClasspathPromptAssets() }
    val renderer: PromptRenderer by lazy { replacements.renderer ?: RenderPyPort(prompts) }
    val guard: OutputGuard by lazy { replacements.guard ?: GuardPyPort() }

    // --- platform ports implemented here (WP-15)
    /** The one permission monitor (A11yStatus-based); MurmurApp and MainActivity refresh it (see its KDoc). */
    val permissions: PermissionMonitor by lazy { AndroidPermissionMonitor(context) }
    val apps: AppInfoProvider by lazy { PackageAppInfoProvider(context) }
    private val navigator: AppNavigator by lazy { IntentAppNavigator(context) }
    private val statusNotifier by lazy { AndroidStatusNotifier(context) }
    private val audioCapture: AudioCapture by lazy {
        // The microphone FGS starts from the accessibility-service context at press time (WP-09, platform spike b).
        fake?.audio ?: AndroidAudioCapture(context, audioStore, fgsContext = serviceOrApp)
    }
    private val memory: MemoryGate by lazy {
        fake?.memory ?: MemoryGate {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            mi.availMem / (1024 * 1024)
        }
    }

    /** Preflight row 6: free storage (filesDir volume) and thermal status (ADR-068). */
    private val system = object : SystemConditions {
        override fun freeStorageBytes(): Long = android.os.StatFs(context.filesDir.path).availableBytes
        override fun thermalCritical(): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus >=
                android.os.PowerManager.THERMAL_STATUS_CRITICAL
    }

    /** WP-03 provides the overlay-view implementation (haptics from the bubble view, ADR-044); not merged yet. */
    private val feedback = object : Feedback {
        override fun haptic(kind: HapticKind) {}
        override fun sound(kind: HapticKind) {}
    }

    // --- dictation (WP-11)
    val orchestrator: DictationOrchestrator by lazy {
        DictationOrchestrator(
            DictationDeps(
                clock = clock, settings = settings, models = models, engines = engines,
                audio = audioCapture, target = BridgedTargetReader(), inserter = BridgedInserter(),
                context = DefaultContextAssembler(), renderer = renderer, guard = guard, prompts = prompts,
                history = history, metrics = metrics, audioStore = audioStore, feedback = feedback, memory = memory,
                unitHealth = unitHealth, navigator = navigator, permissions = permissions, deliveryMetrics = DefaultDeliveryMetrics(),
                system = system,
            ),
            appScope,
        )
    }

    // --- feature deps (every member listed in each feature file)
    val historyDeps: HistoryDeps by lazy {
        HistoryDeps(
            history = history, settings = settings, audio = audioStore, clock = clock,
            retry = { orchestrator.retry(it) }, repolish = { orchestrator.repolish(it) }, apps = apps,
            modelName = { id -> catalog.catalog.value.model(id)?.displayName },
        )
    }
    val dashboardDeps: DashboardDeps by lazy {
        DashboardDeps(metrics = metrics, settings = settings, stats = DbModule.stats(), clock = clock, serviceConnected = serviceConnected, apps = apps)
    }
    val modelsDeps: ModelsDeps by lazy {
        ModelsDeps(
            catalog = catalog, installed = installed, benchmarks = benchmarks, runner = benchmarkRunner, settings = settings,
            custom = customModels, device = deviceProfile.profile, health = unitHealth,
        )
    }
    val settingsDeps: SettingsDeps by lazy {
        SettingsDeps(
            settings = settings, prompts = prompts, renderer = renderer, catalog = catalog, history = history,
            serviceConnected = serviceConnected,
            tryCleanup = { sample, override, prefs -> orchestrator.tryCleanup(sample, override, prefs) },
            retention = retentionNow, apps = apps, permissions = permissions,
        )
    }
    val onboardingDeps: OnboardingDeps by lazy {
        OnboardingDeps(
            settings = settings, catalog = catalog, installed = installed, serviceConnected = serviceConnected,
            permissions = permissions, device = deviceProfile,
            practice = { orchestrator.startInApp(it) },
            bubble = orchestrator.bubble, levels = orchestrator.levels, bubbleInput = { orchestrator.onInput(it) },
            clock = clock, accessibilitySettingsOpened = { A11yStatus.get(context).onSentToAccessibilitySettings() },
        )
    }
    val shellDeps: ShellDeps by lazy { ShellDeps(dashboardDeps, historyDeps, modelsDeps, settingsDeps, practice = onboardingDeps) }

    /**
     * Called once from MurmurApp.onCreate in :main: retention (start-up run, midnight job, shortening watcher, ADR-072)
     * and the status notification (ADR-055).
     */
    fun start() {
        if (fake == null) retentionScheduler.start()
        StatusNotificationController(statusNotifier, serviceConnected, permissions, settings, installed, models, catalog).start(appScope)
    }

    /** Release builds before WP-14 is wired (WP-20, wave 3): the hub shows the runner as unavailable. */
    private object RunnerNotWired : BenchmarkRunner {
        override fun run(entry: ModelEntry, candidates: List<Candidate>): Flow<BenchProgress> =
            flowOf(BenchProgress.Failed(MurmurError.EngineRuntimeMissing("benchmark")))
        override fun stop() {}
    }
}
