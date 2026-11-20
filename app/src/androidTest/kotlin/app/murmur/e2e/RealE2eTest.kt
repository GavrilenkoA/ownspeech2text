package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.benchmark.WordErrorRate
import app.murmur.core.dictation.AsrReadiness
import app.murmur.core.dictation.ContextReadiness
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.LlmReadiness
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real end-to-end test (WP-20 brief 4, "real"; ADR-063): GigaAM v3 RNN-T int8 + Silero VAD + both OCR models in
 * :asr and Qwen3.5 0.8B Q4_0 in :llm, all on the x86_64 CPU, with FLEURS dev clips played by the debug WAV capture.
 * tools/e2e/run_emulator_e2e.sh --suite real pushes the pinned models into noBackupFilesDir/models/<artifact>/ (with
 * installed.json), pushes the ru and en clips and their references, and boots the inner AVD with `-qemu -cpu host`.
 * Every number reported here is an emulator number.
 * The ru dictations assert what the brief lists (Cyrillic text, WER <= 20 %, guard verdict accept, audio, idle exit) plus
 * the screen context of D11 (a planned screenshot and OCR text in the cleanup context). The en dictation runs the same
 * pinned engines: GigaAM is trained on Russian only (catalog note), so its English WER is reported, not asserted; it
 * measures the latency of an English dictation through the whole pipeline and asserts the insertion and the recorded
 * guard verdict. Per dictation the stages after the stop are reported from the orchestrator's states
 * (stop -> Finishing -> Polishing -> Delivering -> Done(Inserted)) and from the history row's timings.
 */
@RunWith(AndroidJUnit4::class)
class RealE2eTest : DictationTest() {
    @Before
    fun realWorld() {
        Pipeline.realWorld()
    }

    /**
     * Three dictations: ru cold (both processes start, the LLM builds its prefix: no Preparing ran for pushed models),
     * ru warm, en warm; then the idle exit of both processes, then the four tabs of the app read the data these
     * dictations produced.
     */
    @Test
    fun ruAndEnClipsAreTranscribedCleanedInsertedAndStoredThenTheProcessesExitAndTheTabsShowTheData() {
        val rows = mutableListOf<HistoryEntry>()
        rows += dictateClip("cold", "ru")
        rows += dictateClip("warm", "ru")
        rows += dictateClip("en", "en")
        val doneAt = SystemClock.uptimeMillis()

        // D5: 70 s after Done both inference processes are gone (idle unload 60 s, then unload + exit).
        var asrGoneAt: Long? = null
        var llmGoneAt: Long? = null
        while (SystemClock.uptimeMillis() < doneAt + 70_000) {
            val now = SystemClock.uptimeMillis()
            if (asrGoneAt == null && Processes.pidOf(Processes.ASR) == null) asrGoneAt = now - doneAt
            if (llmGoneAt == null && Processes.pidOf(Processes.LLM) == null) llmGoneAt = now - doneAt
            SystemClock.sleep(1_000)
        }
        E2e.report("real.asr_exit_after_done_ms", asrGoneAt ?: -1)
        E2e.report("real.llm_exit_after_done_ms", llmGoneAt ?: -1)
        assertNull(":asr still running 70 s after Done", Processes.pidOf(Processes.ASR))
        assertNull(":llm still running 70 s after Done", Processes.pidOf(Processes.LLM))

        // The four tabs show this run's data (not samples, not an empty state).
        AppTabs.open()
        val tabs = try {
            listOf(AppTabs.history(rows), AppTabs.dashboard(), AppTabs.models(Pipeline.ASR_MODEL, Pipeline.LLM_MODEL), AppTabs.settings())
        } finally {
            AppTabs.close()
        }
        tabs.forEach { E2e.report("real.tab.${it.tab}", "${if (it.realData) "real" else "NOT real"}: ${it.why}") }
        E2e.report("real.tabs_with_real_data", "${tabs.count { it.realData }}/${tabs.size}")
        assertEquals("tabs with real data: $tabs", tabs.size, tabs.count { it.realData })
    }

    /** One dictation of the [lang] clip into a fresh target field; returns its history row. */
    private fun dictateClip(label: String, lang: String): HistoryEntry {
        val (clipMs, reference) = Fixtures.installFleurs(lang)
        val r = "real.$label"
        E2e.report("$r.clip", "$lang ${clipMs} ms")
        rec.states.clear()
        historyBefore = Pipeline.historyCount()
        TargetApp.open(TargetApp.Target.PLAIN)
        val pressAt = SystemClock.uptimeMillis()
        // Listening starts with the microphone (WAV) and the preflight; the models load in parallel (D5).
        dictate(listenMs = clipMs + 1_500, listeningTimeoutMs = 30_000)
        val stopAt = SystemClock.uptimeMillis()
        val done = rec.awaitDone(300_000)
        val doneAt = SystemClock.uptimeMillis()
        assertInserted(done, noCleanup = false)

        val inserted = TargetApp.awaitText("non-empty inserted text") { it.isNotBlank() }
        val letters = inserted.filter { it.isLetter() }
        val cyrillic = letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        if (lang == "ru") {
            assertTrue("inserted text is Cyrillic ($cyrillic of ${letters.length} letters)", letters.isNotEmpty() && cyrillic >= 0.9 * letters.length)
        } else {
            assertTrue("inserted text has letters", letters.isNotEmpty())
            E2e.report("$r.latin_share", "%.2f".format((letters.length - cyrillic).toDouble() / letters.length))
        }

        val row = assertEntry(EntryStatus.INSERTED)
        val raw = row.rawText ?: throw AssertionError("the row has no raw transcript")
        val wer = WordErrorRate.wer(reference, raw)
        E2e.report("$r.wer_raw", "%.3f".format(wer))
        val outcome = row.cleanup?.outcome
        E2e.report("$r.cleanup_outcome", outcome ?: "none")
        if (lang == "ru") {
            assertTrue("WER of the raw transcript ${"%.3f".format(wer)} <= 0.20", wer <= 0.20)
            // The guard verdict is the cleanup outcome: accept = cleaned text inserted (not skipped, no fallback).
            assertEquals("cleanup outcome (guard verdict)", "accept", outcome)
        } else {
            // The cleanup ran and the guard judged it (accept or a guard fallback), it was not skipped.
            assertNotNull("guard verdict recorded", outcome)
            assertTrue("cleanup ran, not skipped: $outcome", outcome == "accept" || outcome!!.startsWith("fallback:"))
        }

        val asr = rec.states.mapNotNull { (rec.enginesOf(it.value)?.asr as? AsrReadiness.Ready)?.info }.firstOrNull()
        val llm = rec.states.mapNotNull { (rec.enginesOf(it.value)?.llm as? LlmReadiness.Ready) }.firstOrNull()
        val context = rec.states.mapNotNull { rec.enginesOf(it.value)?.context }.lastOrNull()
        val plan = rec.states.mapNotNull { (it.value as? DictationState.Listening)?.plan }.lastOrNull()
        val listeningAt = rec.states.first { it.value is DictationState.Listening }.atMs
        val asrReadyAt = rec.states.firstOrNull { (rec.enginesOf(it.value)?.asr) is AsrReadiness.Ready }?.atMs
        val llmReadyAt = rec.states.firstOrNull { (rec.enginesOf(it.value)?.llm) is LlmReadiness.Ready }?.atMs
        E2e.report("$r.press_to_listening_ms", listeningAt - pressAt)
        E2e.report("$r.asr_ready_after_press_ms", asrReadyAt?.minus(pressAt) ?: -1)
        E2e.report("$r.llm_ready_after_press_ms", llmReadyAt?.minus(pressAt) ?: -1)
        E2e.report("$r.asr_device", asr?.deviceName ?: "none")
        E2e.report("$r.asr_load_ms", asr?.loadMs ?: -1)
        E2e.report("$r.llm_device", llm?.info?.deviceName ?: "none")
        E2e.report("$r.llm_load_ms", llm?.info?.loadMs ?: -1)
        E2e.report("$r.llm_prefix_tokens", llm?.prefixTokens ?: -1)
        E2e.report("$r.screen_context", when (context) {
            is ContextReadiness.Ready -> "Ready hasScreen=${context.hasScreen} words=${context.contextWords}"
            null -> "none"
            else -> context::class.simpleName ?: "?"
        } + " (plan.screenshot=${plan?.screenshot})")
        // D11 / ARCHITECTURE 16: the screen context comes from the real OCR in :asr (the target shows a Cyrillic line).
        assertEquals("screenshot planned (privacy full, OCR models resolved)", true, plan?.screenshot)
        assertTrue("screen context with text: $context", context is ContextReadiness.Ready && context.hasScreen && context.contextWords > 0)

        // Latency per stage after the stop (stop tap -> text inserted), from the orchestrator's own states.
        fun firstAt(kind: (DictationState) -> Boolean) = rec.states.firstOrNull { it.atMs >= stopAt - 50 && kind(it.value) }?.atMs
        val finishingAt = firstAt { it is DictationState.Finishing }
        val polishingAt = firstAt { it is DictationState.Polishing }
        val deliveringAt = firstAt { it is DictationState.Delivering }
        val doneStateAt = firstAt { it is DictationState.Done } ?: doneAt
        fun span(a: Long?, b: Long?) = if (a != null && b != null) b - a else -1
        E2e.report(
            "$r.stages_ms",
            "stop->finishing=${span(stopAt, finishingAt)} asr_final=${span(finishingAt, polishingAt)} " +
                "cleanup=${span(polishingAt, deliveringAt)} insert=${span(deliveringAt, doneStateAt)} total=${doneStateAt - stopAt}",
        )
        E2e.report("$r.stop_to_done_ms_test", doneAt - stopAt)
        E2e.report("$r.stop_to_done_ms_history", row.timings.stopToDoneMs ?: -1)
        E2e.report("$r.asr_tail_ms", row.timings.asrTailMs ?: -1)
        E2e.report("$r.llm_ms", row.timings.llmMs ?: -1)
        E2e.report("$r.ocr_ms", row.timings.ocrMs ?: -1)
        E2e.report("$r.insert_method", row.insertMethod ?: "none")
        E2e.report("$r.context_words", row.contextWords ?: -1)
        E2e.waitUntil("Idle after the $label dictation", 30_000, pollMs = 100) { rec.state is DictationState.Idle }
        return row
    }
}
