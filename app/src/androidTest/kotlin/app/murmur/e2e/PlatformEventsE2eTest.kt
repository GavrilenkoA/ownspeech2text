package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.murmur.core.dictation.DictationState
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.ProductName
import app.murmur.core.store.EntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Platform events during a dictation (ADR-058, WP-20 brief 4 a-g), each against the outcome ARCHITECTURE 4.4 / 5.2
 * documents and the history row. Run on `murmur_fold7_inner` with the host bridge (hinge sensor, modem).
 * On fake engines a process "kill" is the fake host's crash (the same death signal the binder clients raise); with real
 * engines it is a SIGKILL of the process ([Pipeline.crash]).
 */
@RunWith(AndroidJUnit4::class)
class PlatformEventsE2eTest : DictationTest() {
    @Before
    fun world() {
        HostBridge.requireRunning()
        Pipeline.fakes?.llm?.let { it.delayPerPieceMs = 250; it.hang = false }
        if (Pipeline.fakes != null) Fixtures.installCapture(Fixtures.toneBurst(totalMs = 4_000, speechMs = 3_000))
        TargetApp.open(TargetApp.Target.PLAIN)
    }

    private val doneTimeoutMs get() = if (Pipeline.fakes != null) 15_000L else 240_000L

    private fun assertInsertedRow() {
        assertInserted(rec.awaitDone(doneTimeoutMs), noCleanup = false)
        TargetApp.awaitText("inserted text") { it.isNotBlank() }
        assertEntry(EntryStatus.INSERTED)
    }

    /** (a) fold + unfold while Listening: the dictation continues, the bubble keeps its edge, the text is inserted. */
    @Test
    fun foldAndUnfoldDuringListening() {
        dictate(listenMs = 4_000) {
            val width = DeviceEvents::displayWidth
            val rightBefore = Bubble.dockedRight(width())
            DeviceEvents.fold()
            E2e.waitFor("bubble back on the folded display", 5_000, pollMs = 100) { Bubble.bounds() }
            assertEquals("edge after fold", rightBefore, Bubble.dockedRight(width()))
            assertTrue("still Listening after fold: ${rec.state}", rec.state is DictationState.Listening)
            DeviceEvents.unfold()
            E2e.waitFor("bubble back on the unfolded display", 5_000, pollMs = 100) { Bubble.bounds() }
            assertEquals("edge after unfold", rightBefore, Bubble.dockedRight(width()))
            assertTrue("still Listening after unfold: ${rec.state}", rec.state is DictationState.Listening)
        }
        assertInsertedRow()
    }

    /** (b) rotation while Listening (`settings put system user_rotation 1`). */
    @Test
    fun rotationDuringListening() {
        dictate(listenMs = 4_000) {
            DeviceEvents.rotate(1)
            E2e.waitFor("bubble after rotation", 5_000, pollMs = 100) { Bubble.bounds() }
            assertTrue("still Listening after rotation: ${rec.state}", rec.state is DictationState.Listening)
        }
        assertInsertedRow()
    }

    /**
     * (c) incoming call while Listening (`adb emu gsm call`): ExternalStop(CALL) → Finishing with the audio so far, no stop
     * tap needed, then delivered (Inserted, or Copied when the call screen took the focus).
     */
    @Test
    fun incomingCallStopsAndProcessesTheAudioSoFar() {
        Bubble.tapToStart()
        rec.awaitListening()
        SystemClock.sleep(3_500)
        DeviceEvents.incomingCall()
        rec.awaitState("Finishing without a tap", 5_000) { it as? DictationState.Finishing }
        val done = rec.awaitDone(doneTimeoutMs)
        DeviceEvents.endCall()
        val outcome = done.outcome
        assertTrue("delivered despite the call: $outcome",
            outcome is app.murmur.core.dictation.DictationOutcome.Inserted || outcome is app.murmur.core.dictation.DictationOutcome.Copied)
        val row = Pipeline.awaitNewEntry(historyBefore)
        assertTrue("row status ${row.status}", row.status == EntryStatus.INSERTED || row.status == EntryStatus.COPIED)
        assertTrue("recorded up to the call, not beyond: ${row.timings.recordMs} ms", row.timings.recordMs in 3_000..8_000)
        E2e.report("events.call.outcome", outcome::class.simpleName!!)
    }

    /** (d) :asr dies while Listening: re-bind + replay of the session buffer, the dictation still ends Inserted. */
    @Test
    fun asrDeathWhileListeningIsRecovered() {
        val sessionsBefore = Pipeline.fakes?.asr?.sessions?.size
        dictate(listenMs = 4_500) {
            SystemClock.sleep(1_500)
            E2e.report("events.asr_kill", Pipeline.crash(InferProcess.ASR))
            E2e.waitUntil("ASR restarted (asrCrashes = 1)", 15_000, pollMs = 50) { rec.enginesOf(rec.state)?.asrCrashes == 1 }
            assertTrue("still Listening: ${rec.state}", rec.state is DictationState.Listening)
        }
        assertInsertedRow()
        sessionsBefore?.let { before ->
            val sessions = Pipeline.requireFakes().asr.sessions
            assertEquals("a second ASR session after the crash", before + 2, sessions.size)
            assertTrue("replay from sample 0: ${sessions.last().samples} >= ${sessions[sessions.size - 2].samples}",
                sessions.last().samples >= sessions[sessions.size - 2].samples)
        }
    }

    /** (d') a second :asr death in the same dictation: Failed with the audio kept ("Try again"). */
    @Test
    fun secondAsrDeathFailsWithAudio() {
        dictate(listenMs = 5_000) {
            SystemClock.sleep(1_000)
            Pipeline.crash(InferProcess.ASR)
            E2e.waitUntil("first ASR crash counted", 15_000, pollMs = 50) { rec.enginesOf(rec.state)?.asrCrashes == 1 }
            E2e.waitUntil("ASR ready again", 30_000, pollMs = 50) {
                rec.enginesOf(rec.state)?.asr is app.murmur.core.dictation.AsrReadiness.Ready
            }
            Pipeline.crash(InferProcess.ASR)
            E2e.waitUntil("ASR failed after the second death", 15_000, pollMs = 50) {
                rec.enginesOf(rec.state)?.asr is app.murmur.core.dictation.AsrReadiness.Failed
            }
        }
        assertFailed(rec.awaitDone(doneTimeoutMs), MurmurError.ProcessDied(InferProcess.ASR).code)
        val row = assertEntry(EntryStatus.FAILED)
        assertEquals(MurmurError.ProcessDied(InferProcess.ASR).code, row.failureCode)
        assertEquals("nothing typed into the target", null, TargetApp.lastText())
    }

    /** (e) :llm dies during Polishing: the raw transcript is inserted, "Inserted, no cleanup" (skipped:llm_died). */
    @Test
    fun llmDeathDuringPolishingInsertsTheRawText() {
        Pipeline.fakes?.llm?.delayPerPieceMs = 1_500
        dictate(listenMs = 3_500)
        rec.awaitState("Polishing with cleanup started", if (Pipeline.fakes != null) 10_000 else 120_000) {
            (it as? DictationState.Polishing)?.takeIf { p -> p.cleanupStarted }
        }
        E2e.report("events.llm_kill", Pipeline.crash(InferProcess.LLM))
        val inserted = assertInserted(rec.awaitDone(doneTimeoutMs), noCleanup = true)
        E2e.report("events.llm_kill.skip_reason", inserted.skipReason ?: "null")
        val row = assertEntry(EntryStatus.INSERTED)
        assertEquals("skipped:llm_died", row.cleanup?.outcome)
        val raw = row.rawText ?: throw AssertionError("the row has no raw transcript")
        TargetApp.awaitText("the raw transcript") { it.trimEnd() == raw.trimEnd() }
        Pipeline.fakes?.let { assertEquals(it.asr.script, raw) }
    }

    /** (f) the service is turned off while Listening: Failed(AccessibilityOff), audio kept, nothing inserted. */
    @Test
    fun serviceDisabledWhileListeningFailsWithAudio() {
        Bubble.tapToStart()
        rec.awaitListening()
        SystemClock.sleep(2_500)
        Accessibility.disable()
        assertFailed(rec.awaitDone(doneTimeoutMs), MurmurError.AccessibilityOff.code)
        val row = assertEntry(EntryStatus.FAILED)
        assertEquals(MurmurError.AccessibilityOff.code, row.failureCode)
        assertEquals(null, TargetApp.lastText())
        assertTrue("no bubble without the service", Bubble.overlayWindows().isEmpty())
    }

    /**
     * (g) start from the status notification: expand the shade, tap "Start dictation", the shade collapses, and the
     * target snapshot is the app below it (ADR-056), not SystemUI.
     */
    @Test
    fun notificationStartTargetsTheAppBelowTheShade() {
        val res = E2e.context.resources
        val action = res.getString(res.getIdentifier("notif_action_start", "string", E2e.PKG))
        val title = res.getString(res.getIdentifier("notif_ready_title", "string", E2e.PKG), ProductName.NAME)
        DeviceEvents.expandShade()
        val d = E2e.device
        val actionPattern = Pattern.compile(Pattern.quote(action), Pattern.CASE_INSENSITIVE)
        if (!d.wait(Until.hasObject(By.text(actionPattern)), 3_000)) {
            // The notification may be collapsed in the shade: expand it by its title row.
            d.findObject(By.text(title))?.click()
        }
        val button = d.wait(Until.findObject(By.text(actionPattern)), 5_000) ?: throw AssertionError("'$action' is not in the shade")
        button.click()
        rec.awaitListening(15_000)
        if (DeviceEvents.shadeOpen()) DeviceEvents.collapseShade()
        SystemClock.sleep(3_000)
        Bubble.tapToFinish()
        assertInsertedRow()
        val row = Pipeline.historyEntries().maxBy { it.createdAt }
        assertEquals("the app below the shade", TargetApp.PKG, row.app.pkg)
    }
}
