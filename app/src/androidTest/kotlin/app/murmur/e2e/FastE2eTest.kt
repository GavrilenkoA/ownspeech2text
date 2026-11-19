package app.murmur.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.store.EntryStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fast end-to-end test (WP-20 brief 4, "fast"): the real :main (accessibility service, bubble overlay, orchestrator,
 * insertion ladder, Room history, WAV capture) on the :testing fake engines. Runs on both AVDs.
 * Precondition (tools/e2e/run_emulator_e2e.sh --suite fast): `files/debug-fake-engines` exists before :main starts.
 */
@RunWith(AndroidJUnit4::class)
class FastE2eTest : DictationTest() {
    @Before
    fun fakeWorld() {
        val fakes = Pipeline.requireFakes()
        // Each word of the fake cleanup takes 250 ms: Polishing stays on screen long enough to be seen and recorded.
        fakes.llm.delayPerPieceMs = 250
        fakes.llm.hang = false
        Fixtures.installCapture(Fixtures.toneBurst())
    }

    @Test
    fun tapListenTapInsertsTheCleanedTextAndWritesHistory() {
        val fakes = Pipeline.requireFakes()
        TargetApp.open(TargetApp.Target.PLAIN)
        dictate(listenMs = 3_200)
        // What the user sees, read from the overlay itself (not only from the orchestrator's flow).
        E2e.waitUntil("Polishing pill on screen; overlays ${Bubble.overlayTexts()}", 10_000, pollMs = 30) { Bubble.shows(Bubble.polishingText) }
        E2e.waitUntil("Inserted pill on screen; overlays ${Bubble.overlayTexts()}", 15_000, pollMs = 30) { Bubble.shows(Bubble.insertedText) }
        val done = rec.awaitDone(5_000)
        assertInserted(done, noCleanup = false)

        val expected = Pipeline.fakeCleaned()
        TargetApp.awaitText("the fake cleanup text") { it.trimEnd() == expected }

        val entry = assertEntry(EntryStatus.INSERTED)
        assertEquals(expected, entry.finalText?.trimEnd())
        assertEquals(fakes.asr.script, entry.rawText)
        assertEquals("accept", entry.cleanup?.outcome)
        rec.assertBubbleOrder("Listening", "Polishing", "Inserted")
        rec.assertStateOrder("Arming", "Listening", "Finishing", "Polishing", "Delivering", "Done")
        E2e.report("fast.stop_to_done_ms", entry.timings.stopToDoneMs ?: -1)
    }
}
