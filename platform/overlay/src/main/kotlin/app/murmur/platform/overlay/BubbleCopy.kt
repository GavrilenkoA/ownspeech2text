package app.murmur.platform.overlay

import androidx.annotation.StringRes
import app.murmur.core.dictation.BubbleAction
import app.murmur.core.dictation.BubbleError
import app.murmur.core.dictation.DictationBubble

/** How TalkBack hears a state change (DESIGN 8: polite live region; errors assertive). */
enum class Live { OFF, POLITE, ASSERTIVE }

/**
 * The words of one visual: the visible [label] (null when the visual has none: circle, tab, the Listening timer),
 * the [description] TalkBack reads for the bubble, an optional [state] description, the live-region mode and the
 * Error action's label. DESIGN 5.3 "TalkBack text" column and DESIGN 7 copy deck.
 */
data class BubbleText(
    @StringRes val label: Int?,
    @StringRes val description: Int,
    @StringRes val state: Int?,
    val live: Live,
    @StringRes val action: Int?,
)

object BubbleCopy {
    fun of(visual: BubbleVisual): BubbleText? = when (visual) {
        BubbleVisual.Hidden -> null
        BubbleVisual.Rest -> BubbleText(null, R.string.bubble_rest_cd, null, Live.OFF, null)
        BubbleVisual.Ready -> BubbleText(null, R.string.bubble_ready_cd, null, Live.OFF, null)
        is BubbleVisual.Dragging -> BubbleText(null, R.string.bubble_drag_cd, null, Live.OFF, null)
        is BubbleVisual.Active -> of(visual.state)
    }

    fun of(state: DictationBubble): BubbleText? = when (state) {
        DictationBubble.Idle -> null
        is DictationBubble.Listening ->
            if (state.hold) BubbleText(R.string.bubble_hold, R.string.bubble_hold_live, null, Live.POLITE, null)
            // The pill is the finish button; "Listening" is its live state.
            else BubbleText(null, R.string.bubble_finish_cd, R.string.bubble_listening, Live.POLITE, null)
        DictationBubble.Transcribing -> same(R.string.bubble_transcribing)
        DictationBubble.Polishing -> same(R.string.bubble_polishing)
        is DictationBubble.Inserted -> same(if (state.noCleanup) R.string.bubble_inserted_nocleanup else R.string.bubble_inserted)
        is DictationBubble.Copied -> BubbleText(R.string.bubble_copied, R.string.bubble_copied_live, null, Live.POLITE, null)
        is DictationBubble.Error -> {
            val reason = errorLabel(state.reason)
            BubbleText(reason, reason, null, Live.ASSERTIVE, state.action?.let(::actionLabel))
        }
    }

    private fun same(@StringRes id: Int) = BubbleText(id, id, null, Live.POLITE, null)

    @StringRes
    fun errorLabel(e: BubbleError): Int = when (e) {
        BubbleError.NO_SPEECH -> R.string.bubble_err_nospeech
        BubbleError.MIC_BUSY -> R.string.bubble_err_micbusy
        BubbleError.NO_MODEL -> R.string.bubble_err_nomodel
        BubbleError.LOAD_FAILED -> R.string.bubble_err_load
        BubbleError.TRANSCRIPTION_FAILED -> R.string.bubble_err_transcription
        BubbleError.GENERIC -> R.string.bubble_err_generic
    }

    /** DESIGN 5.5: Retry, or Open (Models / the History entry). */
    @StringRes
    fun actionLabel(a: BubbleAction): Int = when (a) {
        BubbleAction.RETRY -> R.string.bubble_action_retry
        BubbleAction.OPEN_MODELS, BubbleAction.OPEN_ENTRY -> R.string.bubble_action_open
    }
}
