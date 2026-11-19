package app.murmur.testing

import app.murmur.core.dictation.DictationConfig
import app.murmur.core.dictation.DictationDeps
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.prompt.ClasspathPromptAssets
import kotlinx.coroutines.CoroutineScope

/**
 * One object holding a full fake :main world for orchestrator and UI tests. Engines are wired to their hosts
 * (engines.asrHost.crash() fails the running ASR session), the resolver to [unitHealth] (a unit marked failed is
 * skipped next time), the capture to [audioStore] (history audio). Use [clock] = VirtualClock { testScheduler.currentTime }
 * in runTest so that timers and latencies follow virtual time.
 */
class FakeWorld(
    val clock: app.murmur.core.model.MurmurClock = FakeClock(),
    val settings: InMemorySettingsRepository = InMemorySettingsRepository(),
    val unitHealth: FakeUnitHealth = FakeUnitHealth(),
    val models: FakeModelResolver = FakeModelResolver(health = unitHealth),
    val engines: FakeEngines = FakeEngines(),
    val audioStore: InMemoryAudioStore = InMemoryAudioStore(),
    val audio: FakeAudioCapture = FakeAudioCapture(store = audioStore),
    val target: FakeTargetReader = FakeTargetReader(),
    val inserter: RecordingInserter = RecordingInserter(),
    val history: InMemoryHistoryRepository = InMemoryHistoryRepository(),
    val metrics: InMemoryMetricsRepository = InMemoryMetricsRepository(),
    val feedback: RecordingFeedback = RecordingFeedback(),
    val memory: FixedMemoryGate = FixedMemoryGate(),
    val navigator: RecordingNavigator = RecordingNavigator(),
    val permissions: FakePermissionMonitor = FakePermissionMonitor(),
    val guard: PassThroughGuard = PassThroughGuard(),
    val system: FakeSystemConditions = FakeSystemConditions(),
) {
    fun deps() = DictationDeps(
        clock = clock, settings = settings, models = models, engines = engines, audio = audio, target = target,
        inserter = inserter, context = SimpleContextAssembler(), renderer = FakePromptRenderer(), guard = guard,
        prompts = ClasspathPromptAssets(), history = history, metrics = metrics, audioStore = audioStore,
        feedback = feedback, memory = memory, unitHealth = unitHealth, navigator = navigator, permissions = permissions,
        deliveryMetrics = SimpleDeliveryMetrics(), system = system,
    )

    /** The orchestrator over this world; [errors] collects unexpected internal exceptions (tests assert it stays empty). */
    fun orchestrator(scope: CoroutineScope, config: DictationConfig = DictationConfig(), errors: MutableList<Throwable> = mutableListOf()) =
        DictationOrchestrator(deps(), scope, config, onInternalError = { errors += it })
}
