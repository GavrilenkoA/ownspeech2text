package app.murmur.core.ocr

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * Host JVM end to end: [OcrPipeline.runDetailed] with the desktop ONNX Runtime 1.32 on every golden PNG, compared
 * stage by stage with ml/ocr/golden (det_input, det_boxes, rec, ocr) and held to the README tolerances.
 */
class OcrHostPipelineTest {
    companion object {
        private lateinit var models: HostOrtModels
        private lateinit var runs: Map<OcrGolden, OcrRun>

        @BeforeClass @JvmStatic
        fun runSuite() {
            models = HostOrtModels()
            val pipeline = OcrPipeline(OcrConfig(), models)
            runs = Golden.all().associateWith { pipeline.runDetailed(Golden.image(it)) }
        }

        @AfterClass @JvmStatic
        fun close() = models.close()
    }

    @Test
    fun detectorInputSizeIsExact() {
        for ((g, r) in runs) assertEquals(g.name, g.detInput, r.detInput)
    }

    @Test
    fun detectorBoxesMatchGolden() {
        var exact = 0
        var total = 0
        for ((g, r) in runs) {
            assertEquals("${g.name} det box count", g.detBoxes.size, r.detBoxes.size)
            for ((w, got) in g.detBoxes.zip(r.detBoxes)) {
                total++
                val d = maxOf(abs(w.rect.x0 - got.rect.x0), abs(w.rect.y0 - got.rect.y0), abs(w.rect.x1 - got.rect.x1), abs(w.rect.y1 - got.rect.y1))
                assertTrue("${g.name} ${w.rect} vs ${got.rect}", d <= OcrTolerance.BOX_PX)
                assertEquals("${g.name} score ${w.rect}", w.score, got.score, 0.02)
                if (w.rect == got.rect && w.score == OcrPostProcess.roundHalfEven(got.score, 4)) exact++
            }
        }
        println("det boxes: $exact of $total identical to the golden (box and 4-digit score)")
    }

    @Test
    fun recognisedLinesMatchGolden() {
        val pages = runs.map { (g, r) ->
            assertEquals("${g.name} rec line count", g.rec.size, r.rec.size)
            OcrTolerance.compare(g.name,
                g.rec.map { OcrLine(it.text, it.conf, it.rect.list()) },
                r.rec.map { OcrLine(it.text, OcrPostProcess.roundHalfEven(it.conf, 4), it.rect.list()) })
        }
        println("rec stage (raw text, conf rounded to 4 digits like the golden):\n" + Golden.assertWithin(OcrTolerance.Suite(pages)))
    }

    @Test
    fun finalOutputWithinReadmeTolerances() {
        val pages = runs.map { (g, r) -> OcrTolerance.compare(g.name, g.ocr.lines, r.result.lines) }
        println("ocr (final output):\n" + Golden.assertWithin(OcrTolerance.Suite(pages)))
        for ((g, r) in runs) assertEquals(g.name, g.ocr.size, r.result.size)
    }

    @Test
    fun statsDescribeTheRun() {
        for ((g, r) in runs) {
            val s = r.result.stats!!
            assertEquals(listOf(g.detInput.first, g.detInput.second), s.detInput)
            assertEquals(r.detBoxes.size, s.boxes)
            assertEquals(r.rec.size, s.recLines)
            assertEquals(s.boxes - s.recLines, s.excluded)
            assertEquals(r.rec.size, s.recCalls) // rec_batch 1
            assertTrue(s.detMs > 0 && s.recMs >= 0 && s.totalMs >= s.detMs + s.recMs)
        }
        val ms = runs.entries.joinToString("\n") { (g, r) ->
            val s = r.result.stats!!
            "${g.name} ${g.ocr.size[0]}x${g.ocr.size[1]} det ${s.detInput[0]}x${s.detInput[1]} ${"%.0f".format(s.detMs)} ms, " +
                "rec ${s.recLines} lines ${"%.0f".format(s.recMs)} ms (${"%.1f".format(s.recMsPerLine)}/line), total ${"%.0f".format(s.totalMs)} ms"
        }
        println("host timings (4 threads, shared host):\n$ms")
    }

    /** README section 5.4: the output must not depend on the batch size (each line is padded to its own bucket). */
    @Test
    fun batchSizeDoesNotChangeTheOutput() {
        val batched = OcrPipeline(OcrConfig(recBatch = 8), models)
        for ((g, r) in runs.entries.take(4)) {
            val b = batched.runDetailed(Golden.image(g))
            assertEquals(g.name, r.rec.map { it.text to it.rect }, b.rec.map { it.text to it.rect })
            for ((x, y) in r.rec.zip(b.rec)) assertEquals("${g.name} ${x.rect}", x.conf, y.conf, 1e-4)
            assertTrue(b.result.stats!!.recCalls < r.result.stats!!.recCalls)
        }
    }

    private fun Rect.list() = listOf(x0, y0, x1, y1)
}
