package app.murmur

import android.app.ActivityManager
import android.content.Context
import app.murmur.core.benchmark.BenchmarkRunner
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.context.DefaultContextAssembler
import app.murmur.core.dictation.DictationDeps
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.dictation.Feedback
import app.murmur.core.dictation.HapticKind
import app.murmur.core.dictation.MemoryGate
import app.murmur.core.engine.Engines
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.SystemMurmurClock
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.prompt.GuardPyPort
import app.murmur.core.prompt.RenderPyPort
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRepository
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
import app.murmur.data.models.DefaultInstalledModelsRepository
import app.murmur.data.models.DefaultModelResolver
import app.murmur.data.models.ModelsModule
import app.murmur.infer.api.BinderEngines
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
 * Integration owner: WP-20. Other packages change their own module, not this file.
 */
class AppContainer(private val context: Context) {
    val clock: MurmurClock = SystemMurmurClock
    /** Application scope; the orchestrator gets its own serial view of it (threading policy, ARCHITECTURE.md 12). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serviceConnected = MutableStateFlow(false)
    /** Cached probe of both inference processes (ARCHITECTURE.md 8.4); null until the first probe. */
    val deviceProfile = MutableStateFlow<DeviceProfile?>(null)

    private val debug = DebugEngines(context)

    // --- data
    private val db by lazy { DbModule.database(context) }
    val audioStore: AudioStore by lazy { OpusAudioStore(context) }
    val history: HistoryRepository by lazy { RoomHistoryRepository(db, audioStore) }
    val metrics: MetricsRepository by lazy { RoomMetricsRepository(db) }
    val benchmarks: BenchmarkRepository by lazy { RoomBenchmarkRepository(db) }
    val settings: SettingsRepository by lazy { DataStoreSettingsRepository(context) }

    // --- models
    val catalog: CatalogRepository by lazy { DefaultCatalogRepository() }
    val installed: InstalledModelsRepository by lazy { DefaultInstalledModelsRepository(context, ModelsModule.storage(context)) }
    private val resolver by lazy {
        DefaultModelResolver(catalog, installed) { deviceProfile.value ?: error("device profile not probed yet") }
    }

    // --- engines (real binder clients, or :testing fakes in debug fake mode)
    val engines: Engines by lazy { debug.fakeEnginesOrNull() ?: BinderEngines(context) }
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
            ),
            appScope,
        )
    }

    private val feedback = Feedback { _: HapticKind -> /* WP-03: View.performHapticFeedback per Settings.feedback */ }

    private val memory = MemoryGate {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    }
}
