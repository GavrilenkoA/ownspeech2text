package app.murmur.testing

import app.murmur.core.dictation.DictationDeps
import app.murmur.core.prompt.ClasspathPromptAssets

/**
 * One object holding a full fake :main world for orchestrator and UI tests. Engines are wired to their hosts
 * (engines.asrHost.crash() fails the running ASR session). Use [clock] = VirtualClock { testScheduler.currentTime }
 * in runTest so that timers and latencies follow virtual time.
 */
class FakeWorld(
    val clock: app.murmur.core.model.MurmurClock = FakeClock(),
    val settings: InMemorySettingsRepository = InMemorySettingsRepository(),
    val models: FakeModelResolver = FakeModelResolver(),
    val engines: FakeEngines = FakeEngines(),
    val audio: FakeAudioCapture = FakeAudioCapture(),
    val target: FakeTargetReader = FakeTargetReader(),
    val inserter: RecordingInserter = RecordingInserter(),
    val history: InMemoryHistoryRepository = InMemoryHistoryRepository(),
    val metrics: InMemoryMetricsRepository = InMemoryMetricsRepository(),
    val audioStore: InMemoryAudioStore = InMemoryAudioStore(),
    val feedback: RecordingFeedback = RecordingFeedback(),
    val memory: FixedMemoryGate = FixedMemoryGate(),
    val unitHealth: FakeUnitHealth = FakeUnitHealth(),
    val navigator: RecordingNavigator = RecordingNavigator(),
    val permissions: FakePermissionMonitor = FakePermissionMonitor(),
    val guard: PassThroughGuard = PassThroughGuard(),
) {
    fun deps() = DictationDeps(
        clock = clock, settings = settings, models = models, engines = engines, audio = audio, target = target,
        inserter = inserter, context = SimpleContextAssembler(), renderer = FakePromptRenderer(), guard = guard,
        prompts = ClasspathPromptAssets(), history = history, metrics = metrics, audioStore = audioStore,
        feedback = feedback, memory = memory, unitHealth = unitHealth, navigator = navigator, permissions = permissions,
        deliveryMetrics = SimpleDeliveryMetrics(),
    )
}
