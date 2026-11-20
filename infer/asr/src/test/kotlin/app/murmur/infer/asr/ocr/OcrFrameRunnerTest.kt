package app.murmur.infer.asr.ocr

import ai.onnxruntime.OrtException
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.ocr.OcrCancelledException
import app.murmur.core.ocr.OcrConfig
import app.murmur.infer.asr.qnnep.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-21's "newest frame wins" inside a model call, on real ORT CPU calls: a native-resolution detector call on one
 * thread takes over a second here, so a stop that waits for the call to end is told apart from one inside it.
 */
class OcrFrameRunnerTest {
    private val det = LoadSpec(ModelKind.OCR_DET, ModelId("pp-ocrv6-tiny-det"), ArtifactId("pp-ocrv6-tiny-det@onnx"),
        RuntimeKeys.ORT_PPOCR_DET, ComputeUnit.CPU, File(Fixtures.modelsDir, "pp-ocrv6-tiny-det@onnx").path, mapOf("model" to "det.onnx"))
    private val rec = LoadSpec(ModelKind.OCR_REC, ModelId("pp-ocrv5-cyrillic-mobile-rec"), ArtifactId("pp-ocrv5-cyrillic-mobile-rec@onnx"),
        RuntimeKeys.ORT_PPOCR_REC, ComputeUnit.CPU, File(Fixtures.modelsDir, "pp-ocrv5-cyrillic-mobile-rec@onnx").path,
        mapOf("model" to "rec.onnx", "dict" to "rec.yml"))

    private val h = 2176
    private val w = 1984
    private val input = FloatArray(3 * h * w) { ((it % 97) - 48) / 48f }

    private class TestFrame(queued: Boolean) : OcrFrameRunner.Frame {
        private val owned = AtomicBoolean(!queued)
        @Volatile override var superseded = false
            private set

        override fun markSuperseded() { superseded = true }
        override fun claim() = owned.compareAndSet(false, true)
    }

    private fun models() = OrtOcrModels(det, rec, OcrConfig(threads = 1))

    private fun fullCallMs(models: OrtOcrModels): Double {
        val t0 = System.nanoTime()
        models.detect(input, h, w)
        return (System.nanoTime() - t0) / 1e6
    }

    @Test
    fun aRunningFrameIsStoppedInsideItsModelCall() {
        models().use { models ->
            val full = fullCallMs(models)
            val runner = OcrFrameRunner()
            val frame = TestFrame(queued = false)
            val inCall = CountDownLatch(1)
            var outcome: Throwable? = null
            var endedAt = 0L
            val t = Thread {
                try {
                    runner.run(models, frame) { inCall.countDown(); models.detect(input, h, w) }
                } catch (e: Throwable) {
                    outcome = e
                }
                endedAt = System.nanoTime()
            }
            t.start()
            assertTrue(inCall.await(10, TimeUnit.SECONDS))
            Thread.sleep(full.toLong() / 5) // well inside the call
            val t0 = System.nanoTime()
            assertFalse("a running frame is not claimed by the newer one", runner.supersede(frame))
            t.join(30_000)
            val stopMs = (endedAt - t0) / 1e6
            println("frame runner: full detector call ${"%.0f".format(full)} ms, stopped ${"%.0f".format(stopMs)} ms after supersede")
            assertTrue("$outcome", outcome is OcrFrameRunner.Superseded)
            assertTrue("stopped $stopMs ms after supersede (full call $full ms)", stopMs < full / 4)
            // the models stay usable and the next frame runs normally
            assertEquals(32 * 32, runner.run(models, TestFrame(queued = false)) { models.detect(FloatArray(3 * 32 * 32), 32, 32) }.size)
        }
    }

    @Test
    fun aFrameSupersededBeforeItsNextCallDoesNotRunIt() {
        models().use { models ->
            val full = fullCallMs(models)
            val runner = OcrFrameRunner()
            val frame = TestFrame(queued = false)
            val t0 = System.nanoTime()
            val e = runCatching {
                runner.run(models, frame) {
                    runner.supersede(frame) // between two calls: nothing in flight to terminate
                    models.detect(input, h, w)
                }
            }.exceptionOrNull()
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("$e", e is OcrFrameRunner.Superseded)
            assertTrue("the superseded frame's call took $ms ms (full call $full ms)", ms < full / 4)
        }
    }

    @Test
    fun aQueuedFrameIsLeftToTheCallerAndOtherFailuresStayWhatTheyAre() {
        models().use { models ->
            val runner = OcrFrameRunner()
            val queued = TestFrame(queued = true)
            assertTrue("a queued frame is claimed by the newer one", runner.supersede(queued))
            assertTrue(queued.superseded)
            // a stop between stages (the pipeline's own check) is the same answer
            val stage = runCatching { runner.run(models, TestFrame(queued = false)) { throw OcrCancelledException(ModelKind.OCR_REC) } }
            assertTrue("${stage.exceptionOrNull()}", stage.exceptionOrNull() is OcrFrameRunner.Superseded)
            // a model error of a frame nobody superseded is not turned into "superseded"
            val bad = runCatching { runner.run(models, TestFrame(queued = false)) { models.detect(FloatArray(5), 1, 5) } }.exceptionOrNull()
            assertTrue("$bad", bad is OrtException || bad is IllegalArgumentException)
            assertFalse(bad is OcrFrameRunner.Superseded)
            val boom = IllegalStateException("x")
            assertSame(boom, runCatching { runner.run(models, TestFrame(queued = false)) { throw boom } }.exceptionOrNull())
            // after any run the models no longer stop calls
            assertEquals(32 * 32, models.detect(FloatArray(3 * 32 * 32), 32, 32).size)
        }
    }
}
