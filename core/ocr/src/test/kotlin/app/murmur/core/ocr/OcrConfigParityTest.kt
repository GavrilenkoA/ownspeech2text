package app.murmur.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Drift guard: OcrConfig defaults == ml/ocr/config.py (the porting spec, D11). */
class OcrConfigParityTest {
    private val root = File(System.getProperty("murmur.repoRoot") ?: ".")
    private val py = File(root, "ml/ocr/config.py")

    private fun pyValue(name: String): String {
        val re = Regex("""^\s+$name:\s*\w+\s*=\s*(.+?)\s*(#.*)?$""", RegexOption.MULTILINE)
        return re.find(py.readText())?.groupValues?.get(1) ?: error("$name not in config.py")
    }

    @Test
    fun scalarDefaultsMatchPythonReference() {
        assumeTrue("ml/ocr/config.py not found", py.exists())
        val c = OcrConfig()
        assertEquals(c.detLimit.toString(), pyValue("det_limit"))
        assertEquals(c.detMultiple.toString(), pyValue("det_multiple"))
        assertEquals(c.dbThresh.toString(), pyValue("db_thresh"))
        assertEquals(c.dbBoxThresh.toString(), pyValue("db_box_thresh"))
        assertEquals(c.dbUnclipRatio.toString(), pyValue("db_unclip_ratio"))
        assertEquals(c.recHeight.toString(), pyValue("rec_height"))
        assertEquals(c.recBatch.toString(), pyValue("rec_batch"))
        assertEquals(c.minConf.toString(), pyValue("min_conf"))
        assertEquals(c.minChars.toString(), pyValue("min_chars"))
        assertEquals(c.dedupIou.toString(), pyValue("dedup_iou"))
        assertEquals(c.paneMinAspect.toString(), pyValue("pane_min_aspect"))
        assertEquals(
            c.recBuckets.joinToString(", ", "(", ")"),
            pyValue("rec_buckets"),
        )
        assertEquals("\"${c.pipelineVersion}\"", Regex("""PIPELINE_VERSION = (".+?")""").find(py.readText())!!.groupValues[1])
    }
}
