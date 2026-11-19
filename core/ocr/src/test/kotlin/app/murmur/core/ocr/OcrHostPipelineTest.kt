package app.murmur.core.ocr

import app.murmur.core.model.ModelKind
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        // The pre-processing is bit-exact (BilinearTest) and the detector is ml/ocr's ORT build, so on this x86_64
        // host every box and score is the golden's. A difference means the port drifted (or another CPU's kernels).
        assertEquals("det boxes identical to the golden", total, exact)
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
        // Beyond the tolerance gate: same ORT build as ml/ocr on this host, so the output is the golden byte for byte.
        assertEquals("pages identical to the golden", pages.map { it.name }, pages.filter { it.exact }.map { it.name })
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

    /**
     * README section 5.4: the output must not depend on the batch size (each line is padded to its own bucket).
     * Batch 8 against batch 1 on every golden: the final lines are identical, and so is every raw recogniser line
     * (text, box and the unrounded conf, bit for bit).
     */
    @Test
    fun batchSizeDoesNotChangeTheOutput() {
        val batched = OcrPipeline(OcrConfig(recBatch = 8), models)
        var lines = 0
        var calls1 = 0
        var calls8 = 0
        for ((g, r) in runs) {
            val b = batched.runDetailed(Golden.image(g))
            assertEquals(g.name, r.result.lines, b.result.lines)
            assertEquals(g.name, r.rec, b.rec)
            lines += b.rec.size
            calls1 += r.result.stats!!.recCalls
            calls8 += b.result.stats!!.recCalls
        }
        assertTrue(calls8 < calls1)
        println("batch 8 vs 1: ${runs.size} pages, $lines rec lines identical (conf bit-exact), calls $calls8 vs $calls1")
    }

    /** [OcrRunControl]: every model stage is announced in order, and a cancel stops the run before the next model call. */
    @Test
    fun runControlSeesEveryStageAndStopsTheRun() {
        val (g, ref) = runs.entries.first { it.key.name == "001" }
        val counting = object : OcrModels by models {
            var det = 0
            var rec = 0
            override fun detect(input: FloatArray, h: Int, w: Int) = models.detect(input, h, w).also { det++ }
            override fun recognize(input: FloatArray, n: Int, bucketWidth: Int) = models.recognize(input, n, bucketWidth).also { rec++ }
        }
        val pipeline = OcrPipeline(OcrConfig(), counting)
        class Recorder(val stopAt: ModelKind?) : OcrRunControl {
            val seen = ArrayList<ModelKind>()
            override fun entering(stage: ModelKind) { seen += stage }
            override val cancelled get() = seen.lastOrNull() == stopAt
        }

        val all = Recorder(stopAt = null)
        val out = pipeline.run(Golden.image(g), all)
        assertEquals(ref.result.lines, out.lines)
        assertEquals(listOf(ModelKind.OCR_DET) + List(out.stats!!.recCalls) { ModelKind.OCR_REC }, all.seen)
        assertEquals(1 to out.stats!!.recCalls, counting.det to counting.rec)

        counting.det = 0; counting.rec = 0
        val e = assertThrows(OcrCancelledException::class.java) { pipeline.run(Golden.image(g), Recorder(ModelKind.OCR_REC)) }
        assertEquals(ModelKind.OCR_REC, e.stage)
        assertEquals(1 to 0, counting.det to counting.rec) // the detector ran, no recogniser call

        val first = assertThrows(OcrCancelledException::class.java) { pipeline.run(Golden.image(g), Recorder(ModelKind.OCR_DET)) }
        assertEquals(ModelKind.OCR_DET, first.stage)
        assertEquals(1 to 0, counting.det to counting.rec) // nothing ran
    }

    private fun Rect.list() = listOf(x0, y0, x1, y1)
}
