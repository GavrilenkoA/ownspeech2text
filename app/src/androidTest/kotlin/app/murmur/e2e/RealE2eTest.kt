package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.benchmark.WordErrorRate
import app.murmur.core.dictation.AsrReadiness
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.LlmReadiness
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.EntryStatus
import kotlinx.coroutines.runBlocking
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
 */
@RunWith(AndroidJUnit4::class)
class RealE2eTest : DictationTest() {
    private val timeoutScale: Float get() = E2e.args.getString("e2e.timeoutScale")?.toFloat() ?: 4f

    @Before
    fun realWorld() {
        Pipeline.requireReal()
        val models = java.io.File(E2e.context.noBackupFilesDir, "models")
        val missing = ARTIFACTS.filterNot { java.io.File(models, "$it/installed.json").isFile }
        assertTrue("models not pushed: $missing (tools/e2e/run_emulator_e2e.sh --suite real pushes them)", missing.isEmpty())
        runBlocking {
            E2e.container.settings.update {
                it.copy(
                    asrModel = ModelId(ASR_MODEL),
                    cleanup = CleanupChoice.Model(ModelId(LLM_MODEL)),
                    unitPreference = it.unitPreference + (LLM_MODEL to UnitPreference.Pinned(ComputeUnit.CPU)),
                    useScreen = true,
                    keepRecordings = true,
                    idleUnloadSec = 60,
                    debugIgnoreMemoryGate = true,
                    debugTimeoutScale = timeoutScale,
                )
            }
        }
    }

    @Test
    fun fleursClipIsTranscribedCleanedInsertedAndStoredThenTheProcessesExit() {
        val (clipMs, reference) = Fixtures.installFleurs()
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
        E2e.report("real.wer_raw", "%.3f".format(wer))
        assertTrue("WER of the raw transcript ${"%.3f".format(wer)} <= 0.20", wer <= 0.20)
        // The guard verdict is the cleanup outcome: accept = cleaned text inserted (not skipped, no fallback).
        assertEquals("cleanup outcome (guard verdict)", "accept", row.cleanup?.outcome)

        val asr = rec.states.mapNotNull { (rec.enginesOf(it.value)?.asr as? AsrReadiness.Ready)?.info }.firstOrNull()
        val llm = rec.states.mapNotNull { (rec.enginesOf(it.value)?.llm as? LlmReadiness.Ready) }.firstOrNull()
        val listeningAt = rec.states.first { it.value is DictationState.Listening }.atMs
        val asrReadyAt = rec.states.firstOrNull { (rec.enginesOf(it.value)?.asr) is AsrReadiness.Ready }?.atMs
        val llmReadyAt = rec.states.firstOrNull { (rec.enginesOf(it.value)?.llm) is LlmReadiness.Ready }?.atMs
        E2e.report("real.clip_ms", clipMs)
        E2e.report("real.press_to_listening_ms", listeningAt - pressAt)
        E2e.report("real.asr_ready_after_press_ms", asrReadyAt?.minus(pressAt) ?: -1)
        E2e.report("real.llm_ready_after_press_ms", llmReadyAt?.minus(pressAt) ?: -1)
        E2e.report("real.asr_device", asr?.deviceName ?: "none")
        E2e.report("real.asr_load_ms", asr?.loadMs ?: -1)
        E2e.report("real.llm_device", llm?.info?.deviceName ?: "none")
        E2e.report("real.llm_load_ms", llm?.info?.loadMs ?: -1)
        E2e.report("real.llm_prefix_tokens", llm?.prefixTokens ?: -1)
        E2e.report("real.stop_to_done_ms_test", doneAt - stopAt)
        E2e.report("real.stop_to_done_ms_history", row.timings.stopToDoneMs ?: -1)
        E2e.report("real.asr_tail_ms", row.timings.asrTailMs ?: -1)
        E2e.report("real.llm_ms", row.timings.llmMs ?: -1)
        E2e.report("real.ocr_ms", row.timings.ocrMs ?: -1)
        E2e.report("real.context_words", row.contextWords ?: -1)

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

    companion object {
        /** Pinned by the brief (ADR-063); tools/e2e pushes exactly these artifacts. */
        const val ASR_MODEL = "gigaam-v3-e2e-rnnt"
        const val LLM_MODEL = "qwen3.5-0.8b"
        val ARTIFACTS = listOf(
            "gigaam-v3-e2e-rnnt@onnx-int8", "silero-vad-v5@onnx",
            "pp-ocrv6-tiny-det@onnx", "pp-ocrv5-cyrillic-mobile-rec@onnx", "qwen3.5-0.8b@q4_0",
        )
    }
}
