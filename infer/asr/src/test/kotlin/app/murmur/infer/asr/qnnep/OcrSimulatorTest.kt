package app.murmur.infer.asr.qnnep

import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrGolden
import app.murmur.core.ocr.OcrModels
import app.murmur.core.ocr.OcrPipeline
import app.murmur.core.ocr.OcrTolerance
import app.murmur.core.ocr.ScreenImage
import app.murmur.infer.asr.ocr.OrtOcrModels
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * OCR on the HTP on the x86_64 HTP simulator with the catalog's context artifacts (WP-21 acceptance 1). Every model call
 * runs on the QNN contexts (through [OrtOcrModels] with the ort-qnn specs, the app's code) and on ORT CPU; the pipeline
 * goes on with the QNN output. The acceptance criteria are asserted as the brief states them (the spike's parity,
 * asr-ocr.md 7.5, and the ml/ocr README tolerances, ADR-077), one stage at a time and both together:
 *  - [theDetectorContextsReproduceTheCpuBinaryMap]: QNN detector + CPU recogniser, binary map (prob > 0.2) agreement 1.0;
 *  - [theRecogniserContextsReproduceTheCpuArgmax]: CPU detector + QNN recogniser, argmax agreement 1.0, README tolerance;
 *  - [thePipelineOnTheContextsMeetsTheReadmeTolerances]: both on QNN (~90 min on the simulator);
 *  - [aShapeWithoutAContextRunsTheArtifactsCpuGraph]: the detector's fallback for window shapes that were not compiled.
 * Measured 2026-11-20 (WP-21 report): the fp16 detector does NOT reproduce the CPU binary map (1,867 of 15.5 M pixels
 * differ, max |d| 0.16). With the CPU recogniser, 15 of 26 pages leave the README tolerance: 12 by box offsets of 3-7 px
 * or conf drift (001 006 008 009 012 013 016 017 022 023 024 026), 3 with merged or shifted lines (004 015 020). The
 * fp16 recogniser keeps 577/578 lines identical but drifts conf by 0.026 on 010 and 011. So all three QNN tests fail on
 * the simulator; they stay the adoption gate that DEVICE_CHECKLIST DC4 repeats on the phone (ADR-059).
 * Excluded from the normal unit tests (needs -Pmurmur.qnnSim / -Pmurmur.qnnContexts, see [Simulator]).
 */
class OcrSimulatorTest {
    /** Per-call comparison of the QNN outputs with ORT CPU; the pipeline continues with the QNN output. */
    class Comparing(private val qnn: OcrModels, private val cpu: OcrModels) : OcrModels by qnn {
        var detCalls = 0; var detPixels = 0L; var detDiffer = 0L; var detMaxAbs = 0.0; var detMs = 0L
        var recCalls = 0; var recSteps = 0L; var recDiffer = 0L; var recMaxAbs = 0.0; var recMs = 0L

        override fun detect(input: FloatArray, h: Int, w: Int): FloatArray {
            val t = System.currentTimeMillis()
            val q = qnn.detect(input, h, w)
            detMs += System.currentTimeMillis() - t
            val c = cpu.detect(input, h, w)
            detCalls++
            for (i in q.indices) {
                detPixels++
                if ((q[i] > THRESH) != (c[i] > THRESH)) detDiffer++
                detMaxAbs = maxOf(detMaxAbs, Math.abs(q[i] - c[i]).toDouble())
            }
            return q
        }

        override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray {
            val t = System.currentTimeMillis()
            val q = qnn.recognize(input, n, bucketWidth)
            recMs += System.currentTimeMillis() - t
            val c = cpu.recognize(input, n, bucketWidth)
            recCalls++
            val k = qnn.classes
            for (s in 0 until q.size / k) {
                recSteps++
                if (argmax(q, s * k, k) != argmax(c, s * k, k)) recDiffer++
            }
            for (i in q.indices) recMaxAbs = maxOf(recMaxAbs, Math.abs(q[i] - c[i]).toDouble())
            return q
        }

        fun summary(): String = String.format(Locale.ROOT,
            "det calls=%d binmap_agree=%.6f (%d of %d px differ) max|d|=%.4f sim_ms/call=%d; rec calls=%d argmax_agree=%.6f (%d of %d steps) max|d|=%.4f sim_ms/call=%d",
            detCalls, 1.0 - detDiffer.toDouble() / maxOf(1, detPixels), detDiffer, detPixels, detMaxAbs, detMs / maxOf(1, detCalls),
            recCalls, 1.0 - recDiffer.toDouble() / maxOf(1, recSteps), recDiffer, recSteps, recMaxAbs, recMs / maxOf(1, recCalls))

        private fun argmax(a: FloatArray, from: Int, n: Int): Int {
            var b = 0
            for (i in 1 until n) if (a[from + i] > a[from + b]) b = i
            return b
        }

        companion object { const val THRESH = 0.2f }
    }

    /** Runs every golden page through [models] (compared with CPU) and returns the README suite. */
    private fun suite(label: String, models: OcrModels): Pair<OcrTolerance.Suite, Comparing> {
        val cmp = Comparing(models, cpuModels)
        val pipeline = OcrPipeline(OcrConfig(), cmp)
        val cpuPipeline = OcrPipeline(OcrConfig(), cpuModels)
        val t0 = System.currentTimeMillis()
        val pages = goldens.map { g ->
            val run = pipeline.runDetailed(image(g))
            val boxesEqual = run.detBoxes.map { it.rect } == cpuPipeline.runDetailed(image(g)).detBoxes.map { it.rect }
            OcrTolerance.compare(g.name, g.ocr.lines, run.result.lines).also { println("sim ocr [$label] $it det_boxes_equal_cpu=$boxesEqual") }
        }
        val suite = OcrTolerance.Suite(pages)
        println("sim ocr [$label] (${(System.currentTimeMillis() - t0) / 1000} s) ${suite.toString().lines().drop(pages.size).joinToString(" ")}")
        println("sim ocr [$label] calls: ${cmp.summary()}")
        return suite to cmp
    }

    @Test
    fun theDetectorContextsReproduceTheCpuBinaryMap() {
        val (suite, cmp) = suite("qnn det + cpu rec", detQnnRecCpu)
        assertEquals(26, suite.pages.size)
        assertEquals("detector binary-map disagreements: ${cmp.summary()}", 0L, cmp.detDiffer)
        assertTrue(suite.toString(), suite.ok)
    }

    @Test
    fun theRecogniserContextsReproduceTheCpuArgmax() {
        val (suite, cmp) = suite("cpu det + qnn rec", detCpuRecQnn)
        assertEquals(26, suite.pages.size)
        assertEquals("recogniser argmax disagreements: ${cmp.summary()}", 0L, cmp.recDiffer)
        assertTrue(suite.toString(), suite.ok)
    }

    @Test
    fun thePipelineOnTheContextsMeetsTheReadmeTolerances() {
        val (suite, cmp) = suite("qnn det + qnn rec", qnnModels)
        assertEquals(cmp.summary(), 0L, cmp.recDiffer + cmp.detDiffer)
        assertTrue(suite.toString(), suite.ok)
    }

    @Test
    fun aShapeWithoutAContextRunsTheArtifactsCpuGraph() {
        // the cover pages (960x416) through a detector artifact that only has the inner contexts: its own det.onnx runs
        val innerOnly = detQnn.copy(layout = detQnn.layout.filterKeys { it == "model" || it == "ctx.h960w864" || it == "ctx.h864w960" })
        val cover = goldens.filter { it.detInput == (416 to 960) }
        assertEquals(14, cover.size)
        OrtOcrModels(innerOnly, recCpu, OcrConfig()) { Simulator.ep }.use { models ->
            val pipeline = OcrPipeline(OcrConfig(), models)
            val pages = cover.map { g -> OcrTolerance.compare(g.name, g.ocr.lines, pipeline.run(image(g)).lines) }
            println("sim ocr [uncompiled shape -> det.onnx on CPU] ${OcrTolerance.Suite(pages).toString().lines().last()}")
            assertEquals("the CPU graph reproduces ml/ocr exactly", 14, pages.count { it.exact })
            assertEquals("every cover page is counted as a CPU detector call", 14, models.detectorCpuCalls)
        }
        // without the CPU graph an uncompiled shape is an error, never a padded guess
        OrtOcrModels(innerOnly.copy(layout = innerOnly.layout - "model"), recCpu, OcrConfig()) { Simulator.ep }.use { models ->
            val e = runCatching { models.detect(FloatArray(3 * 960 * 416), 960, 416) }.exceptionOrNull()
            assertTrue("$e", e is IllegalArgumentException)
        }
    }

    companion object {
        private val detQnn get() = Simulator.spec("pp-ocrv6-tiny-det@qnn-sm8750")
        private val recQnn get() = Simulator.spec("pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750")
        private val detCpu = LoadSpec(ModelKind.OCR_DET, ModelId("pp-ocrv6-tiny-det"), ArtifactId("pp-ocrv6-tiny-det@onnx"),
            RuntimeKeys.ORT_PPOCR_DET, ComputeUnit.CPU, File(Fixtures.modelsDir, "pp-ocrv6-tiny-det@onnx").path, mapOf("model" to "det.onnx"))
        private val recCpu = LoadSpec(ModelKind.OCR_REC, ModelId("pp-ocrv5-cyrillic-mobile-rec"), ArtifactId("pp-ocrv5-cyrillic-mobile-rec@onnx"),
            RuntimeKeys.ORT_PPOCR_REC, ComputeUnit.CPU, File(Fixtures.modelsDir, "pp-ocrv5-cyrillic-mobile-rec@onnx").path,
            mapOf("model" to "rec.onnx", "dict" to "rec.yml"))

        private lateinit var qnnModels: OrtOcrModels
        private lateinit var detQnnRecCpu: OrtOcrModels
        private lateinit var detCpuRecQnn: OrtOcrModels
        private lateinit var cpuModels: OrtOcrModels
        private lateinit var goldens: List<OcrGolden>

        @BeforeClass @JvmStatic
        fun open() {
            val t = System.currentTimeMillis()
            qnnModels = OrtOcrModels(detQnn, recQnn, OcrConfig()) { Simulator.ep }
            println("sim ocr contexts open in ${System.currentTimeMillis() - t} ms, ep ${Simulator.ep.status}")
            detQnnRecCpu = OrtOcrModels(detQnn, recCpu, OcrConfig()) { Simulator.ep }
            detCpuRecQnn = OrtOcrModels(detCpu, recQnn, OcrConfig()) { Simulator.ep }
            cpuModels = OrtOcrModels(detCpu, recCpu, OcrConfig())
            goldens = File(Fixtures.repoRoot, "ml/ocr/golden").listFiles { f -> f.name.matches(Regex("\\d{3}\\.json")) }!!
                .sortedBy { it.name }.map { OcrGolden.parse(it.nameWithoutExtension, it.readText()) }
        }

        @AfterClass @JvmStatic
        fun close() {
            listOf(qnnModels, detQnnRecCpu, detCpuRecQnn, cpuModels).forEach { it.close() }
        }

        fun image(g: OcrGolden): ScreenImage {
            val img = ImageIO.read(File(Fixtures.repoRoot, "ml/ocr/golden/${g.name}.png"))
            return g.image(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
    }
}
