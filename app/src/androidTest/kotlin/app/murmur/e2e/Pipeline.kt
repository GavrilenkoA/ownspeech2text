package app.murmur.e2e

import app.murmur.core.model.InferProcess
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

    fun requireReal() {
        if (fakes != null) fail("the real suite needs real engines: remove files/${Fixtures.FAKE_ENGINES_FLAG}")
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
