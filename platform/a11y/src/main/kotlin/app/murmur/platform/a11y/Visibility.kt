package app.murmur.platform.a11y

import app.murmur.core.context.BuiltInDenylist
import app.murmur.core.context.FieldKinds
import app.murmur.core.store.BubbleVisibility
import app.murmur.core.store.Settings
import app.murmur.platform.overlay.BubblePolicy
import app.murmur.platform.overlay.VisibilityInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Focus as read on a11y-io from the window list, the focused node and the accessibility IME. */
data class FocusState(
    val pkg: String?,
    val displayId: Int,
    val editableFocused: Boolean,
    val inputType: Int,
    val isPassword: Boolean,
    /** Identity of the focused field (node + window); a new key restarts the tuck-in idle timer. */
    val fieldKey: Long?,
) {
    companion object {
        val NONE = FocusState(pkg = null, displayId = 0, editableFocused = false, inputType = 0, isPassword = false, fieldKey = null)
    }
}

/** The Settings members the visibility policy reads (DESIGN 5.2, 6.5). */
data class VisibilitySettings(
    val showAlways: Boolean = false,
    val tuckIn: Boolean = true,
    val hiddenInPackages: Set<String> = emptySet(),
    val hiddenUntilEpochMs: Long? = null,
) {
    companion object {
        fun of(s: Settings) = VisibilitySettings(
            showAlways = s.bubbleVisibility == BubbleVisibility.ALWAYS,
            tuckIn = s.tuckIn,
            hiddenInPackages = s.hiddenInPackages,
            hiddenUntilEpochMs = s.hiddenUntilEpochMs,
        )
    }
}

object VisibilityRules {
    /** DESIGN 5.2: "Keyboard closes while Ready or Rest -> Hidden after 300 ms (debounced to avoid flicker between fields)". */
    const val BLUR_DEBOUNCE_MS = 300L

    fun build(focus: FocusState, settings: VisibilitySettings, ownUi: Set<String>, nowMs: Long, idleSinceMs: Long?, talkBackOn: Boolean) =
        VisibilityInput(
            editableFocused = focus.editableFocused,
            bubbleAllowedForField = !focus.isPassword && FieldKinds.bubbleAllowed(focus.inputType),
            appHidden = focus.pkg != null && appHidden(focus.pkg, settings.hiddenInPackages, ownUi),
            hiddenUntilMs = settings.hiddenUntilEpochMs,
            nowMs = nowMs,
            showAlways = settings.showAlways,
            idleSinceMs = idleSinceMs,
            tuckIn = settings.tuckIn,
            talkBackOn = talkBackOn,
        )

    /** Built-in denylist ∪ the user's "Hidden in" ∪ Murmur's own UI (the practice field uses an in-app bubble). */
    fun appHidden(pkg: String, hiddenIn: Set<String>, ownUi: Set<String>): Boolean =
        pkg in ownUi || pkg in BuiltInDenylist.packages || pkg in hiddenIn

    /** A blur (editable -> not editable) waits [BLUR_DEBOUNCE_MS]; everything else, including a password focus, is immediate. */
    fun delayFor(previous: FocusState?, next: FocusState): Long =
        if (previous?.editableFocused == true && !next.editableFocused) BLUR_DEBOUNCE_MS else 0L

    /** Bubbles on other displays than the focused one see "no field" (they can still show a Rest tab with "Always"). */
    fun forDisplay(input: VisibilityInput, focusDisplayId: Int, displayId: Int): VisibilityInput =
        if (displayId == focusDisplayId) input else input.copy(editableFocused = false, bubbleAllowedForField = true)

    /** The next instant at which the merged visual can change without any new event (tuck-in, end of "hide 1 h"). */
    fun nextDeadline(input: VisibilityInput): Long? {
        val tuck = input.idleSinceMs?.takeIf { input.tuckIn && !input.talkBackOn && input.editableFocused }
            ?.let { it + BubblePolicy.TUCK_AFTER_MS }?.takeIf { it > input.nowMs }
        val unhide = input.hiddenUntilMs?.takeIf { it > input.nowMs }
        return listOfNotNull(tuck, unhide).minOrNull()
    }
}

/**
 * Turns focus reads, settings and TalkBack state into the [VisibilityInput] stream for the bubble (DESIGN 5.2).
 * Runs on one serial context (the service's main scope); [now] is epoch ms (hiddenUntil is epoch ms).
 * [emit] receives the focus display id and the input; it is called again at each [VisibilityRules.nextDeadline].
 */
class FocusTracker(
    private val scope: CoroutineScope,
    /** Murmur's own UI packages ([A11yServiceHost.ownUiPackages]). */
    private val ownUi: Set<String>,
    private val now: () -> Long,
    private val emit: (displayId: Int, input: VisibilityInput) -> Unit,
) {
    private var focus: FocusState = FocusState.NONE
    private var shown: FocusState? = null
    private var settings = VisibilitySettings()
    private var talkBack = false
    private var idleSince: Long? = null
    private var pendingBlur: Job? = null
    private var deadline: Job? = null

    val current: FocusState get() = focus

    fun onFocus(next: FocusState) {
        focus = next
        val wait = VisibilityRules.delayFor(shown, next)
        if (wait > 0L && pendingBlur?.isActive == true) return // a blur is already counting down; it applies the latest focus
        pendingBlur?.cancel(); pendingBlur = null
        if (wait == 0L) apply(next) else pendingBlur = scope.launch { delay(wait); apply(focus) }
    }

    fun onSettings(s: VisibilitySettings) { settings = s; republish() }
    fun onTalkBack(on: Boolean) { talkBack = on; republish() }

    /** A dictation ended (bubble back to Idle): Ready again for a full 4 s before tucking in. */
    fun onDictationIdle() { if (shown?.editableFocused == true) idleSince = now(); republish() }

    /** Re-send the shown state (display added or changed). */
    fun republish() { shown?.let { publish(it) } }

    private fun apply(state: FocusState) {
        pendingBlur = null
        val prev = shown
        if (state == prev) return
        if (state.editableFocused && (prev?.editableFocused != true || prev.fieldKey != state.fieldKey)) idleSince = now()
        if (!state.editableFocused) idleSince = null
        shown = state
        publish(state)
    }

    private fun publish(state: FocusState) {
        val input = VisibilityRules.build(state, settings, ownUi, now(), idleSince, talkBack)
        emit(state.displayId, input)
        deadline?.cancel()
        deadline = VisibilityRules.nextDeadline(input)?.let { at ->
            scope.launch { delay(at - input.nowMs); shown?.let { publish(it) } }
        }
    }
}
