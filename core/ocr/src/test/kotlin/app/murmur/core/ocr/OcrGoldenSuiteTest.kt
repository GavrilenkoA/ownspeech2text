package app.murmur.core.ocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/** ml/ocr/golden is the parity suite of D11 (owned by ml/ocr; read in place, never copied). */
class OcrGoldenSuiteTest {
    private val dir = File(System.getProperty("murmur.repoRoot") ?: ".", "ml/ocr/golden")

    @Test
    fun goldenSuiteMatchesThisPipelineVersion() {
        val files = dir.listFiles { f -> f.name.matches(Regex("\\d+\\.json")) }
        assumeTrue("ml/ocr/golden not found", !files.isNullOrEmpty())
        for (f in files!!) {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            assertEquals(f.name, OcrConfig().pipelineVersion, o.getValue("pipeline").jsonPrimitive.content)
            assertTrue(f.name, o.getValue("stages").jsonObject.keys.containsAll(listOf("det_input", "det_boxes", "rec")))
            assertTrue(File(dir, f.name.replace(".json", ".png")).exists())
        }
    }

    @Ignore("WP-07 enables: OcrPostProcess.process(stages.rec) must reproduce `ocr` exactly; full pipeline within README tolerances")
    @Test
    fun postProcessingReproducesGoldenExactly() {
        TODO("WP-07")
    }
}
