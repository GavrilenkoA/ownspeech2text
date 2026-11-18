package app.murmur.platform.a11y

import app.murmur.core.context.FieldKinds
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.store.BubbleVisibility
import app.murmur.core.store.Settings
import app.murmur.platform.overlay.BubblePolicy
import app.murmur.platform.overlay.BubbleVisual
import app.murmur.platform.overlay.VisibilityInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibilityTest {
    private val own = "app.murmur"
    private val text = FieldKinds.TYPE_CLASS_TEXT
    private val password = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_PASSWORD

    private fun focus(pkg: String = "com.other", editable: Boolean = true, inputType: Int = text, pw: Boolean = false, key: Long? = 1, display: Int = 0) =
        FocusState(pkg, display, editable, inputType, pw, if (editable) key else null)

    private fun visual(v: VisibilityInput) = BubblePolicy.merge(DictationBubble.Idle, v, dragging = false, overHideTarget = false)

    @Test
    fun buildMapsFieldAppAndSettings() {
        val s = VisibilitySettings(showAlways = true, tuckIn = false, hiddenInPackages = setOf("com.bank"), hiddenUntilEpochMs = 99)
        val v = VisibilityRules.build(focus(), s, own, nowMs = 50, idleSinceMs = 10, talkBackOn = true)
        assertEquals(VisibilityInput(editableFocused = true, bubbleAllowedForField = true, appHidden = false, hiddenUntilMs = 99,
            nowMs = 50, showAlways = true, idleSinceMs = 10, tuckIn = false, talkBackOn = true), v)
        assertFalse(VisibilityRules.build(focus(inputType = password), s, own, 0, null, false).bubbleAllowedForField)
        assertFalse(VisibilityRules.build(focus(pw = true), s, own, 0, null, false).bubbleAllowedForField) // node.isPassword only
        assertFalse(VisibilityRules.build(focus(inputType = FieldKinds.TYPE_CLASS_PHONE), s, own, 0, null, false).bubbleAllowedForField)
        assertTrue(VisibilityRules.build(focus(pkg = "com.bank"), s, own, 0, null, false).appHidden)
        assertTrue(VisibilityRules.build(focus(pkg = "com.android.systemui"), s, own, 0, null, false).appHidden)
        assertTrue(VisibilityRules.build(focus(pkg = own), s, own, 0, null, false).appHidden)
        assertFalse(VisibilityRules.build(FocusState.NONE, s, own, 0, null, false).appHidden)
    }

    @Test
    fun settingsMapping() {
        val v = VisibilitySettings.of(Settings(bubbleVisibility = BubbleVisibility.ALWAYS, tuckIn = false, hiddenInPackages = setOf("a"), hiddenUntilEpochMs = 5))
        assertEquals(VisibilitySettings(showAlways = true, tuckIn = false, hiddenInPackages = setOf("a"), hiddenUntilEpochMs = 5), v)
        assertFalse(VisibilitySettings.of(Settings()).showAlways)
    }

    @Test
    fun onlyABlurIsDebounced() {
        assertEquals(300L, VisibilityRules.delayFor(focus(), focus(editable = false)))
        assertEquals(0L, VisibilityRules.delayFor(focus(), focus(inputType = password)))
        assertEquals(0L, VisibilityRules.delayFor(focus(editable = false), focus()))
        assertEquals(0L, VisibilityRules.delayFor(null, focus(editable = false)))
    }

    @Test
    fun otherDisplaysSeeNoField() {
        val v = VisibilityRules.build(focus(inputType = password), VisibilitySettings(), own, 0, null, false)
        assertEquals(v, VisibilityRules.forDisplay(v, focusDisplayId = 0, displayId = 0))
        val other = VisibilityRules.forDisplay(v, focusDisplayId = 0, displayId = 1)
        assertFalse(other.editableFocused); assertTrue(other.bubbleAllowedForField)
    }

    @Test
    fun deadlinesForTuckInAndUnhide() {
        val v = VisibilityRules.build(focus(), VisibilitySettings(hiddenUntilEpochMs = 10_000), own, nowMs = 1_000, idleSinceMs = 1_000, talkBackOn = false)
        assertEquals(1_000 + BubblePolicy.TUCK_AFTER_MS, VisibilityRules.nextDeadline(v))
        assertEquals(10_000L, VisibilityRules.nextDeadline(v.copy(tuckIn = false)))
        assertEquals(10_000L, VisibilityRules.nextDeadline(v.copy(talkBackOn = true))) // DESIGN 8: no tuck-in under TalkBack
        assertNull(VisibilityRules.nextDeadline(v.copy(tuckIn = false, hiddenUntilMs = null)))
    }

    private class Recorder {
        val events = mutableListOf<Pair<Long, VisibilityInput>>()
    }

    private fun TestScope.tracker(rec: Recorder) =
        FocusTracker(backgroundScope, own, now = { testScheduler.currentTime }) { _, v -> rec.events += testScheduler.currentTime to v }

    @Test
    fun passwordFocusHidesImmediatelyEvenWithShowAlways() = runTest {
        val rec = Recorder()
        val t = tracker(rec)
        t.onSettings(VisibilitySettings(showAlways = true))
        t.onFocus(focus())
        runCurrent()
        assertEquals(BubbleVisual.Ready, visual(rec.events.last().second))
        advanceTimeBy(1_000)
        t.onFocus(focus(inputType = password, key = 2))
        runCurrent()
        val (at, v) = rec.events.last()
        assertEquals(1_000L, at) // no debounce on the way to a password field
        assertFalse(v.bubbleAllowedForField)
        assertEquals(BubbleVisual.Hidden, visual(v))
    }

    @Test
    fun blurIsDebouncedAndCancelledByAQuickRefocus() = runTest {
        val rec = Recorder()
        val t = tracker(rec)
        t.onFocus(focus(key = 1))
        runCurrent()
        val before = rec.events.size
        t.onFocus(focus(editable = false))
        advanceTimeBy(200)
        t.onFocus(focus(key = 2)) // focus moved to the next field within 300 ms: no flicker
        runCurrent()
        assertTrue(rec.events.drop(before).all { it.second.editableFocused })
        t.onFocus(focus(editable = false))
        advanceTimeBy(299); runCurrent()
        assertTrue(rec.events.last().second.editableFocused)
        advanceTimeBy(1); runCurrent()
        assertFalse(rec.events.last().second.editableFocused)
        assertEquals(BubbleVisual.Hidden, visual(rec.events.last().second))
    }

    @Test
    fun repeatedBlurEventsDoNotPostponeTheHide() = runTest {
        val rec = Recorder()
        val t = tracker(rec)
        t.onFocus(focus()); runCurrent()
        val start = testScheduler.currentTime
        repeat(5) { t.onFocus(focus(editable = false)); advanceTimeBy(100) }
        runCurrent()
        val hidAt = rec.events.first { !it.second.editableFocused }.first
        assertEquals(start + 300, hidAt)
    }

    @Test
    fun tucksInAfterFourSecondsAndANewFieldRestartsTheTimer() = runTest {
        val rec = Recorder()
        val t = tracker(rec)
        t.onFocus(focus(key = 1)); runCurrent()
        advanceTimeBy(3_000)
        t.onFocus(focus(key = 2)); runCurrent() // new field at t=3000
        advanceTimeBy(3_999); runCurrent()
        assertEquals(BubbleVisual.Ready, visual(rec.events.last().second))
        advanceTimeBy(1); runCurrent()
        assertEquals(7_000L, rec.events.last().first)
        assertEquals(BubbleVisual.Rest, visual(rec.events.last().second))
        // A finished dictation shows Ready again for a full 4 s.
        t.onDictationIdle(); runCurrent()
        assertEquals(BubbleVisual.Ready, visual(rec.events.last().second))
    }

    @Test
    fun noTuckInUnderTalkBackAndHideOneHourExpires() = runTest {
        val rec = Recorder()
        val t = tracker(rec)
        t.onTalkBack(true)
        t.onSettings(VisibilitySettings(hiddenUntilEpochMs = 10_000))
        t.onFocus(focus()); runCurrent()
        assertEquals(BubbleVisual.Hidden, visual(rec.events.last().second))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(10_000L, rec.events.last().first)
        assertEquals(BubbleVisual.Ready, visual(rec.events.last().second))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(BubbleVisual.Ready, visual(rec.events.last().second))
    }

    @Test
    fun hiddenInAppAndOwnUiStayHidden() = runTest {
        val rec = Recorder()
        val t = tracker(rec)
        t.onSettings(VisibilitySettings(showAlways = true, hiddenInPackages = setOf("com.bank")))
        t.onFocus(focus(pkg = "com.bank")); runCurrent()
        assertEquals(BubbleVisual.Hidden, visual(rec.events.last().second))
        t.onFocus(focus(pkg = own, key = 3)); runCurrent()
        assertEquals(BubbleVisual.Hidden, visual(rec.events.last().second))
        t.onFocus(focus(pkg = "com.other", editable = false)); advanceTimeBy(300); runCurrent()
        assertEquals(BubbleVisual.Rest, visual(rec.events.last().second)) // "Always": Rest tab without a field
    }
}
