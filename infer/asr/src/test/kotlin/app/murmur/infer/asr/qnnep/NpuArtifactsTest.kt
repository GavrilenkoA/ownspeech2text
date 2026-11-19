package app.murmur.infer.asr.qnnep

import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.catalog.Blocker
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.FileSource
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.UnitPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ORT QNN EP artifacts in the bundled catalog (WP-21): pinned to the compile manifest, NPU + SM8750 only, never
 * usable where the ort-qnn runtimes are not reported (the emulator, every build without -Pmurmur.qnn), and never the
 * default choice even where they are (ADR-059: off until DC4).
 */
class NpuArtifactsTest {
    private val catalog = CatalogParser.bundled()
    private val manifest = Json.parseToJsonElement(File(Fixtures.repoRoot, "native/qnn-contexts/manifest.json").readText()).jsonObject

    private val npu = mapOf(
        "pp-ocrv6-tiny-det" to ("pp-ocrv6-tiny-det@qnn-sm8750" to RuntimeKeys.ORT_QNN_PPOCR_DET),
        "pp-ocrv5-cyrillic-mobile-rec" to ("pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750" to RuntimeKeys.ORT_QNN_PPOCR_REC),
        "gigaam-v3-e2e-ctc" to ("gigaam-v3-e2e-ctc@qnn-sm8750-20s" to RuntimeKeys.ORT_QNN_GIGAAM_CTC),
    )

    /** The x86_64 emulator as WP-13's probe sees it (ARCHITECTURE 8.4): no SoC, CPU only, the :asr probe without the EP. */
    private val emulator = DeviceProfile(null, "x86_64", setOf(ComputeUnit.CPU), RuntimeKeys.implemented, 3072)
    /** The Fold7 with a shipped build: NPU (sherpa QNN probe) but no QNN EP plugin packaged. */
    private val phoneShipped = DeviceProfile("SM8750", "arm64-v8a", setOf(ComputeUnit.CPU, ComputeUnit.NPU), RuntimeKeys.implemented, 12_000)
    /** The Fold7 with the DC4 build (-Pmurmur.qnn=true, EP registered). */
    private val phoneDc4 = phoneShipped.copy(supportedRuntimes = RuntimeKeys.implemented + RuntimeKeys.qnnEp)

    @Test
    fun theCatalogValidatesAndEveryNewFileIsPinnedToTheBuildManifest() {
        assertEquals(emptyList<String>(), CatalogValidator.validate(catalog))
        val built = manifest.getValue("artifacts").jsonObject
        for ((model, art) in npu) {
            val (id, runtime) = art
            val entry = catalog.model(ModelId(model))!!
            val a = entry.artifacts.single { it.id.value == id }
            assertEquals(runtime, a.runtime)
            assertEquals(listOf(ComputeUnit.NPU), a.units)
            assertEquals(listOf("SM8750"), a.socs)
            assertTrue(a.memoryMb[ComputeUnit.NPU]!! > 0)
            assertTrue("the CPU artifact stays first (the default and the fallback)", entry.artifacts.first().units == listOf(ComputeUnit.CPU))
            val m = built.getValue(id).jsonObject
            val rows = (m["archive"]?.let { listOf(it.jsonObject) } ?: m.getValue("files").jsonArray.map { it.jsonObject })
            assertEquals(rows.map { it.getValue("name").jsonPrimitive.content }, a.files.map { it.name })
            for ((f, r) in a.files.zip(rows)) {
                assertEquals(r.getValue("sha256").jsonPrimitive.content, f.sha256)
                assertEquals(r.getValue("bytes").jsonPrimitive.long, f.bytes)
                assertEquals(FileSource.HUGGINGFACE, f.source)
                assertTrue(f.url, f.url.contains("/resolve/${f.revision}/"))
            }
            // the layout names exactly the graphs that were compiled and checked
            val layout = m.getValue("layout").jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(layout, a.layout)
            assertEquals(entry.kind, RuntimeKeys.kindOf(runtime))
            assertEquals(InferProcess.ASR, RuntimeKeys.process(runtime))
        }
        val g = catalog.model(ModelId("gigaam-v3-e2e-ctc"))!!.artifacts.single { it.runtime == RuntimeKeys.ORT_QNN_GIGAAM_CTC }
        assertEquals(20.0, g.maxWindowSec!!, 0.0)
        assertTrue("a batch runtime: the VAD is queued with it", RuntimeKeys.isBatchAsr(g.runtime))
        val graph = built.getValue(g.id.value).jsonObject.getValue("graphs").jsonArray.single().jsonObject
        assertEquals(2000, graph.getValue("input").jsonObject.getValue("features").jsonArray[2].jsonPrimitive.int)
    }

    @Test
    fun theQnnEpRuntimesAreNeverImpliedWithoutAProbe() {
        assertTrue(RuntimeKeys.qnnEp.all { it in RuntimeKeys.known })
        assertTrue(RuntimeKeys.qnnEp.none { it in RuntimeKeys.implemented })
        assertEquals(ModelKind.OCR_DET, RuntimeKeys.kindOf(RuntimeKeys.ORT_QNN_PPOCR_DET))
        assertEquals(ModelKind.OCR_REC, RuntimeKeys.kindOf(RuntimeKeys.ORT_QNN_PPOCR_REC))
        assertEquals(ModelKind.ASR, RuntimeKeys.kindOf(RuntimeKeys.ORT_QNN_GIGAAM_CTC))
        assertFalse(RuntimeKeys.isBatchAsr(RuntimeKeys.ORT_QNN_PPOCR_DET))
    }

    @Test
    fun onTheEmulatorAndInShippedBuildsTheNpuArtifactsAreNotAvailable() {
        for (device in listOf(emulator, phoneShipped)) {
            for ((model, art) in npu) {
                val entry = catalog.model(ModelId(model))!!
                val c = ArtifactSelector.candidates(entry, device).single { it.artifact.id.value == art.first }
                assertFalse(c.usable)
                assertEquals(Blocker.RUNTIME_NOT_SHIPPED, c.blocker)
                // the CPU path is unchanged: the same choice as without the new artifact
                val chosen = ArtifactSelector.choose(entry, device, UnitPreference.Auto)!!
                assertEquals(ComputeUnit.CPU, chosen.unit)
                assertEquals(entry.artifacts.first().id, chosen.artifact.id)
                assertNull(ArtifactSelector.ranked(entry, device, UnitPreference.Pinned(ComputeUnit.NPU)).firstOrNull { it.artifact.id.value == art.first })
            }
        }
        // a phone of another SoC with the EP: "Needs Snapdragon 8 Elite"
        val other = phoneDc4.copy(soc = "SM8650")
        val det = catalog.model(ModelId("pp-ocrv6-tiny-det"))!!
        assertEquals(Blocker.WRONG_SOC, ArtifactSelector.candidates(det, other).single { it.artifact.id.value == "pp-ocrv6-tiny-det@qnn-sm8750" }.blocker)
    }

    @Test
    fun onTheDc4BuildTheNpuArtifactIsUsableButCpuStaysTheDefaultUntilMeasured() {
        for ((model, art) in npu) {
            val entry = catalog.model(ModelId(model))!!
            val c = ArtifactSelector.candidates(entry, phoneDc4).single { it.artifact.id.value == art.first }
            assertTrue(c.usable)
            assertEquals(entry.artifacts.first().id, ArtifactSelector.choose(entry, phoneDc4, UnitPreference.Auto)!!.artifact.id)
            // pinning the NPU (or a faster valid benchmark) selects it; the CPU artifact is then the fallback
            val pinned = ArtifactSelector.ranked(entry, phoneDc4, UnitPreference.Pinned(ComputeUnit.NPU))
            assertEquals(art.first, pinned.first().artifact.id.value)
            assertEquals(entry.artifacts.first().id, ArtifactSelector.fallbackAfter(pinned, pinned.first())!!.artifact.id)
            val bench = app.murmur.core.catalog.BenchLookup { a, u -> if (u == ComputeUnit.NPU && a.value == art.first) 1.0 else 10.0 }
            assertEquals(art.first, ArtifactSelector.choose(entry, phoneDc4, UnitPreference.Auto, bench)!!.artifact.id.value)
        }
    }
}
