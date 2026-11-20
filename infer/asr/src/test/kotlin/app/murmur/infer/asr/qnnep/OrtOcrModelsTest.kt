package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtException
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrGolden
import app.murmur.core.ocr.OcrPipeline
import app.murmur.core.ocr.OcrTolerance
import app.murmur.core.ocr.ScreenImage
import app.murmur.infer.asr.ocr.OrtOcrHandler
import app.murmur.infer.asr.ocr.OrtOcrModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * WP-21's changes to WP-07's OCR models on the host JVM (desktop ORT 1.32.0, the shared model cache): the CPU path
 * still reproduces ml/ocr/golden exactly through the per-stage runners, an ort-qnn artifact without the QNN EP is
 * UnitUnavailable(NPU), and a superseding frame stops a model call in flight.
 */
class OrtOcrModelsTest {
    private fun spec(artifact: String, kind: ModelKind, model: String, runtime: String, unit: ComputeUnit, layout: Map<String, String>) =
        LoadSpec(kind, ModelId(model), ArtifactId(artifact), runtime, unit, File(Fixtures.modelsDir, artifact).path, layout)

    private val det = spec("pp-ocrv6-tiny-det@onnx", ModelKind.OCR_DET, "pp-ocrv6-tiny-det", RuntimeKeys.ORT_PPOCR_DET, ComputeUnit.CPU,
        mapOf("model" to "det.onnx"))
    private val rec = spec("pp-ocrv5-cyrillic-mobile-rec@onnx", ModelKind.OCR_REC, "pp-ocrv5-cyrillic-mobile-rec", RuntimeKeys.ORT_PPOCR_REC,
        ComputeUnit.CPU, mapOf("model" to "rec.onnx", "dict" to "rec.yml"))

    @Test
    fun theCpuPathStillReproducesTheGoldensExactly() {
        val goldens = goldens()
        assertEquals(26, goldens.size)
        OrtOcrModels(det, rec, OcrConfig()).use { models ->
            val pipeline = OcrPipeline(OcrConfig(), models)
            val suite = OcrTolerance.Suite(goldens.map { g -> OcrTolerance.compare(g.name, g.ocr.lines, pipeline.run(image(g)).lines) })
            println(suite)
            assertTrue(suite.toString(), suite.ok)
            assertEquals("pages byte-identical to ml/ocr (x86_64)", 26, suite.pages.count { it.exact })
        }
    }

    @Test
    fun anOrtQnnArtifactWithoutTheEpIsUnitUnavailableNpu() {
        val qnnDet = det.copy(runtime = RuntimeKeys.ORT_QNN_PPOCR_DET, unit = ComputeUnit.NPU, layout = mapOf("ctx.h960w416" to "det_h960_w416.onnx"))
        val noEp = QnnEp.of(QnnEpLibs.inDir("/nonexistent"))
        assertEquals(QnnEpStatus(false, "no-libs"), noEp.status)
        val e = assertThrows(OrtOcrModels.LoadException::class.java) { OrtOcrModels(qnnDet, rec, OcrConfig()) { noEp } }
        assertEquals(ModelKind.OCR_DET, e.kind)
        assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), OrtOcrHandler.loadError(e))
        // a context that does not open judges the unit with a code; memory stays memory
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.OCR_REC, "qnn:session"),
            OrtOcrHandler.loadError(OrtOcrModels.LoadException(ModelKind.OCR_REC, QnnEpException("session"))))
        assertEquals(MurmurError.OutOfMemory(ModelKind.OCR_DET),
            OrtOcrHandler.loadError(OrtOcrModels.LoadException(ModelKind.OCR_DET, OutOfMemoryError())))
    }

    @Test
    fun loadInfoNamesTheUnitsOfThePair() {
        assertEquals("CPU", OrtOcrHandler.loadInfo(ComputeUnit.CPU, ComputeUnit.CPU, 5).deviceName)
        assertEquals(ComputeUnit.CPU, OrtOcrHandler.loadInfo(ComputeUnit.CPU, ComputeUnit.CPU, 5).unit)
        assertEquals("QNN", OrtOcrHandler.loadInfo(ComputeUnit.NPU, ComputeUnit.NPU, 5).deviceName)
        assertEquals(ComputeUnit.NPU, OrtOcrHandler.loadInfo(ComputeUnit.NPU, ComputeUnit.CPU, 5).unit)
        assertEquals("QNN+CPU", OrtOcrHandler.loadInfo(ComputeUnit.CPU, ComputeUnit.NPU, 5).deviceName)
    }

    @Test
    fun anNpuDetectorCountsTheShapesItRanOnTheCpu() {
        // the routing of QnnDetector over plain CPU sessions: a "context" for 64x96 only, the dynamic graph for the rest
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val model = File(det.dir, "det.onnx").path
        val ctx = env.createSession(model, ai.onnxruntime.OrtSession.SessionOptions())
        val other = env.createSession(model, ai.onnxruntime.OrtSession.SessionOptions())
        QnnDetector.of(mapOf(QnnContexts.Shape(64, 96) to ctx), other).use { d ->
            ai.onnxruntime.OrtSession.RunOptions().use { run ->
                assertEquals(64 * 96, d.detect(FloatArray(3 * 64 * 96), 64, 96, run).size)
                assertEquals("a compiled shape is no CPU call", 0, d.cpuCalls)
                assertEquals(96 * 64, d.detect(FloatArray(3 * 96 * 64), 96, 64, run).size)
                assertEquals(64 * 64, d.detect(FloatArray(3 * 64 * 64), 64, 64, run).size)
                assertEquals("each uncompiled shape is counted", 2, d.cpuCalls)
            }
        }
        val ctxOnly = env.createSession(model, ai.onnxruntime.OrtSession.SessionOptions())
        QnnDetector.of(mapOf(QnnContexts.Shape(64, 96) to ctxOnly), null).use { d ->
            ai.onnxruntime.OrtSession.RunOptions().use { run ->
                assertThrows(IllegalArgumentException::class.java) { d.detect(FloatArray(3 * 64 * 64), 64, 64, run) }
                assertEquals(0, d.cpuCalls)
            }
        }
        // a CPU detector never reports NPU fallbacks
        OrtOcrModels(det, rec, OcrConfig()).use { assertEquals(0, it.detectorCpuCalls) }
    }

    @Test
    fun theTimingLineNamesTheUnitTheDetectorRanOn() {
        val stats = app.murmur.core.ocr.OcrStats(listOf(960, 416), 12.0, 3, 1, 2, 8.0, 1, 21.0)
        val npu = OrtOcrHandler.timing(stats, 2, ComputeUnit.NPU)
        assertTrue(npu, npu.endsWith(" total_ms=21.0 det_unit=npu"))
        assertTrue(npu.startsWith("run det_in=960x416 det_ms=12.0 "))
        assertTrue(OrtOcrHandler.timing(stats, 2, ComputeUnit.CPU).endsWith(" det_unit=cpu"))
    }

    @Test
    fun terminateStopsTheModelCallInFlight() {
        OrtOcrModels(det, rec, OcrConfig(threads = 1)).use { models ->
            // a native-resolution detector call (~1-2 s on one thread) so the flag lands while it runs
            val h = 2176
            val w = 1984
            val input = FloatArray(3 * h * w) { ((it % 97) - 48) / 48f }
            val started = CountDownLatch(1)
            var error: Throwable? = null
            val t = Thread {
                started.countDown()
                try { models.detect(input, h, w) } catch (e: Throwable) { error = e }
            }
            t.start()
            started.await()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // keep asking until the call is in flight (the options exist only while it runs)
            while (t.isAlive && System.nanoTime() < deadline) {
                models.terminateRunning()
                Thread.sleep(5)
            }
            t.join(30_000)
            val e = error ?: throw AssertionError("the detector call finished although it was terminated")
            assertTrue("$e", e is OrtException)
            // the models stay usable: the next call runs normally
            assertEquals(32 * 32, models.detect(FloatArray(3 * 32 * 32), 32, 32).size)
        }
    }

    @Test
    fun aCallStartedAfterTheStopRequestThrowsAtOnce() {
        OrtOcrModels(det, rec, OcrConfig(threads = 1)).use { models ->
            val h = 2176
            val w = 1984
            val input = FloatArray(3 * h * w)
            val full = System.nanoTime().let { t0 -> models.detect(input, h, w); (System.nanoTime() - t0) / 1e6 }
            models.stopRequested = { true }
            val t0 = System.nanoTime()
            val e = runCatching { models.detect(input, h, w) }.exceptionOrNull()
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("$e", e is OrtException)
            assertTrue("stopped call took $ms ms (full call $full ms)", ms < full / 4)
            models.stopRequested = { false }
            assertEquals(h * w, models.detect(input, h, w).size)
        }
    }

    private fun goldens(): List<OcrGolden> {
        val dir = File(Fixtures.repoRoot, "ml/ocr/golden")
        return dir.listFiles { f -> f.name.matches(Regex("\\d{3}\\.json")) }!!.sortedBy { it.name }
            .map { OcrGolden.parse(it.nameWithoutExtension, it.readText()) }
    }

    private fun image(g: OcrGolden): ScreenImage {
        val img = ImageIO.read(File(Fixtures.repoRoot, "ml/ocr/golden/${g.name}.png"))
        return g.image(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }
}
