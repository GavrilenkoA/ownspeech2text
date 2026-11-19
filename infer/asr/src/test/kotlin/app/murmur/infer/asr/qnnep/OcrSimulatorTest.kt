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
 * OCR on the HTP, proven on the x86_64 HTP simulator with the catalog's context artifacts (WP-21 acceptance 1):
 * every model call of the 26 ml/ocr/golden pages runs on both the QNN contexts (through [OrtOcrModels] with the
 * ort-qnn specs, the app's code) and ORT CPU, and the pipeline goes on with the QNN outputs:
 *  - recogniser argmax agreement 1.0 on every call, detector binary map (prob > 0.2) agreement 1.0 on every call
 *    (the spike's parity, asr-ocr.md 7.5);
 *  - the pipeline's final output with the QNN outputs meets the ml/ocr README tolerances (OcrTolerance, ADR-077);
 *  - the padded path (an input shape without its own context) on the 14 cover pages, detector forced into 960x960.
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

    @Test
    fun allGoldenPagesOnTheContextsMatchCpuAndMeetTheReadmeTolerances() {
        val cmp = Comparing(qnnModels, cpuModels)
        val pipeline = OcrPipeline(OcrConfig(), cmp)
        val t0 = System.currentTimeMillis()
        val pages = goldens.map { g ->
            val page = OcrTolerance.compare(g.name, g.ocr.lines, pipeline.run(image(g)).lines)
            println("sim ocr $page | ${cmp.summary()}")
            page
        }
        val suite = OcrTolerance.Suite(pages)
        println("sim ocr suite (${(System.currentTimeMillis() - t0) / 1000} s): $suite")
        println("sim ocr calls: ${cmp.summary()}")
        assertEquals(26, pages.size)
        assertEquals("recogniser argmax disagreements: ${cmp.summary()}", 0L, cmp.recDiffer)
        assertEquals("detector binary-map disagreements: ${cmp.summary()}", 0L, cmp.detDiffer)
        assertTrue(suite.toString(), suite.ok)
    }

    @Test
    fun aShapeWithoutItsOwnContextIsPaddedIntoTheSmallestThatHoldsIt() {
        // the cover pages (960x416) through a detector that has only the 960x960 context; CPU recogniser
        val onlySquare = detQnn.copy(layout = detQnn.layout.filterKeys { it == "ctx.h960w960" })
        OrtOcrModels(onlySquare, recCpu, OcrConfig()) { Simulator.ep }.use { padded ->
            val cmp = Comparing(padded, cpuModels)
            val pipeline = OcrPipeline(OcrConfig(), cmp)
            val cover = goldens.filter { it.detInput == (416 to 960) }
            assertEquals(14, cover.size)
            val suite = OcrTolerance.Suite(cover.map { g -> OcrTolerance.compare(g.name, g.ocr.lines, pipeline.run(image(g)).lines) })
            println("sim ocr padded 416x960 -> 960x960: $suite")
            println("sim ocr padded calls: ${cmp.summary()}")
            assertTrue(suite.toString(), suite.ok)
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
        private lateinit var cpuModels: OrtOcrModels
        private lateinit var goldens: List<OcrGolden>

        @BeforeClass @JvmStatic
        fun open() {
            val t = System.currentTimeMillis()
            qnnModels = OrtOcrModels(detQnn, recQnn, OcrConfig()) { Simulator.ep }
            println("sim ocr contexts open in ${System.currentTimeMillis() - t} ms, ep ${Simulator.ep.status}")
            cpuModels = OrtOcrModels(detCpu, recCpu, OcrConfig())
            goldens = File(Fixtures.repoRoot, "ml/ocr/golden").listFiles { f -> f.name.matches(Regex("\\d{3}\\.json")) }!!
                .sortedBy { it.name }.map { OcrGolden.parse(it.nameWithoutExtension, it.readText()) }
        }

        @AfterClass @JvmStatic
        fun close() {
            qnnModels.close()
            cpuModels.close()
        }

        fun image(g: OcrGolden): ScreenImage {
            val img = ImageIO.read(File(Fixtures.repoRoot, "ml/ocr/golden/${g.name}.png"))
            return g.image(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
    }
}
