package app.murmur.core.dictation

import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurError

/**
 * What the dictation engine tells the bubble (DESIGN 5.3). While no dictation runs it is [Idle] and the
 * overlay's visibility policy decides Hidden / Rest / Ready; Dragging is overlay-local.
 */
sealed interface DictationBubble {
    data object Idle : DictationBubble
    data class Listening(val startedAtMs: Long, val hold: Boolean, val warn: Boolean) : DictationBubble
    data object Transcribing : DictationBubble
    data object Polishing : DictationBubble
    data class Inserted(val noCleanup: Boolean) : DictationBubble
    data class Copied(val entry: EntryId?) : DictationBubble
    data class Error(val reason: BubbleError, val action: BubbleAction?) : DictationBubble
}

/** DESIGN 5.5 error rows -> copy-deck keys (bubble_err_*). */
enum class BubbleError { NO_SPEECH, MIC_BUSY, NO_MODEL, LOAD_FAILED, TRANSCRIPTION_FAILED, GENERIC }

enum class BubbleAction { RETRY, OPEN_MODELS, OPEN_ENTRY }

/** The DESIGN 5.3/5.5 mapping, state -> bubble. Pure and total; unit-tested (BubbleMappingTest). */
object BubbleMapping {
    fun of(state: DictationState): DictationBubble = when (state) {
        DictationState.Idle -> DictationBubble.Idle
        is DictationState.Arming -> DictationBubble.Listening(state.pressedAtMs, state.mode == StartMode.HOLD, warn = false)
        is DictationState.Listening -> DictationBubble.Listening(state.startedAtMs, state.mode == StartMode.HOLD, state.warn)
        is DictationState.Finishing -> if (state.showTranscribing) DictationBubble.Transcribing
            else DictationBubble.Listening(state.stoppedAtMs, hold = false, warn = false)
        is DictationState.Polishing -> DictationBubble.Polishing
        is DictationState.Delivering -> if (state.cleaned) DictationBubble.Polishing else DictationBubble.Transcribing
        is DictationState.Done -> when (val o = state.outcome) {
            is DictationOutcome.Inserted -> DictationBubble.Inserted(o.noCleanup)
            is DictationOutcome.Copied -> DictationBubble.Copied(o.entry)
            is DictationOutcome.Failed -> errorBubble(o.error)
            is DictationOutcome.Discarded -> DictationBubble.Idle
        }
    }

    fun errorBubble(e: MurmurError): DictationBubble.Error = when (e) {
        MurmurError.NoSpeech -> DictationBubble.Error(BubbleError.NO_SPEECH, BubbleAction.RETRY)
        MurmurError.MicBusy, MurmurError.MicSilenced, is MurmurError.MicStartFailed -> DictationBubble.Error(BubbleError.MIC_BUSY, null)
        MurmurError.NoAsrModel -> DictationBubble.Error(BubbleError.NO_MODEL, BubbleAction.OPEN_MODELS)
        is MurmurError.EngineLoadFailed, is MurmurError.OutOfMemory, is MurmurError.EngineRuntimeMissing, is MurmurError.UnitUnavailable ->
            DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY)
        is MurmurError.AsrFailed, is MurmurError.ProcessDied, is MurmurError.Timeout -> DictationBubble.Error(BubbleError.TRANSCRIPTION_FAILED, BubbleAction.OPEN_ENTRY)
        else -> DictationBubble.Error(BubbleError.GENERIC, null)
    }
}
