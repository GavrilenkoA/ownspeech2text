package app.murmur.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.DictationOutcome
import app.murmur.core.engine.HostState
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.store.EntryStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retry from the bubble (WP-11's proposed e2e case, ADR-064), on the :testing fake engines (an out-of-memory load can
 * only be forced there): the ASR cannot be loaded → Failed with the recording kept → Retry on the Error pill → the
 * stored recording runs Finishing → Polishing → Delivering → Inserted into the same field, and the history row is
 * updated in place (no second row). Runs in the `fast` suite.
 */
@RunWith(AndroidJUnit4::class)
class RetryE2eTest : DictationTest() {
    @Before
    fun fakeWorld() {
        Pipeline.fakeWorld(llmMsPerWord = 250)
        TargetApp.open(TargetApp.Target.PLAIN)
    }

    @Test
    fun retryAfterAnAsrOutOfMemoryInsertsIntoTheSameField() {
        val fakes = Pipeline.requireFakes()
        // A model a previous test left loaded would not be loaded again: start from a stopped :asr, as after its idle exit.
        if (fakes.asrHost.state.value is HostState.Up) runBlocking { fakes.asrHost.shutdown() }
        fakes.asr.loadError = MurmurError.OutOfMemory(ModelKind.ASR)
        dictate(listenMs = 3_200)
        assertFailed(rec.awaitDone(15_000), MurmurError.OutOfMemory(ModelKind.ASR).code)
        val row = assertEntry(EntryStatus.FAILED)   // with its audio file: what Retry transcribes
        assertEquals(MurmurError.OutOfMemory(ModelKind.ASR).code, row.failureCode)
        assertEquals("nothing typed before the Retry", null, TargetApp.lastText())

        fakes.asr.loadError = null
        rec.states.clear()
        Bubble.tapAction(Bubble.retryText)
        val done = rec.awaitDone(20_000)
        assertInserted(done, noCleanup = false)
        rec.assertStateOrder("Arming", "Finishing", "Polishing", "Delivering", "Done(Inserted)")
        // What the user sees: the Error pill with Retry, then the normal working pills of the retried recording.
        rec.assertBubbleOrder("Listening", "Error", "Transcribing", "Polishing", "Inserted")
        val expected = Pipeline.fakeCleaned()
        TargetApp.awaitText("the retried dictation's cleanup text") { it.trimEnd() == expected }

        assertEquals("the failed row is updated in place, no second row", historyBefore + 1, Pipeline.historyCount())
        val updated = Pipeline.historyEntries().first { it.id == row.id }
        assertEquals(EntryStatus.INSERTED, updated.status)
        assertEquals(expected, updated.finalText?.trimEnd())
        assertEquals(fakes.asr.script, updated.rawText)
        assertEquals("the same target app", TargetApp.PKG, updated.app.pkg)
        assertEquals("the Inserted outcome names the retried entry", row.id, (done.outcome as DictationOutcome.Inserted).entry)
    }
}
