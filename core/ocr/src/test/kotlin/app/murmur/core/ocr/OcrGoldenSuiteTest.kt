package app.murmur.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ml/ocr/golden is the parity suite of D11 (owned by ml/ocr; read in place, never copied). */
class OcrGoldenSuiteTest {
    private val goldens = Golden.all()

    @Test
    fun goldenSuiteMatchesThisPipelineVersion() {
        assertEquals(26, goldens.size)
        for (g in goldens) {
            assertEquals(g.name, OcrConfig().pipelineVersion, g.pipeline)
            assertTrue(g.name, g.png.exists())
            assertEquals(g.name, OcrPipelineSizes.detTargetSize(g.ocr.size[0], g.ocr.size[1]), g.detInput)
        }
    }

    /**
     * `stages.rec` of every golden through [OcrPostProcess.process] gives `ocr` back: the same lines in the same order
     * with identical text and box (JSON equality of everything but conf), and identical conf wherever `stages.rec`
     * determines it. The suite stores the rec conf rounded to 4 digits (ml/ocr/golden.py `round(c, 4)`) while `ocr`
     * holds `round(raw, 3)`: when the 4th digit is a 5 the raw value may lie on either side of the 3-digit boundary,
     * and no implementation (the Python reference included: 20/26 pages differ there) can know which. For those
     * lines the golden conf must be one of the two candidates. The full pipeline on the host
     * (OcrHostPipelineTest) has the unrounded conf and is compared exactly.
     */
    @Test
    fun postProcessingReproducesGoldenExactly() {
        var lines = 0
        var undetermined = 0
        var exactPages = 0
        for (g in goldens) {
            val (w, h) = g.ocr.size
            val got = OcrPostProcess.process(g.rec, w, h)
            val want = g.ocr.lines
            assertEquals("${g.name}: texts, boxes, order", want.map { it.text to it.box }, got.map { it.text to it.box })
            // Pair each output line with its stages.rec line (same box) to get the 4-digit conf it came from.
            val c4 = g.rec.associate { listOf(it.rect.x0, it.rect.y0, it.rect.x1, it.rect.y1) to it.conf }
            for ((gl, wl) in got.zip(want)) {
                lines++
                if (gl.conf == wl.conf) continue
                val c = c4.getValue(gl.box)
                val candidates = setOf(
                    OcrPostProcess.roundHalfEven(c - 0.00005, 3),
                    OcrPostProcess.roundHalfEven(c + 0.00005, 3),
                )
                assertTrue("${g.name} ${wl.box}: conf ${gl.conf} vs ${wl.conf} from $c", candidates.size == 2 && wl.conf in candidates)
                undetermined++
            }
            if (got == want) exactPages++
        }
        println("post-processing: ${goldens.size} pages, $lines lines; conf undetermined by stages.rec: $undetermined; " +
            "pages byte-identical as stored: $exactPages")
        assertEquals(578, lines)
    }

    /** rec_keys.txt (the dictionary as exported by ml/ocr) reads back as 850 entries, apostrophe included. */
    @Test
    fun recKeysTextIsTheDictionary() {
        val keys = OcrDictionary.fromKeysText(File(Golden.dir, "rec_keys.txt").readText())
        assertEquals(850, keys.size)
        assertTrue("'" in keys)
        assertEquals("!", keys.first())
    }
}

/** detTargetSize needs no models; a pipeline over a model stub exposes it to tests that have none. */
internal object OcrPipelineSizes {
    private val pipeline = OcrPipeline(OcrConfig(), OcrPipelineSizesModels)

    fun detTargetSize(w: Int, h: Int) = pipeline.detTargetSize(w, h)
}
