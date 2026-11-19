package app.murmur.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.dictation.DictationOutcome
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bubble as a user meets it: an accessibility overlay of app.murmur, found by its TalkBack text (DESIGN 5.3,
 * WP-03's `bubble_ready_cd` = "Start dictation…" while Ready, `bubble_finish_cd` = "Finish dictation" on the Listening
 * pill), tapped with UiAutomator.
 */
object Bubble {
    private fun string(name: String): String {
        val res = E2e.context.resources
        val id = res.getIdentifier(name, "string", E2e.PKG)
        assertTrue("string resource $name is missing (WP-03 bubble copy)", id != 0)
        return res.getString(id)
    }

    val readyText: String by lazy { string("bubble_ready_cd") }
    val finishText: String by lazy { string("bubble_finish_cd") }
    val insertedText: String by lazy { string("bubble_inserted") }
    val polishingText: String by lazy { string("bubble_polishing") }
    /** The Error pill's action for a failure with the recording kept (WP-03 `bubble_action_retry`). */
    val retryText: String by lazy { string("bubble_action_retry") }

    private fun find(desc: String): UiObject2? = E2e.device.findObject(By.desc(desc))

    fun awaitReady(timeoutMs: Long = 10_000): UiObject2 =
        E2e.waitFor("bubble '$readyText' on screen; overlays now: ${overlayTexts()}", timeoutMs, pollMs = 200) { find(readyText) }

    /** Tap on Ready: starts a dictation. */
    fun tapToStart() {
        val b = awaitReady()
        E2e.note("tap bubble (start) at ${b.visibleBounds}")
        b.click()
    }

    /** Tap on the Listening pill: finishes the dictation. */
    fun tapToFinish(timeoutMs: Long = 10_000) {
        val pill = E2e.waitFor("Listening pill '$finishText'; overlays now: ${overlayTexts()}", timeoutMs, pollMs = 100) { find(finishText) }
        E2e.note("tap bubble (finish) at ${pill.visibleBounds}")
        pill.click()
    }

    /** Tap on the action button of a result pill ([retryText] on an Error pill). */
    fun tapAction(label: String, timeoutMs: Long = 5_000) {
        val action = E2e.waitFor("pill action '$label'; overlays now: ${overlayTexts()}", timeoutMs, pollMs = 50) {
            E2e.device.findObject(By.desc(label)) ?: E2e.device.findObject(By.text(label))
        }
        E2e.note("tap bubble action '$label' at ${action.visibleBounds}")
        action.click()
    }

    /** Overlay windows of app.murmur: the bubble pill and its cancel button (TYPE_ACCESSIBILITY_OVERLAY). */
    fun overlayWindows(): List<AccessibilityWindowInfo> = E2e.uiAutomation.windows.filter {
        it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.packageName == E2e.PKG
    }

    /** Screen bounds of the visible bubble overlay (the widest app.murmur overlay window), or null. */
    fun bounds(): Rect? = overlayWindows().map { w -> Rect().also { w.getBoundsInScreen(it) } }.maxByOrNull { it.width() }

    /** Texts and content descriptions in our overlays, for diagnostics and the on-screen state checks. */
    fun overlayTexts(): List<String> = overlayWindows().flatMap { w -> w.root?.let { collect(it) }.orEmpty() }

    private fun collect(n: AccessibilityNodeInfo): List<String> {
        val own = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).filter { it.isNotBlank() }
        return own + (0 until n.childCount).flatMap { i -> n.getChild(i)?.let { collect(it) }.orEmpty() }
    }

    /** True while a text containing [label] is drawn in our overlay. */
    fun shows(label: String): Boolean = overlayTexts().any { it.contains(label) }

    /** Which display edge the bubble is docked to (DESIGN 5.4), from its window bounds. */
    fun dockedRight(screenWidth: Int): Boolean {
        val b = checkNotNull(bounds()) { "no bubble overlay on screen" }
        return b.centerX() > screenWidth / 2
    }

    /**
     * Waits until the bubble sits on the [right] (or left) edge of the current display, after a size or rotation change
     * re-placed it (the overlay moves once the display change reached it). Fails with the bounds last seen.
     */
    fun awaitEdge(right: Boolean, screenWidth: () -> Int, timeoutMs: Long = 5_000) {
        var seen: Rect? = null
        E2e.waitUntil("bubble on the ${if (right) "right" else "left"} edge (last bounds $seen, width ${screenWidth()})", timeoutMs, pollMs = 100) {
            seen = bounds()
            seen?.let { (it.centerX() > screenWidth() / 2) == right } ?: false
        }
    }
}

/**
 * Records every DictationState and DictationBubble the orchestrator publishes (StateFlow collectors on an unconfined
 * dispatcher see each value the orchestrator sets from its serial loop, as long as it does not set two in the same
 * instant; working states last hundreds of ms here). Start before the dictation, [close] after.
 */
class DictationRecorder : AutoCloseable {
    data class Stamped<T>(val atMs: Long, val value: T)

    val states = CopyOnWriteArrayList<Stamped<DictationState>>()
    val bubbles = CopyOnWriteArrayList<Stamped<DictationBubble>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        val o = E2e.container.orchestrator
        scope.launch { o.state.collect { states += Stamped(SystemClock.uptimeMillis(), it) } }
        scope.launch { o.bubble.collect { bubbles += Stamped(SystemClock.uptimeMillis(), it) } }
    }

    val state: DictationState get() = E2e.container.orchestrator.state.value

    /** The engine readiness a working state carries (Arming, Listening, Finishing, Polishing). */
    fun enginesOf(s: DictationState): EngineStatus? = when (s) {
        is DictationState.Arming -> s.engines
        is DictationState.Listening -> s.engines
        is DictationState.Finishing -> s.engines
        is DictationState.Polishing -> s.engines
        else -> null
    }

    fun <T : DictationState> awaitState(what: String, timeoutMs: Long, match: (DictationState) -> T?): T =
        E2e.waitFor("dictation state $what (now ${state::class.simpleName})", timeoutMs, pollMs = 20) { match(state) }

    fun awaitListening(timeoutMs: Long = 10_000): DictationState.Listening =
        awaitState("Listening", timeoutMs) { it as? DictationState.Listening }

    fun awaitDone(timeoutMs: Long): DictationState.Done = awaitState("Done", timeoutMs) { it as? DictationState.Done }
        .also { E2e.note("done ${label(it)} after ${states.size} states") }

    /** The bubble kinds in order, consecutive duplicates collapsed (Listening timer updates are one Listening). */
    fun bubbleKinds(): List<String> = bubbles.map { it.value::class.simpleName ?: "?" }
        .fold(mutableListOf()) { acc, k -> if (acc.lastOrNull() != k) acc += k; acc }

    /** The state kinds in order, consecutive duplicates collapsed; Done carries its outcome and error code. */
    fun stateKinds(): List<String> = states.map { label(it.value) }
        .fold(mutableListOf()) { acc, k -> if (acc.lastOrNull() != k) acc += k; acc }

    private fun label(s: DictationState): String = when (s) {
        is DictationState.Done -> when (val o = s.outcome) {
            is DictationOutcome.Failed -> "Done(Failed:${o.error.code})"
            else -> "Done(${o::class.simpleName})"
        }
        else -> s::class.simpleName ?: "?"
    }

    /** Fails unless [expected] appears in this order (other kinds may come between). */
    fun assertBubbleOrder(vararg expected: String) = assertSubsequence("bubble", bubbleKinds(), expected.toList())

    fun assertStateOrder(vararg expected: String) = assertSubsequence("state", stateKinds(), expected.toList())

    private fun assertSubsequence(what: String, seen: List<String>, expected: List<String>) {
        var i = 0
        for (k in seen) if (i < expected.size && k == expected[i]) i++
        if (i < expected.size) fail("$what sequence $seen does not contain $expected in order")
    }

    override fun close() = scope.cancel()
}
