package app.murmur.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.benchmark.BenchStep
import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.model.ModelId
import app.murmur.core.store.BenchmarkResultData
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Benchmark button on the x86_64 emulator (WP-14's entry point, committed in wave 3 now that WP-10's real :llm is
 * merged; ADR-080, ADR-083): the app's own [app.murmur.AppContainer.benchmarkRunner] (DefaultBenchmarkRunner over the
 * BinderEngines, specs from the production resolver, the Kotlin ports of render.py and guard.py, the exit records) runs
 * the pinned models that `tools/e2e/run_emulator_e2e.sh --suite bench` pushed into noBackupFilesDir/models, on the real
 * :asr and :llm processes. The records are saved into the app's Room store, as a user's run is. Every number is an
 * EMULATOR number. Argument `e2e.benchModels` = comma-separated catalog ids (default: GigaAM RNN-T + Qwen3.5 0.8B).
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkOnEmulatorTest {
    private val c get() = E2e.container

    @Before
    fun realEngines() {
        Pipeline.requireReal()
        // BinderTimeouts and the runner's own budgets scale with it (emulator CPU, ADR-049).
        runBlocking { c.settings.update { it.copy(debugTimeoutScale = Pipeline.timeoutScale) } }
    }

    private fun installed(id: String): Boolean =
        File(E2e.context.noBackupFilesDir, "models/$id/installed.json").isFile

    /** What the hub passes: the candidates of the installed artifacts (BenchmarkRunner.run's contract). */
    private fun candidates(entry: ModelEntry): List<Candidate> = runBlocking {
        ArtifactSelector.candidates(entry, c.deviceProfile.ensure(), c.unitHealth).filter { installed(it.artifact.id.value) }
    }

    private fun entry(id: String): ModelEntry = runBlocking {
        c.catalog.loaded().model(ModelId(id)) ?: throw AssertionError("$id is not in the catalog")
    }

    @Test
    fun benchmarkOfTheInstalledModelsSavesValidCpuRecords() {
        val ids = E2e.args.getString("e2e.benchModels")?.split(',') ?: listOf(Pipeline.ASR_MODEL, Pipeline.LLM_MODEL)
        for (id in ids) {
            val entry = entry(id)
            val candidates = candidates(entry)
            E2e.report("bench.$id.candidates", candidates.joinToString { "${it.artifact.id.value}/${it.unit}/usable=${it.usable}" })
            assertTrue("an installed, usable candidate of $id (models pushed?)", candidates.any { it.usable })
            val t0 = SystemClock.elapsedRealtime()
            val events = runBlocking {
                c.benchmarkRunner.run(entry, candidates).onEach { e ->
                    when (e) {
                        is BenchProgress.Step -> if (e.done) E2e.report("bench.$id.step", "${e.unit} ${e.step} ${e.sampleId ?: "-"} ${e.elapsedMs} ms")
                        is BenchProgress.UnitFailed -> E2e.report("bench.$id.unit_failed", "${e.artifact.value} ${e.unit} ${e.error.code}")
                        is BenchProgress.Failed -> E2e.report("bench.$id.failed", e.error.code)
                        else -> Unit
                    }
                }.toList()
            }
            E2e.report("bench.$id.wall_ms", SystemClock.elapsedRealtime() - t0)
            val finished = events.last() as? BenchProgress.Finished ?: throw AssertionError("$id: ${events.last()}")
            assertTrue("$id: a record per measured unit", finished.records.isNotEmpty())
            for (r in finished.records) {
                when (val d = r.result) {
                    is BenchmarkResultData.Asr -> E2e.report("bench.$id.${r.unit}", "rtfx=${"%.2f".format(d.rtfx)} tailMs=${d.tailMs} loadMsCold=${d.loadMsCold} peakMemMb=${d.peakMemMb} wer=${d.perClip.joinToString { "${it.clip}:${"%.3f".format(it.wer)}" }}")
                    is BenchmarkResultData.Llm -> E2e.report("bench.$id.${r.unit}", "prefillTps=${"%.1f".format(d.prefillTps)} decodeTps=${"%.1f".format(d.decodeTps)} ttftMs=${d.ttftMs.toLong()} e2eMs=${d.e2eMs.toLong()} peakMemMb=${d.peakMemMb} guard=${d.guardAction}")
                    else -> E2e.report("bench.$id.${r.unit}", d::class.simpleName ?: "?")
                }
                assertTrue("$id ${r.unit}: valid record (${r.result.invalidReason})", r.result.valid)
                // Saved where the hub and the resolver read it (Room in this build).
                val stored = runBlocking { c.benchmarks.latest(r.artifactId).first() }
                assertTrue("$id ${r.unit}: the record is in the benchmark store", stored.any { it.unit == r.unit && it.atEpochMs == r.atEpochMs })
                assertTrue("$id ${r.unit}: a valid run proves the unit", !c.unitHealth.failed(r.artifactId, r.unit))
            }
        }
    }

    /** Stop in the middle of the Russian sample: no Finished, and :asr is gone within 5 s (shutdown, ADR-069 item 6). */
    @Test
    fun stopLeavesNoInferenceProcess() {
        val entry = entry(Pipeline.ASR_MODEL)
        var pidWhileRunning: Int? = null
        val events = runBlocking {
            c.benchmarkRunner.run(entry, candidates(entry)).onEach { e ->
                if (e is BenchProgress.Step && e.step == BenchStep.SAMPLE_RU && !e.done) {
                    pidWhileRunning = Processes.pidOf(Processes.ASR)
                    c.benchmarkRunner.stop()
                }
            }.toList()
        }
        E2e.report("bench.stop.pid_while_running", pidWhileRunning ?: -1)
        assertTrue("the :asr process ran", pidWhileRunning != null)
        assertEquals("no Finished after stop", emptyList<BenchProgress>(), events.filterIsInstance<BenchProgress.Finished>())
        E2e.waitUntil(":asr gone after stop", 5_000, pollMs = 100) { Processes.pidOf(Processes.ASR) == null }
        assertNull(Processes.pidOf(Processes.ASR))
    }
}
