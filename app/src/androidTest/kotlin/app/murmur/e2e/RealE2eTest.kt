package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.benchmark.WordErrorRate
import app.murmur.core.dictation.AsrReadiness
import app.murmur.core.dictation.ContextReadiness
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.LlmReadiness
import app.murmur.core.store.EntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real end-to-end test (WP-20 brief 4, "real"; ADR-063): GigaAM v3 RNN-T int8 + Silero VAD + both OCR models in
 * :asr and Qwen3.5 0.8B Q4_0 in :llm, all on the x86_64 CPU, with a FLEURS ru dev clip played by the WAV capture.
 * tools/e2e/run_emulator_e2e.sh --suite real pushes the pinned models into noBackupFilesDir/models/<artifact>/ (with
 * installed.json), pushes the clip and its reference, and boots the inner AVD with `-qemu -cpu host` and 4 GB.
 * Every number reported here is an emulator number.
 * The test asserts what the brief lists (Cyrillic text, WER <= 20 %, guard verdict accept, audio, idle exit) plus the
 * screen context of D11 (a planned screenshot and OCR text in the cleanup context).
 */
@RunWith(AndroidJUnit4::class)
class RealE2eTest : DictationTest() {
    @Before
    fun realWorld() {
        Pipeline.realWorld()
    }

    /**
     * Two dictations of the clip: the first one cold (both processes start, the LLM builds its prefix: no Preparing ran
     * for pushed models), the second one right after it (warm processes or a restored prefix snapshot), then the idle
     * exit. Each one: Cyrillic text in the target, WER of the raw transcript <= 20 %, guard verdict accept, audio kept.
     */
    @Test
    fun fleursClipIsTranscribedCleanedInsertedAndStoredThenTheProcessesExit() {
        val (clipMs, reference) = Fixtures.installFleurs()
        E2e.report("real.clip_ms", clipMs)
        dictateClip("cold", clipMs, reference)
        val doneAt = dictateClip("warm", clipMs, reference)

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
    }

    /** One dictation of the clip into a fresh target field; returns the uptime of its Done. */
    private fun dictateClip(label: String, clipMs: Long, reference: String): Long {
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
        assertTrue("inserted text is Cyrillic ($cyrillic of ${letters.length} letters)", letters.isNotEmpty() && cyrillic >= 0.9 * letters.length)

        val row = assertEntry(EntryStatus.INSERTED)
        val raw = row.rawText ?: throw AssertionError("the row has no raw transcript")
        val wer = WordErrorRate.wer(reference, raw)
        val r = "real.$label"
        E2e.report("$r.wer_raw", "%.3f".format(wer))
        assertTrue("WER of the raw transcript ${"%.3f".format(wer)} <= 0.20", wer <= 0.20)
        // The guard verdict is the cleanup outcome: accept = cleaned text inserted (not skipped, no fallback).
        assertEquals("cleanup outcome (guard verdict)", "accept", row.cleanup?.outcome)

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
        E2e.report("$r.stop_to_done_ms_test", doneAt - stopAt)
        E2e.report("$r.stop_to_done_ms_history", row.timings.stopToDoneMs ?: -1)
        E2e.report("$r.asr_tail_ms", row.timings.asrTailMs ?: -1)
        E2e.report("$r.llm_ms", row.timings.llmMs ?: -1)
        E2e.report("$r.ocr_ms", row.timings.ocrMs ?: -1)
        E2e.report("$r.context_words", row.contextWords ?: -1)
        E2e.waitUntil("Idle after the $label dictation", 30_000, pollMs = 100) { rec.state is DictationState.Idle }
        return doneAt
    }
}
