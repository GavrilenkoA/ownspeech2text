package app.murmur.core.dictation

import app.murmur.core.catalog.UnitHealthRepository
import app.murmur.core.context.ContextAssembler
import app.murmur.core.engine.Engines
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.Outcome
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.prompt.PromptAssets
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.store.AudioStore
import app.murmur.core.store.DeliveryMetrics
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the orchestrator needs; wired in :app (real) or from :testing (fakes, FakeWorld). */
data class DictationDeps(
    val clock: MurmurClock,
    val settings: SettingsRepository,
    val models: ModelResolver,
    val engines: Engines,
    val audio: AudioCapture,
    val target: TargetReader,
    val inserter: Inserter,
    val context: ContextAssembler,
    val renderer: PromptRenderer,
    val guard: OutputGuard,
    val prompts: PromptAssets,
    val history: HistoryRepository,
    val metrics: MetricsRepository,
    val audioStore: AudioStore,
    val feedback: Feedback,
    val memory: MemoryGate,
    val unitHealth: UnitHealthRepository,
    val navigator: AppNavigator,
    val permissions: PermissionMonitor,
    val deliveryMetrics: DeliveryMetrics,
)

/** The pure transition function (WP-11). Total over (state, event); stale sessions are ignored. */
object DictationMachine {
    fun reduce(state: DictationState, event: DictationEvent, config: DictationConfig): Transition =
        TODO("WP-11: implement the table in ARCHITECTURE.md section 5.2")
}

/**
 * Runs [DictationMachine] and executes its [Effect]s (ARCHITECTURE.md 5, threading 14). Lives in :main,
 * one instance per process (AppContainer). Single-threaded: every event is processed on [scope]'s
 * serial dispatcher (limitedParallelism(1)). Also the [EnginePreparer]: preparation shares the inference
 * processes and is cancelled by a press.
 */
class DictationOrchestrator(
    private val deps: DictationDeps,
    private val scope: CoroutineScope,
    private val config: DictationConfig = DictationConfig(),
) : EnginePreparer {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _bubble = MutableStateFlow<DictationBubble>(DictationBubble.Idle)
    val bubble: StateFlow<DictationBubble> = _bubble.asStateFlow()

    private val _levels = MutableStateFlow(FloatArray(7))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    fun onInput(input: UserInput) {
        TODO("WP-11: post DictationEvent.Input to the serial event loop")
    }

    /** Accessibility service disconnected (WP-02 via A11yServiceHost): ExternalStop(A11Y_OFF) for the running session. */
    fun onAccessibilityLost() {
        TODO("WP-11")
    }

    /** Onboarding practice (ob-ready): one dictation into [target], StartMode.IN_APP, nothing stored. */
    fun startInApp(target: InAppTarget) {
        TODO("WP-11")
    }

    /** History "Try again": re-transcribe the stored audio of a Failed entry. */
    suspend fun retry(entry: EntryId): Outcome<EntryId> = TODO("WP-11")

    /** History "Re-polish": re-run cleanup with the current prompt/tone/model; Original stays. */
    suspend fun repolish(entry: EntryId): Outcome<EntryId> = TODO("WP-11")

    /**
     * Settings "Try it" card (DESIGN 6.5): render [sample] with the given (unsaved) override and preferences through
     * the active LLM, guard, return the text. No context, nothing stored. Err when no LLM is installed.
     */
    suspend fun tryCleanup(sample: String, systemOverride: String?, preferences: String?): Outcome<String> = TODO("WP-11")

    override suspend fun prepareArtifact(artifact: ArtifactId, onProgress: (Float) -> Unit): Outcome<PrepareReport> = TODO("WP-11")

    override fun invalidatePrefix() {
        TODO("WP-11")
    }
}
