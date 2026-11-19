package app.murmur.e2e

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelId
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.HistoryEntry
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakeLlmEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail

/**
 * Which engines this :main runs: the :testing fakes (debug fake mode, `files/debug-fake-engines`) or the real binder
 * clients of :asr / :llm (CPU engines with real models on the emulator).
 */
object Pipeline {
    val fakes: FakeEngines? get() = E2e.container.engines as? FakeEngines

    fun requireFakes(): FakeEngines = fakes ?: throw AssertionError(
        "debug fake mode is off: tools/e2e/run_emulator_e2e.sh --suite fast creates files/${Fixtures.FAKE_ENGINES_FLAG} " +
            "before the instrumentation starts",
    )

    /**
     * The fake world of the fast suites: fake engines (each cleanup word takes [llmMsPerWord]), the tone-burst WAV as
     * the microphone, and the memory gate ignored (Settings.debugIgnoreMemoryGate, ADR-063: on the 3 GB cover AVD the
     * real gate would skip cleanup as low_memory, which is right for a phone but not what these tests are about).
     */
    fun fakeWorld(llmMsPerWord: Long, wav: ShortArray = Fixtures.toneBurst()): FakeEngines {
        val fakes = requireFakes()
        fakes.llm.delayPerPieceMs = llmMsPerWord
        fakes.llm.hang = false
        // Knobs a test may have set (one instrumentation run shares one :main and so one set of fakes).
        fakes.asr.finishDelayMs = 0
        fakes.asr.loadError = null
        runBlocking { E2e.container.settings.update { it.copy(debugIgnoreMemoryGate = true, debugTimeoutScale = 1f) } }
        Fixtures.installCapture(wav)
        return fakes
    }

    fun requireReal() {
        if (fakes != null) fail("the real suite needs real engines: remove files/${Fixtures.FAKE_ENGINES_FLAG}")
    }

    /** Pinned by the brief (ADR-063): GigaAM RNN-T int8 + VAD + both OCR stages in :asr, Qwen3.5 0.8B Q4_0 in :llm, CPU. */
    const val ASR_MODEL = "gigaam-v3-e2e-rnnt"
    const val LLM_MODEL = "qwen3.5-0.8b"
    val REAL_ARTIFACTS = listOf(
        "gigaam-v3-e2e-rnnt@onnx-int8", "silero-vad-v5@onnx",
        "pp-ocrv6-tiny-det@onnx", "pp-ocrv5-cyrillic-mobile-rec@onnx", "qwen3.5-0.8b@q4_0",
    )

    /**
     * Settings.debugTimeoutScale for the emulator CPU (ADR-049), instrumentation argument `e2e.timeoutScale`. The pushed
     * models never went through Preparing, so the first dictation prefills the 3.1k-token prefix (≈ 50 s here, 1.3-5.5 s
     * on the phone): LLM_READY (10 s after the transcript) × 8 = 80 s covers it on a loaded host.
     */
    val timeoutScale: Float get() = E2e.args.getString("e2e.timeoutScale")?.toFloat() ?: 8f

    /**
     * The real world (tools/e2e/run_emulator_e2e.sh --suite real / events-real pushed the models and the FLEURS clip):
     * the pinned models selected, the LLM on CPU, the screen context on, the memory gate ignored (3-4 GB AVD), timeouts
     * scaled, and the clip as the microphone. The app data was cleared before the models were pushed (the device profile
     * is cached per app version and a rebuilt APK keeps its version), so the first dictation probes like a fresh
     * install. Returns the clip's duration and reference text.
     */
    fun realWorld(): Pair<Long, String> {
        requireReal()
        val models = java.io.File(E2e.context.noBackupFilesDir, "models")
        val missing = REAL_ARTIFACTS.filterNot { java.io.File(models, "$it/installed.json").isFile }
        if (missing.isNotEmpty()) fail("models not pushed: $missing (tools/e2e/run_emulator_e2e.sh --suite real pushes them)")
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
        return Fixtures.installFleurs()
    }

    /** What the fakes insert for the scripted transcript (FakeLlmEngine's deterministic "cleanup"). */
    fun fakeCleaned(): String = FakeLlmEngine.cleanup(requireFakes().asr.script)

    /**
     * A crash of [process] while it works. Real engines: SIGKILL of the process (what `kill -9 $(pidof app.murmur:asr)`
     * does). Fakes: the fake host's crash(), which the fake engines surface exactly as the binder clients surface a
     * dead process (in-flight work fails with ProcessDied, `deaths` emits).
     */
    fun crash(process: InferProcess): String {
        val f = fakes
        return if (f == null) {
            val pid = Processes.kill9(if (process == InferProcess.ASR) Processes.ASR else Processes.LLM)
            "kill -9 pid $pid"
        } else {
            (if (process == InferProcess.ASR) f.asrHost else f.llmHost).crash()
            "fake host crash"
        }
    }

    fun historyEntries(): List<HistoryEntry> = runBlocking { withTimeout(5_000) { E2e.container.history.entries().first() } }

    fun historyCount(): Int = historyEntries().size

    /** The newest history row written after [before] rows existed (rows are newest first). */
    fun awaitNewEntry(before: Int, timeoutMs: Long = 10_000): HistoryEntry {
        E2e.waitUntil("a new history row (had $before)", timeoutMs, pollMs = 100) { historyCount() > before }
        return historyEntries().maxBy { it.createdAt }
    }
}
