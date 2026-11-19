@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.murmur.core.benchmark

import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.PrefixCacheKey
import app.murmur.core.model.ComputeUnit
import app.murmur.core.ocr.OcrLine
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.prompt.RenderInput
import app.murmur.core.store.BenchmarkResultData
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakePromptRenderer
import app.murmur.testing.FakeOcrService
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmOcrBenchmarkTest {
    @Test
    fun llmFixedCaseThroughTheRealPrefixAndGuard() = runTest {
        val entry = BenchRig.QWEN
        val rig = BenchRig(this)
        val llm = rig.fake.llm
        llm.loadDelayMs = 1_500
        llm.prefixDelayMs = 700
        var prefixKey: String? = null
        val events = rig.runner.run(entry, rig.candidates(entry)).onEach { e -> // emulator profile: CPU only
            if (e is BenchProgress.Step && e.step == BenchStep.CLEANUP_CASE && !e.done) prefixKey = llm.prefix?.cacheKey
        }.toList()

        assertEquals(listOf(BenchStep.LOAD, BenchStep.CLEANUP_CASE, BenchStep.UNLOAD_MEMORY), events.startedSteps())
        val record = (events.last() as BenchProgress.Finished).records.single()
        assertEquals(ComputeUnit.CPU, record.unit)
        assertEquals("qwen3.5-0.8b@q4_0", record.artifactId.value) // the catalog's default artifact
        val r = record.result as BenchmarkResultData.Llm
        assertTrue(r.valid)
        assertEquals("accept", r.guardAction)
        assertEquals(LlmStats.STOP_EOG, r.stopReason)
        // FakeLlmEngine stats: prefill 1 ms, ttft 2 ms, decode 3 ms, total 5 ms.
        assertEquals(5.0, r.e2eMs, 1e-9)
        assertEquals(2.0, r.ttftMs, 1e-9)
        val prompt = record.notes[BenchNotes.LLM_PROMPT_TOKENS]!!.toInt()
        val generated = record.notes[BenchNotes.LLM_GENERATED_TOKENS]!!.toInt()
        assertEquals(prompt * 1000.0, r.prefillTps, 1e-9)
        assertEquals(generated * 1000.0 / 3.0, r.decodeTps, 1e-9)
        assertEquals(1_500L, r.loadMsCold) // connect + load; the prefix prefill is not load time
        assertEquals("1", record.notes[BenchNotes.LLM_PREFIX_MS]) // PrefixInfo.prefillMs as reported by :llm
        assertEquals("false", record.notes[BenchNotes.LLM_PREFIX_RESTORED])
        assertEquals(2_200L, events.steps().single { it.step == BenchStep.LOAD && it.done }.elapsedMs) // load + prefix
        assertEquals("500.0", record.notes[BenchNotes.LLM_PP_TPS])
        assertEquals("50.0", record.notes[BenchNotes.LLM_TG_TPS])
        assertEquals("fake-llm-cpu", r.ranOn)
        assertEquals(1, llm.benches)

        // The real profile prefix with the production snapshot key; warm-up + measured request of the fixed case.
        val case = BenchRig.INPUTS.cleanupCase
        val profile = ClasspathPromptAssets().profile(entry.llm!!.promptProfile)
        val rendered = FakePromptRenderer().render(RenderInput(profile, entry.llm!!.template, case.context, case.transcript))
        val sha = entry.artifacts.first().files.first().sha256
        assertEquals(PrefixCacheKey.of(sha, rendered.prefixKey, ComputeUnit.CPU, "fake-1", entry.llm!!.nCtx), prefixKey)
        assertEquals(2, llm.requests.size)
        assertEquals(rendered.suffix, llm.requests.last().suffix)
        assertTrue(llm.requests.last().maxTokens >= llm.requests.last().minTokens)
        assertNull(llm.loadedUnit) // unloaded
    }

    @Test
    fun llmValidityGate() = runTest {
        val entry = BenchRig.QWEN
        val english = BenchRig(this).also { it.fake.llm.output = { "Yes, Timur already checked the contract." } }
        val r1 = (english.run(entry).last() as BenchProgress.Finished).records.single().result
        assertEquals("wrong_script", r1.invalidReason)

        val truncated = BenchRig(this).also { it.fake.llm.stopReason = LlmStats.STOP_MAX_TOKENS }
        val r2 = (truncated.run(entry).last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Llm
        assertEquals("early_stop", r2.invalidReason)
        assertEquals(LlmStats.STOP_MAX_TOKENS, r2.stopReason)

        val empty = BenchRig(this).also { it.fake.llm.output = { "" } }
        val r3 = (empty.run(entry).last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Llm
        assertEquals("empty", r3.invalidReason)
        assertEquals("fallback", r3.guardAction)

        for (rig in listOf(english, truncated, empty)) {
            assertNull(BenchScores.lookup(rig.saved(entry)).score(entry.artifacts.first().id, ComputeUnit.CPU))
            assertNull(BenchScores.llmSpeed(rig.saved(entry), entry.artifacts.first().id, ComputeUnit.CPU))
        }
    }

    @Test
    fun ocrPagesAreTimedAndCheckedAgainstTheirGoldens() = runTest {
        val entry = BenchRig.OCR_DET
        val ocr = FakeOcrService()
        val rig = BenchRig(this, FakeEngines(ocr = ocr))
        val pages = BenchRig.INPUTS.pages.associateBy { it.id }
        val events = rig.runner.run(entry, rig.candidates(entry)).onEach { e ->
            // A correct engine: returns each page's golden lines (cover in 300 ms, inner in 600 ms).
            if (e is BenchProgress.Step && e.step == BenchStep.OCR_PAGE && !e.done) {
                ocr.lines = pages.getValue(e.sampleId!!).golden.lines
                ocr.delayMs = if (e.sampleId == "cover") 300 else 600
            }
        }.toList()

        assertEquals(listOf(BenchStep.LOAD, BenchStep.OCR_PAGE, BenchStep.OCR_PAGE, BenchStep.UNLOAD_MEMORY), events.startedSteps())
        assertEquals(listOf("cover", "inner"), events.steps().filter { it.step == BenchStep.OCR_PAGE && !it.done }.map { it.sampleId })
        val record = (events.last() as BenchProgress.Finished).records.single()
        val r = record.result as BenchmarkResultData.Ocr
        assertTrue(r.invalidReason, r.valid)
        assertEquals(450L, r.msPerScreenshot) // median of 300 and 600
        assertEquals(pages.values.sumOf { it.golden.lines.size }, r.boxes)
        assertEquals("300", record.notes[BenchNotes.OCR_MS_PREFIX + "cover"])
        assertEquals("600", record.notes[BenchNotes.OCR_MS_PREFIX + "inner"])
        assertEquals(4, ocr.calls) // warm-up + measured per page
    }

    @Test
    fun ocrThatMissesTheGoldenIsInvalid() = runTest {
        val entry = BenchRig.model("pp-ocrv5-cyrillic-mobile-rec") // the recogniser entry runs the same pair
        val wrong = BenchRig(this, FakeEngines(ocr = FakeOcrService(lines = listOf(OcrLine("Лёша Козлов", 0.97, listOf(156, 212, 498, 268))))))
        val r = (wrong.run(entry).last() as BenchProgress.Finished).records.single().result
        assertFalse(r.valid)
        assertEquals("ocr_line_count", r.invalidReason)

        val blind = BenchRig(this, FakeEngines(ocr = FakeOcrService(lines = emptyList())))
        val r2 = (blind.run(entry).last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Ocr
        assertEquals("ocr_no_boxes", r2.invalidReason)
        assertEquals(0, r2.boxes)
    }
}
