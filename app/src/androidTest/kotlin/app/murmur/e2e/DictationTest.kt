package app.murmur.e2e

import android.os.SystemClock
import app.murmur.core.dictation.DictationOutcome
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.UserInput
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import java.io.File

/**
 * Common shape of a dictation test: the service on, the target app in front with its field focused, a recorder of
 * every state the orchestrator publishes, the emulator restored afterwards.
 */
abstract class DictationTest {
    protected lateinit var rec: DictationRecorder
    protected var historyBefore = 0

    /** False for a test that never dictates (it then neither needs the service nor the orchestrator). */
    protected open val dictates: Boolean get() = true

    @Before
    fun startDictationWorld() {
        if (!dictates) return
        Accessibility.enable()
        rec = DictationRecorder()
        historyBefore = Pipeline.historyCount()
    }

    @After
    fun stopDictationWorld() {
        if (!::rec.isInitialized) return
        E2e.note("states=${rec.stateKinds()} bubbles=${rec.bubbleKinds()}")
        rec.close()
        // A failed test may leave its dictation running: discard it (the x button's input) so the next test starts clean.
        val s = E2e.container.orchestrator.state.value
        if (s is DictationState.Arming || s is DictationState.Listening) E2e.container.orchestrator.onInput(UserInput.Cancel)
        DeviceEvents.restore()
        if (!Accessibility.connected) Accessibility.enable()
        // Let a result pill dismiss (Error 6 s, x debugTimeoutScale) so that the next test starts from Idle.
        val scale = E2e.container.settings.settings.value.debugTimeoutScale.coerceAtLeast(1f)
        E2e.waitUntil("orchestrator back to Idle", (15_000 * scale).toLong(), pollMs = 100) { E2e.container.orchestrator.state.value is DictationState.Idle }
    }

    /** Tap → Listening → [during] while it listens for [listenMs] → tap. Returns when the stop tap was delivered. */
    protected fun dictate(listenMs: Long, listeningTimeoutMs: Long = 10_000, during: () -> Unit = {}) {
        Bubble.tapToStart()
        rec.awaitListening(listeningTimeoutMs)
        val start = SystemClock.uptimeMillis()
        during()
        val left = listenMs - (SystemClock.uptimeMillis() - start)
        if (left > 0) SystemClock.sleep(left)
        Bubble.tapToFinish()
    }

    protected fun assertInserted(done: DictationState.Done, noCleanup: Boolean): DictationOutcome.Inserted {
        val o = done.outcome
        assertTrue("outcome $o", o is DictationOutcome.Inserted)
        o as DictationOutcome.Inserted
        assertEquals("noCleanup (skip reason ${o.skipReason})", noCleanup, o.noCleanup)
        return o
    }

    protected fun assertFailed(done: DictationState.Done, code: String): DictationOutcome.Failed {
        val o = done.outcome
        assertTrue("outcome $o", o is DictationOutcome.Failed)
        o as DictationOutcome.Failed
        assertEquals(code, o.error.code)
        return o
    }

    /** The history row of this dictation: written into the target app, with its status and (when kept) the audio file. */
    protected fun assertEntry(status: EntryStatus, pkg: String = TargetApp.PKG, withAudio: Boolean = true): HistoryEntry {
        val e = Pipeline.awaitNewEntry(historyBefore)
        assertEquals(status, e.status)
        assertEquals("target package of the row", pkg, e.app.pkg)
        if (withAudio) {
            val audio = e.audio ?: throw AssertionError("the row has no audio ref (keep recordings is on by default)")
            val file = File(audio.path).takeIf { it.isAbsolute } ?: File(E2e.context.filesDir, audio.path)
            assertTrue("history audio file ${file.name} exists", file.isFile && file.length() > 0)
        }
        return e
    }
}
