package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.AsrReadiness
import app.murmur.core.dictation.DictationOutcome
import app.murmur.core.dictation.DictationState
import app.murmur.core.engine.HostState
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.store.EntryStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * :asr killed while it is still loading the model (events suites; its own instrumentation run, so :asr starts cold).
 * ADR-066 / ADR-083: a death during a load is that load's error (a unit failure unless lmkd reported low memory) and the
 * fallback is tried; with no other ASR installed the dictation fails with the recording kept and the ASR unit stays
 * marked failed (until the next app version: every later dictation then fails with model.asr_missing, which is why the
 * test clears the marks afterwards). Whatever the outcome, the dictation must not hang: it ends after the stop tap
 * with a history row that holds the recording, and the next dictation ends too. On the fakes the load takes 5 s
 * (FakeAsrEngine.loadDelayMs); on the real engines GigaAM's own load (3-7 s on the emulator) is the window.
 */
@RunWith(AndroidJUnit4::class)
class AsrLoadDeathE2eTest : DictationTest() {
    private val fakes get() = Pipeline.fakes
    private val doneTimeoutMs get() = if (fakes != null) 20_000L else 180_000L

    @Before
    fun world() {
        if (fakes != null) Pipeline.fakeWorld(llmMsPerWord = 250, wav = Fixtures.toneBurst(totalMs = 10_000, speechMs = 9_000))
        else Pipeline.realWorld()
        TargetApp.open(TargetApp.Target.PLAIN)
    }

    @Test
    fun asrDeathDuringItsLoadEndsTheDictation() {
        val host = E2e.container.engines.asrHost
        if (host.state.value is HostState.Up) runBlocking { host.shutdown() }
        fakes?.asr?.loadDelayMs = 5_000
        try {
            Bubble.tapToStart()
            rec.awaitListening(30_000)
            E2e.waitUntil("ASR loading with :asr up", 30_000, pollMs = 20) {
                rec.enginesOf(rec.state)?.asr is AsrReadiness.Loading && host.state.value is HostState.Up &&
                    (fakes != null || Processes.pidOf(Processes.ASR) != null)
            }
            E2e.report("asr_load_death.kill", Pipeline.crash(InferProcess.ASR))
            SystemClock.sleep(3_000)
            E2e.report("asr_load_death.asr_after_3s", rec.enginesOf(rec.state)?.asr?.let { it::class.simpleName } ?: rec.state::class.simpleName!!)
            val stopAt = SystemClock.uptimeMillis()
            if (rec.state is DictationState.Listening) Bubble.tapToFinish()
            val done = rec.awaitDone(doneTimeoutMs)
            E2e.report("asr_load_death.outcome", outcomeLabel(done.outcome))
            E2e.report("asr_load_death.stop_to_done_ms", SystemClock.uptimeMillis() - stopAt)
            when (done.outcome) {
                is DictationOutcome.Failed -> assertEntry(EntryStatus.FAILED)
                is DictationOutcome.Inserted -> assertEntry(EntryStatus.INSERTED)
                else -> throw AssertionError("a death during the load ends Failed (recording kept) or Inserted after a fallback, not ${done.outcome}")
            }
            val failed = failedUnits()
            E2e.report("asr_load_death.failed_units", failed.joinToString(" ").ifEmpty { "none" })
            if (fakes == null) {
                // The real engines, one ASR installed: the load fails with the death, the unit is marked (ADR-066: a
                // process death during the load is a unit failure; SIGKILL is not lmkd's REASON_LOW_MEMORY, ADR-083), and
                // with no other candidate the dictation fails with the recording kept. (The fakes keep loading through a
                // host crash, so there the dictation ends Inserted.)
                assertEquals(MurmurError.ProcessDied(InferProcess.ASR).code, (done.outcome as? DictationOutcome.Failed)?.error?.code)
                assertTrue("the ASR unit is marked failed: $failed", failed.any { it.startsWith(Pipeline.ASR_MODEL) })
            }
        } finally {
            fakes?.asr?.loadDelayMs = 0
            clearFailedUnits()
        }
        // The app after the death, with the unit marks cleared: the next dictation must end as well (no hang in Arming).
        // The Error pill stays 6 s x debugTimeoutScale before the orchestrator is Idle again.
        val scale = E2e.container.settings.settings.value.debugTimeoutScale.coerceAtLeast(1f)
        E2e.waitUntil("orchestrator back to Idle", (15_000 * scale).toLong(), pollMs = 100) { rec.state is DictationState.Idle }
        rec.states.clear()
        historyBefore = Pipeline.historyCount()
        Bubble.tapToStart()
        val next = E2e.waitFor("the next dictation listens or ends", 60_000, pollMs = 50) {
            rec.state.takeIf { it is DictationState.Listening || it is DictationState.Done }
        }
        if (next is DictationState.Listening) {
            SystemClock.sleep(3_000)
            Bubble.tapToFinish()
        }
        val after = rec.awaitDone(doneTimeoutMs)
        E2e.report("asr_load_death.next_outcome", outcomeLabel(after.outcome))
        assertTrue("the next dictation ended: ${rec.stateKinds()}", after.outcome !is DictationOutcome.Discarded)
    }

    private fun outcomeLabel(o: DictationOutcome): String =
        if (o is DictationOutcome.Failed) "Failed:${o.error.code}" else o::class.simpleName!!

    private fun failedUnits(): List<String> {
        val c = E2e.container
        return c.installed.states.value.keys.flatMap { id ->
            ComputeUnit.entries.filter { c.unitHealth.failed(id, it) }.map { "${id.value}:${it.name}" }
        }
    }

    private fun clearFailedUnits() = runBlocking {
        val c = E2e.container
        for (id in c.installed.states.value.keys) for (u in ComputeUnit.entries) if (c.unitHealth.failed(id, u)) c.unitHealth.clear(id, u)
    }
}
