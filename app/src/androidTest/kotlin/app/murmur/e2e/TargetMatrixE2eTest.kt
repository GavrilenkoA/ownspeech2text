package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.ContextReadiness
import app.murmur.core.dictation.DictationState
import app.murmur.core.store.EntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One fake-engine dictation into every kind of field of the target app (EditText single/multi-line, Compose TextField,
 * WebView input and contenteditable, a FLAG_SECURE window), plus the password field where nothing may happen.
 * The text must arrive in the target's own log, and the row must name the target package.
 */
@RunWith(AndroidJUnit4::class)
class TargetMatrixE2eTest : DictationTest() {
    @Before
    fun fakeWorld() {
        Pipeline.fakeWorld(llmMsPerWord = 0)
    }

    private fun dictateInto(target: TargetApp.Target) {
        TargetApp.open(target)
        dictate(listenMs = 3_200)
        assertInserted(rec.awaitDone(15_000), noCleanup = false)
        val expected = Pipeline.fakeCleaned()
        TargetApp.awaitText("cleanup text in ${target.id}") { it.trimEnd() == expected }
        assertEntry(EntryStatus.INSERTED)
    }

    @Test fun plainEditText() = dictateInto(TargetApp.Target.PLAIN)
    @Test fun multilineEditText() = dictateInto(TargetApp.Target.MULTILINE)
    @Test fun composeTextField() = dictateInto(TargetApp.Target.COMPOSE)
    @Test fun webViewInput() = dictateInto(TargetApp.Target.WEB_INPUT)
    @Test fun webViewContentEditable() = dictateInto(TargetApp.Target.WEB_CONTENTEDITABLE)

    /** FLAG_SECURE: the screenshot fails or is black, so the context carries no screen (ARCHITECTURE 9.1), text still goes in. */
    @Test
    fun secureWindowGetsTheTextWithoutScreenContext() {
        dictateInto(TargetApp.Target.SECURE)
        val contexts = rec.states.mapNotNull { rec.enginesOf(it.value)?.context as? ContextReadiness.Ready }
        assertTrue("context readiness seen during the dictation", contexts.isNotEmpty())
        assertTrue("no screen context from a secure window: $contexts", contexts.none { it.hasScreen })
    }

    /** Password field (DESIGN 5.2, ARCHITECTURE 9.2): no bubble, no dictation, nothing typed, nothing stored. */
    @Test
    fun passwordFieldHidesTheBubbleAndNothingHappens() {
        TargetApp.open(TargetApp.Target.PASSWORD)
        SystemClock.sleep(2_000)
        assertTrue("bubble overlays over a password field: ${Bubble.overlayTexts()}", Bubble.overlayTexts().none { it == Bubble.readyText })
        assertTrue(rec.states.all { it.value is DictationState.Idle })
        assertNull(TargetApp.lastText())
        assertEquals(historyBefore, Pipeline.historyCount())
    }
}
