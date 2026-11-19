package app.murmur.platform.a11y

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.store.BubbleVisibility
import app.murmur.platform.a11y.A11yTestSupport.enableService
import app.murmur.platform.a11y.A11yTestSupport.host
import app.murmur.platform.a11y.A11yTestSupport.launch
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.service
import app.murmur.platform.a11y.A11yTestSupport.snapshotOf
import app.murmur.platform.a11y.A11yTestSupport.waitFor
import app.murmur.platform.overlay.BubblePolicy
import app.murmur.platform.overlay.BubbleVisual
import app.murmur.platform.overlay.VisibilityInput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DESIGN 5.2 on the emulator: a password field hides the bubble within 300 ms, also with "Show the bubble: Always". */
@RunWith(AndroidJUnit4::class)
class VisibilityEmulatorTest {
    @Before fun setUp() {
        enableService()
        runBlocking { host.settings.update { it.copy(bubbleVisibility = BubbleVisibility.ALWAYS) } }
    }

    @After fun tearDown() { runBlocking { host.settings.update { it.copy(bubbleVisibility = BubbleVisibility.WHEN_TYPING) } } }

    private fun visual(v: VisibilityInput) = BubblePolicy.merge(DictationBubble.Idle, v, dragging = false, overHideTarget = false)

    private fun surface(displayId: Int) = waitFor("bubble surface on display $displayId", 5_000) {
        host.surfaces.lastOrNull { it.displayId == displayId && it.attached }
    }

    @Test
    fun passwordFocusHidesTheBubbleWithin300ms() {
        launch(FocusPairActivity::class.java)
        val snap = snapshotOf("Focus pair")
        val bubble = surface(snap.displayId)
        waitFor("Ready on the plain field", 5_000) {
            bubble.inputs.lastOrNull()?.second?.takeIf { it.editableFocused && it.bubbleAllowedForField && it.showAlways }
        }
        val mark = bubble.inputs.size
        // The password EditText (android.R.id.text2) below the focused plain field, in the same window.
        val pw = runBlocking {
            withContext(service().ioDispatcher) {
                service().windows.first { it.id == snap.windowId }.root?.findAccessibilityNodeInfosByViewId("android:id/text2")?.firstOrNull()
            }
        }!!
        val t0 = SystemClock.uptimeMillis()
        assertTrue(runBlocking { withContext(service().ioDispatcher) { pw.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } })
        val (at, hidden) = waitFor("password VisibilityInput", 2_000, pollMs = 10) {
            bubble.inputs.drop(mark).firstOrNull { !it.second.bubbleAllowedForField }
        }
        val sequence = bubble.inputs.map { (t, v) ->
            "${t - t0}ms:${visual(v)}(editable=${v.editableFocused},allowed=${v.bubbleAllowedForField},appHidden=${v.appHidden},always=${v.showAlways})"
        }
        note("visibility password latency_ms=${at - t0} sequence=$sequence")
        assertTrue("latency ${at - t0} ms", at - t0 <= 300)
        assertTrue(hidden.showAlways)
        assertTrue(hidden.editableFocused)
        assertEquals(BubbleVisual.Hidden, visual(hidden))
        // The sequence went Ready (plain field) -> Hidden (password), with no Ready after the password focus.
        assertEquals(BubbleVisual.Ready, visual(bubble.inputs.last { it.first < t0 }.second))
        assertTrue(bubble.inputs.drop(bubble.inputs.indexOfFirst { it.first == at }).all { visual(it.second) == BubbleVisual.Hidden })
    }
}
