package app.murmur.core.dictation

import app.murmur.core.context.ContextAssembler
import app.murmur.core.engine.Engines
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.Outcome
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.prompt.PromptAssets
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.store.AudioStore
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the orchestrator needs; wired in :app (real) or from :testing (fakes). */
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
)

/** The pure transition function (WP-11). Total over (state, event); stale sessions are ignored. */
object DictationMachine {
    fun reduce(state: DictationState, event: DictationEvent, config: DictationConfig): Transition =
        TODO("WP-11: implement the table in ARCHITECTURE.md section 5.2")
}

/**
 * Runs [DictationMachine] and executes its [Effect]s (ARCHITECTURE.md 5, threading 12). Lives in :main,
 * one instance per process (AppContainer). Single-threaded: every event is processed on [scope]'s
 * serial dispatcher (limitedParallelism(1)).
 */
class DictationOrchestrator(
    private val deps: DictationDeps,
    private val scope: CoroutineScope,
    private val config: DictationConfig = DictationConfig(),
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _bubble = MutableStateFlow<DictationBubble>(DictationBubble.Idle)
    val bubble: StateFlow<DictationBubble> = _bubble.asStateFlow()

    private val _levels = MutableStateFlow(FloatArray(7))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    fun onInput(input: UserInput) {
        TODO("WP-11: post DictationEvent.Input to the serial event loop")
    }

    /** History "Try again": re-transcribe the stored audio of a Failed entry. */
    suspend fun retry(entry: EntryId): Outcome<EntryId> = TODO("WP-11")

    /** History "Re-polish": re-run cleanup with the current prompt/tone/model; Original stays. */
    suspend fun repolish(entry: EntryId): Outcome<EntryId> = TODO("WP-11")
}
