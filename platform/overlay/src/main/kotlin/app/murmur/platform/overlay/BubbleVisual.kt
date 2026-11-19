package app.murmur.platform.overlay

import app.murmur.core.dictation.DictationBubble

/**
 * The 10 visual states of DESIGN 5.3 = dictation state (from the orchestrator) + visibility policy +
 * overlay-local drag. Pure merge function; the Compose bubble renders exactly one of these.
 */
sealed interface BubbleVisual {
    data object Hidden : BubbleVisual
    data object Rest : BubbleVisual
    data object Ready : BubbleVisual
    data class Active(val state: DictationBubble) : BubbleVisual
    data class Dragging(val overHideTarget: Boolean) : BubbleVisual
}

/**
 * DESIGN 5.2 visibility inputs (computed by :platform:a11y from focus events and settings), plus the geometry the
 * placement of DESIGN 5.4 needs. Geometry is in screen pixels of the display the input is for; null = unknown (the
 * bubble then keeps its stored or current position).
 */
data class VisibilityInput(
    val editableFocused: Boolean,
    val bubbleAllowedForField: Boolean,   // FieldKinds.bubbleAllowed (no password/number/phone)
    val appHidden: Boolean,               // "Hidden in" list or built-in denylist or Murmur's own UI
    val hiddenUntilMs: Long?,             // drag-to-hide / notification "Hide 1 h"
    val nowMs: Long,
    val showAlways: Boolean,              // Settings "Show the bubble: Always"
    val idleSinceMs: Long?,               // Ready without interaction since
    val tuckIn: Boolean,
    val talkBackOn: Boolean,              // DESIGN 8: no tuck-in under TalkBack
    /** Top of the focused field's getBoundsInScreen (px). */
    val fieldTop: Int? = null,
    /** Top of the TYPE_INPUT_METHOD window from the accessibility window list (px); null = no keyboard shown. */
    val imeTop: Int? = null,
) {
    companion object {
        /** Before the service has published anything: nothing focused, nothing hidden. */
        fun nothingFocused(nowMs: Long) = VisibilityInput(
            editableFocused = false, bubbleAllowedForField = true, appHidden = false, hiddenUntilMs = null, nowMs = nowMs,
            showAlways = false, idleSinceMs = null, tuckIn = true, talkBackOn = false,
        )
    }
}

object BubblePolicy {
    const val TUCK_AFTER_MS = 4_000L

    /**
     * [restOpenedAtMs]: when the user last opened (tapped) or used the "Show the bubble: Always" tab while no field is
     * focused (overlay-local, kept by [BubbleController]). DESIGN 5.3 Rest "tap -> Ready": that tab opens to Ready and
     * folds back after [TUCK_AFTER_MS] without interaction, whatever the tuck-in setting (the tab is the no-field
     * resting form, not a tuck); under TalkBack it stays Ready (DESIGN 8: no tuck-in).
     */
    fun merge(
        dictation: DictationBubble,
        v: VisibilityInput,
        dragging: Boolean,
        overHideTarget: Boolean,
        restOpenedAtMs: Long? = null,
    ): BubbleVisual {
        // DESIGN 5.3: a working or result pill can only be moved, never dropped on the hide target.
        if (dictation !is DictationBubble.Idle) return if (dragging) BubbleVisual.Dragging(overHideTarget = false) else BubbleVisual.Active(dictation)
        if (dragging) return BubbleVisual.Dragging(overHideTarget)
        if (v.hiddenUntilMs != null && v.nowMs < v.hiddenUntilMs) return BubbleVisual.Hidden
        if (v.appHidden) return BubbleVisual.Hidden
        // DESIGN 5.2: password, number and phone fields hide the bubble "always", even with "Show the bubble: Always".
        if (v.editableFocused && !v.bubbleAllowedForField) return BubbleVisual.Hidden
        val show = (v.editableFocused && v.bubbleAllowedForField) || v.showAlways
        if (!show) return BubbleVisual.Hidden
        if (!v.editableFocused && v.showAlways) return if (alwaysTabOpen(v, restOpenedAtMs)) BubbleVisual.Ready else BubbleVisual.Rest
        val idle = v.idleSinceMs
        return if (v.tuckIn && !v.talkBackOn && idle != null && v.nowMs - idle >= TUCK_AFTER_MS) BubbleVisual.Rest else BubbleVisual.Ready
    }

    private fun alwaysTabOpen(v: VisibilityInput, openedAt: Long?): Boolean =
        openedAt != null && (v.talkBackOn || v.nowMs - openedAt < TUCK_AFTER_MS)

    /**
     * The next instant at which [merge] can change without a new event: the end of the tuck-in wait while Ready (or of
     * the opened "Always" tab), or the end of "hide for 1 hour" while hidden by it. Null = nothing pending.
     */
    fun nextDeadline(dictation: DictationBubble, v: VisibilityInput, restOpenedAtMs: Long? = null): Long? {
        if (dictation !is DictationBubble.Idle) return null
        v.hiddenUntilMs?.takeIf { it > v.nowMs }?.let { return it }
        if (merge(dictation, v, dragging = false, overHideTarget = false, restOpenedAtMs = restOpenedAtMs) != BubbleVisual.Ready) return null
        if (!v.editableFocused) return restOpenedAtMs?.let { it + TUCK_AFTER_MS }?.takeIf { it > v.nowMs && !v.talkBackOn }
        return v.idleSinceMs?.let { it + TUCK_AFTER_MS }?.takeIf { it > v.nowMs && v.tuckIn && !v.talkBackOn }
    }
}
