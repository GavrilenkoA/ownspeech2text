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

/** History "Re-polish" and Settings "Try it" (WP-11 Deliver 4); Retry is in RetryTest. */
class BackgroundWorkTest {

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
