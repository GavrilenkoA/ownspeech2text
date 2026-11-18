package app.murmur.testing

import app.murmur.core.dictation.DictationDeps
import app.murmur.core.prompt.ClasspathPromptAssets

/** One object holding a full fake :main world for orchestrator and UI tests. */
class FakeWorld(
    val clock: FakeClock = FakeClock(),
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
) {
    fun deps() = DictationDeps(
        clock = clock, settings = settings, models = models, engines = engines, audio = audio, target = target,
        inserter = inserter, context = SimpleContextAssembler(), renderer = FakePromptRenderer(), guard = PassThroughGuard(),
        prompts = ClasspathPromptAssets(), history = history, metrics = metrics, audioStore = audioStore,
        feedback = feedback, memory = memory,
    )
}
