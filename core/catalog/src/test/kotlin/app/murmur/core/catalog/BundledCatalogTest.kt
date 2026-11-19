package app.murmur.core.catalog

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.UnitPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCatalogTest {
    private val catalog = CatalogParser.bundled()

    @Test
    fun bundledCatalogIsValid() {
        assertEquals(emptyList<String>(), CatalogValidator.validate(catalog))
    }

    @Test
    fun ownerRequestedFamiliesAreListed() {
        // docs/BRIEF.md "Model hub": Whisper (sizes), Parakeet, GigaAM, T-one, Qwen3-ASR, LFM2.5, LFM3, Qwen3.5, fine-tunes, None.
        val families = catalog.models.map { it.family }.toSet()
        for (f in listOf("whisper", "parakeet", "gigaam", "t-one", "qwen3-asr", "lfm2.5", "lfm3", "qwen3.5", "murmur-cleanup"))
            assertTrue("missing family $f in $families", f in families)
        assertTrue(catalog.models.count { it.family == "whisper" } >= 2)
    }

    @Test
    fun everyKindHasADefault() {
        assertNotNull(catalog.model(catalog.defaults.vad))
        assertEquals(ModelKind.OCR_DET, catalog.model(catalog.defaults.ocrDet)!!.kind)
        assertEquals(ModelKind.OCR_REC, catalog.model(catalog.defaults.ocrRec)!!.kind)
        assertEquals(ModelKind.ASR, catalog.model(catalog.defaults.asr.getValue("ru+en"))!!.kind)
    }

    @Test
    fun emulatorGetsCpuArtifactOfDefaultAsr() {
        val emu = DeviceProfile(null, "x86_64", setOf(ComputeUnit.CPU), RuntimeKeys.known, 3072)
        val entry = catalog.model(catalog.defaults.asr.getValue("ru+en"))!!
        val c = ArtifactSelector.choose(entry, emu, UnitPreference.Auto)
        assertNotNull(c); assertEquals(ComputeUnit.CPU, c!!.unit)
    }

    @Test
    fun fold7PrefersNpuForDefaultAsr() {
        val fold = DeviceProfile("SM8750", "arm64-v8a", setOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU), RuntimeKeys.known, 11_500)
        val entry = catalog.model(catalog.defaults.asr.getValue("ru+en"))!!
        assertEquals(ComputeUnit.NPU, ArtifactSelector.choose(entry, fold, UnitPreference.Auto)!!.unit)
        assertEquals(ComputeUnit.CPU, ArtifactSelector.choose(entry, fold, UnitPreference.Pinned(ComputeUnit.CPU))!!.unit)
    }

    @Test
    fun sherpaLayoutDetection() {
        val (rt, layout) = SherpaLayout.detect(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))!!
        assertEquals(RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER, rt)
        assertEquals("tokens.txt", layout["tokens"])
    }

    @Test
    fun noneIsNotACatalogModel() {
        // "None" (no cleanup) is a UI choice (CleanupChoice.None), not a catalog entry.
        assertTrue(catalog.model(ModelId("none")) == null)
    }

    private val fold = DeviceProfile("SM8750", "arm64-v8a", setOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU), RuntimeKeys.known, 11_500)

    @Test
    fun everyLlmContextHoldsItsPrefixPlusAFiveMinuteDictation() {
        // ADR-045 (review blocker): default.v4 prefix = 3,116-3,256 tokens, so n_ctx 4,096 could not clean up 60 s.
        for (m in catalog.models.filter { it.kind == ModelKind.LLM }) {
            val l = m.llm!!
            val prefix = assertNotNull(m.id.value, l.prefixTokens).let { l.prefixTokens!! }
            assertTrue("${m.id}: nCtx ${l.nCtx} < $prefix + reserve", l.nCtx >= prefix + LlmTraits.CONTEXT_RESERVE_TOKENS)
            assertEquals(GgufTemplates.nCtxFor(prefix), l.nCtx)
        }
        // native/llama/tools/tokenizer_check.py (llama-tokenize on the real GGUFs); a prompt change must update the catalog.
        assertEquals(3_226, catalog.model(ModelId("lfm3-1.7b"))!!.llm!!.prefixTokens)
        assertEquals(3_116, catalog.model(ModelId("qwen3.5-0.8b"))!!.llm!!.prefixTokens)
        assertEquals("finetuned.v1", catalog.model(ModelId("murmur-cleanup-qwen3.5-2b"))!!.llm!!.promptProfile)
        assertTrue(catalog.model(ModelId("murmur-cleanup-qwen3-0.6b")) == null)
    }

    @Test
    fun previewOnlyModelsAreNeverTheSpeechModel() {
        val nemotron = catalog.models.first { it.asr?.previewOnly == true }
        assertTrue(!nemotron.selectableAsAsr)
        catalog.defaults.asr.values.forEach { assertTrue(catalog.model(it)!!.selectableAsAsr) }
    }

    @Test
    fun failedUnitIsSkippedAndTheNextCandidateIsTheFallback() {
        val lfm = catalog.model(catalog.defaults.llm!!)!!
        val ranked = ArtifactSelector.ranked(lfm, fold, UnitPreference.Auto)
        assertEquals(ComputeUnit.NPU, ranked.first().unit)
        val next = ArtifactSelector.fallbackAfter(ranked, ranked.first())!!
        assertEquals(ranked.first().artifact.id, next.artifact.id) // same GGUF on the next unit: no download
        val npuFailed = object : UnitHealth {
            override fun failed(artifact: app.murmur.core.model.ArtifactId, unit: ComputeUnit) = unit == ComputeUnit.NPU
            override fun proven(artifact: app.murmur.core.model.ArtifactId, unit: ComputeUnit) = false
        }
        val c = ArtifactSelector.choose(lfm, fold, UnitPreference.Auto, health = npuFailed)!!
        assertTrue(c.unit != ComputeUnit.NPU)
        assertTrue(ArtifactSelector.candidates(lfm, fold, npuFailed).any { it.blocker == Blocker.UNIT_FAILED })
    }

    @Test
    fun invalidBenchmarksDoNotWinAndPinnedUnitsStillFallBack() {
        val asr = catalog.model(catalog.defaults.asr.getValue("ru+en"))!!
        // Only a valid record may score (BenchLookup contract); a pinned NPU keeps CPU as its fallback.
        val ranked = ArtifactSelector.ranked(asr, fold, UnitPreference.Pinned(ComputeUnit.NPU))
        assertEquals(listOf(ComputeUnit.NPU, ComputeUnit.CPU), ranked.map { it.unit })
        val cpuFaster = BenchLookup { _, u -> if (u == ComputeUnit.CPU) 50.0 else 400.0 }
        assertEquals(ComputeUnit.CPU, ArtifactSelector.choose(asr, fold, UnitPreference.Auto, cpuFaster)!!.unit)
    }

    @Test
    fun ggufArchitectureMapsToTheFourRenderTemplates() {
        assertEquals("qwen3.5", GgufTemplates.templateFor("qwen35"))
        assertEquals("lfm2.5", GgufTemplates.templateFor("lfm2"))
        assertNull(GgufTemplates.templateFor("llama"))
        assertEquals("finetuned.v1", GgufTemplates.profileFor("murmur-cleanup-qwen3.5-0.8b", null))
        assertEquals("default.v5", GgufTemplates.profileFor("murmur-cleanup-x", "default.v5"))
        assertEquals("default.v4", GgufTemplates.profileFor("Qwen3.5-0.8B", null))
    }

    @Test
    fun everyBundledArtifactHasAMemoryEstimate() {
        for (m in catalog.models) for (a in m.artifacts) for (u in a.units)
            assertTrue("${a.id}/$u", MemoryEstimate.mb(m, a, u) > 0 && a.memoryMb[u] != null)
    }
}
