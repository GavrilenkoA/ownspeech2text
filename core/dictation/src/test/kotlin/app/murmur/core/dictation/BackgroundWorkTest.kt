package app.murmur.core.dictation

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.EntryStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** History "Try again" / "Re-polish" and Settings "Try it" (WP-11 Deliver 4). */
class BackgroundWorkTest {

    @Test fun retryRetranscribesTheStoredAudioAndTheEntryBecomesCopied() = runTest {
        val h = harness {
            models.npuFirst = true; models.cpuFallback = false
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.EngineLoadFailed(ModelKind.ASR, "qnn")
        }
        h.dictate()
        val failed = h.only()
        assertEquals(EntryStatus.FAILED, failed.status)
        // The CPU artifact got installed; the NPU stays marked failed, so the resolver picks CPU.
        h.w.models.cpuFallback = true
        val r = h.o.retry(failed.id)
        assertEquals(Outcome.Ok(failed.id), r)
        val e = h.only()
        assertEquals(EntryStatus.COPIED, e.status)
        assertEquals("Привет это тестовая диктовка.", e.finalText)
        assertEquals("привет это тестовая диктовка", e.rawText)
        assertEquals("accept", e.cleanup?.outcome)
        assertEquals(ComputeUnit.CPU, e.asr?.unit)
        assertEquals(listOf("Привет это тестовая диктовка."), h.w.inserter.clipboard)
        assertEquals(2_800L * 16, h.w.engines.asr.sessions.last().samples)  // the whole recording, fed at once
        h.assertNoInternalErrors()
    }

    @Test fun errorPillRetryRunsTheSameRetry() = runTest {
        val h = harness { engines.asr.finishError = MurmurError.EngineLoadFailed(ModelKind.ASR, "runtime") }
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(3_000)
        assertEquals(DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY), h.o.bubble.value)
        h.w.engines.asr.finishError = null
        h.o.onInput(UserInput.ResultAction)
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(EntryStatus.COPIED, h.only().status)
        h.assertNoInternalErrors()
    }

    @Test fun repolishRerunsCleanupOnTheRawTextAndBumpsTheRevision() = runTest {
        val h = harness()
        h.dictate()
        val first = h.only()
        h.w.engines.llm.output = { "Привет! Это тестовая диктовка." }
        assertEquals(Outcome.Ok(first.id), h.o.repolish(first.id))
        val e = h.only()
        assertEquals("Привет! Это тестовая диктовка.", e.finalText)
        assertEquals(first.rawText, e.rawText)
        assertEquals(2, e.revision)
        assertTrue(h.w.engines.llm.requests.last().suffix.contains(first.rawText!!))
        assertTrue(!h.w.engines.llm.requests.last().suffix.contains("Лёша"))   // no screen context: never stored
        h.assertNoInternalErrors()
    }

    @Test fun tryCleanupReturnsTheTextAndStoresNothing() = runTest {
        val h = harness()
        assertEquals(Outcome.Ok("Привет мир."), h.o.tryCleanup("привет мир", "Пиши коротко.", "Без эмодзи"))
        assertTrue(h.history().isEmpty())
        assertTrue(h.w.metrics.snapshot().isEmpty())
        assertTrue(h.w.engines.llm.prefixCalls.single().text.contains("Пиши коротко."))
        h.w.settings.edit { it.copy(cleanup = CleanupChoice.None) }
        assertEquals(Outcome.Err(MurmurError.LlmFailed("none")), h.o.tryCleanup("привет", null, null))
        h.assertNoInternalErrors()
    }
}
