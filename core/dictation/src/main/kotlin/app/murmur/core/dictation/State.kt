package app.murmur.core.dictation

import app.murmur.core.context.CtxV2
import app.murmur.core.engine.Transcript
import app.murmur.core.model.EntryId
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.SessionId

/**
 * Dictation state machine (ARCHITECTURE.md section 5). Pure types; [DictationMachine.reduce] is the only
 * place that decides transitions. One session at a time (I1); every event carries its [SessionId] and is
 * dropped when stale (I2).
 */
sealed interface DictationState {
    val session: SessionId?

    data object Idle : DictationState { override val session: SessionId? = null }

    /** Bubble pressed; mic FGS + AudioRecord starting; engines binding in parallel. Bubble shows Listening at once. */
    data class Arming(override val session: SessionId, val mode: StartMode, val pressedAtMs: Long) : DictationState

    /** Audio flows to the ASR session (buffered until the model is loaded). */
    data class Listening(override val session: SessionId, val mode: StartMode, val startedAtMs: Long, val warn: Boolean = false) : DictationState

    /** Stop received; waiting for the ASR tail. Bubble keeps Listening visuals for 300 ms, then Transcribing. */
    data class Finishing(override val session: SessionId, val stoppedAtMs: Long, val showTranscribing: Boolean = false) : DictationState

    /** LLM cleanup running (skipped when cleanup = None, precheck says skip, or the field is a password). */
    data class Polishing(override val session: SessionId, val stoppedAtMs: Long, val transcript: Transcript) : DictationState

    /** Clipboard write + insertion ladder + history/metrics write. */
    data class Delivering(override val session: SessionId, val stoppedAtMs: Long, val text: String, val cleaned: Boolean) : DictationState

    /** Result pill on screen; dismiss timer running. */
    data class Done(override val session: SessionId, val outcome: DictationOutcome) : DictationState
}

enum class StartMode { TAP, HOLD, NOTIFICATION }

sealed interface DictationOutcome {
    /** [noCleanup] = "Inserted, no cleanup" (cleanup failed / timed out / low memory). */
    data class Inserted(val entry: EntryId?, val noCleanup: Boolean, val skipReason: String? = null) : DictationOutcome
    data class Copied(val entry: EntryId?) : DictationOutcome
    data class Failed(val error: MurmurError, val entry: EntryId?) : DictationOutcome
    /** User cancelled (x, slide-off) or a password field took focus before delivery: nothing kept. */
    data class Discarded(val reason: String) : DictationOutcome
}

/** Bubble and notification inputs (DESIGN 5.3). */
sealed interface UserInput {
    /** Tap on Ready starts; tap anywhere on the Listening pill finishes; ignored while working. */
    data object Tap : UserInput
    /** Press held >= 250 ms on Ready (hold-to-talk, if enabled). */
    data object HoldStart : UserInput
    data object HoldRelease : UserInput
    /** Finger slid > 64 dp off the pill while holding: discard. */
    data object HoldSlideOff : UserInput
    /** The x button above the Listening pill. */
    data object Cancel : UserInput
    /** Notification action "Start dictation" (and the Quick Settings tile later). */
    data object NotificationStart : UserInput
    /** Tap on the Copied / Error pill. */
    data object ResultTap : UserInput
}

enum class Timer { MIC_START, WARN, MAX_DURATION, TRANSCRIBING_LABEL, ASR_FINAL, LLM, DELIVERY, RESULT_DISMISS }

/** Everything the reducer reacts to. Completion events come from [Effect] execution in the orchestrator. */
sealed interface DictationEvent {
    data class Input(val input: UserInput, val atMs: Long) : DictationEvent
    data class MicStarted(val session: SessionId, val atMs: Long) : DictationEvent
    data class MicFailed(val session: SessionId, val error: MurmurError) : DictationEvent
    data class EnginesReady(val session: SessionId, val asrReady: Boolean, val llmReady: Boolean, val llmError: MurmurError?) : DictationEvent
    data class AsrLoadFailed(val session: SessionId, val error: MurmurError) : DictationEvent
    /** ctx v2 assembled (screen may be null); it is kept in memory for the cleanup call only. */
    data class ContextReady(val session: SessionId, val ctx: CtxV2?) : DictationEvent
    data class TranscriptReady(val session: SessionId, val transcript: Transcript) : DictationEvent
    data class AsrFailed(val session: SessionId, val error: MurmurError) : DictationEvent
    data class CleanupDone(val session: SessionId, val text: String, val cleaned: Boolean, val skipReason: String?) : DictationEvent
    data class Delivered(val session: SessionId, val result: DeliveryResult) : DictationEvent
    data class TimerFired(val session: SessionId, val timer: Timer) : DictationEvent
    data class ProcessDied(val session: SessionId, val process: InferProcess) : DictationEvent
    /** Service disconnected, call started, user disabled accessibility, screen locked for > max duration... */
    data class ExternalStop(val reason: String) : DictationEvent
}

/** Result of [Effect.Deliver]. */
sealed interface DeliveryResult {
    data class Inserted(val entry: EntryId?, val method: app.murmur.core.store.InsertMethod) : DeliveryResult
    data class CopiedOnly(val entry: EntryId?, val why: MurmurError) : DeliveryResult
    /** Focus moved to a password field before delivery: nothing inserted, copied or stored. */
    data object DiscardedPassword : DeliveryResult
    data class NothingToInsert(val entry: EntryId?) : DeliveryResult
}

/** Side effects the orchestrator executes; their completions come back as [DictationEvent]s. */
sealed interface Effect {
    data class StartCapture(val session: SessionId) : Effect
    data class StopCapture(val session: SessionId) : Effect
    data class DiscardCapture(val session: SessionId) : Effect
    /** D5: bind :asr and load ASR (+VAD, OCR); bind :llm and load the LLM + prefix in parallel (if cleanup on and memory allows). */
    data class PrepareEngines(val session: SessionId) : Effect
    /** D11: screenshot ~0.6 s after start -> OCR -> ctx v2 -> ContextReady. */
    data class CaptureContext(val session: SessionId, val delayMs: Long) : Effect
    data class FinishAsr(val session: SessionId) : Effect
    /** precheck -> render (ctx v2 + transcript) -> generate -> guard -> CleanupDone. */
    data class RunCleanup(val session: SessionId, val transcript: Transcript) : Effect
    /** Clipboard + insertion ladder + history + metrics -> Delivered. */
    data class Deliver(val session: SessionId, val text: String, val raw: String?, val cleaned: Boolean, val skipReason: String?) : Effect
    data class RecordFailure(val session: SessionId, val error: MurmurError, val keepAudio: Boolean) : Effect
    data class StartTimer(val session: SessionId, val timer: Timer, val ms: Long) : Effect
    data class CancelTimer(val timer: Timer) : Effect
    data class Haptic(val kind: HapticKind) : Effect
    /** Keep processes warm for the idle timeout, then unload + exit (D5). */
    data class ScheduleIdleUnload(val ms: Long) : Effect
    data class CancelEngines(val session: SessionId) : Effect
}

enum class HapticKind { CONFIRM, CLOCK_TICK, REJECT }

data class Transition(val state: DictationState, val effects: List<Effect> = emptyList())
