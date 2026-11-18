package app.murmur.core.catalog

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.UnitPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
