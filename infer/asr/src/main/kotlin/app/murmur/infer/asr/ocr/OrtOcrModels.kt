package app.murmur.infer.asr.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrDictionary
import app.murmur.core.ocr.OcrModels
import app.murmur.infer.asr.qnnep.QnnDetector
import app.murmur.infer.asr.qnnep.QnnEp
import app.murmur.infer.asr.qnnep.QnnRecognizer
import java.io.File
import java.nio.FloatBuffer

/**
 * PP-OCR det/rec on ONNX Runtime 1.32, one runner per stage chosen by the artifact's runtime key:
 *  - `ort/ppocr-*` (CPU): one dynamic-shape session on the default CPU EP (no XNNPACK: segfaults, asr-ocr A7).
 *    Session settings equal ml/ocr's: intra-op threads = [OcrConfig.threads], inter-op 1, sequential, graph
 *    optimisation ALL, no spinning.
 *  - `ort-qnn/ppocr-*` (NPU, WP-21): the artifact's EPContext graphs on Qualcomm's QNN plugin EP, one session per
 *    fixed shape, CPU fallback disabled ([QnnDetector]: the context by input shape; [QnnRecognizer]: one per bucket).
 * The two stages are independent (an NPU detector with a CPU recogniser works). Layout roles (catalog): det "model"
 * (CPU) or "ctx.h<H>w<W>" (QNN); rec "model" (CPU) or "ctx.w<W>" (QNN), plus "dict" (inference.yml) for both.
 * Not thread-safe: used only on the OCR thread of [OrtOcrHandler], except [terminateRunning]. [close] releases all.
 *
 * [qnn] resolves the process's QNN EP; it is asked only when a stage is an ort-qnn artifact.
 */
class OrtOcrModels(
    det: LoadSpec,
    rec: LoadSpec,
    config: OcrConfig = OcrConfig(),
    qnn: () -> QnnEp = { throw IllegalStateException("no QNN EP in this process") },
) : OcrModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    private val options = sessionOptions(config)
    private val detector: Detector
    private val recognizer: Recognizer
    override val dictionary: List<String>
    override val classes: Int

    /** The options of the model call in flight, for [terminateRunning] (any thread). */
    @Volatile private var running: OrtSession.RunOptions? = null

    /** Units of the two stages, for LoadInfo. */
    val detUnit: ComputeUnit = det.unit
    val recUnit: ComputeUnit = rec.unit

    init {
        try {
            dictionary = stage(ModelKind.OCR_REC) { OcrDictionary.fromInferenceYml(file(rec, ROLE_DICT).readText()) }
            detector = stage(ModelKind.OCR_DET) {
                if (det.runtime == RuntimeKeys.ORT_QNN_PPOCR_DET) QnnDetector.open(det, qnn()) else CpuStage(session(det, options))
            }
            try {
                recognizer = stage(ModelKind.OCR_REC) {
                    if (rec.runtime == RuntimeKeys.ORT_QNN_PPOCR_REC) QnnRecognizer.open(rec, qnn(), config.recBuckets, config.recHeight)
                    else CpuStage(session(rec, options))
                }
                classes = recognizer.classes
            } catch (t: Throwable) {
                detector.close()
                throw t
            }
        } catch (t: Throwable) {
            options.close()
            throw t
        }
    }

    /** Which artifact failed to load, for MurmurError.EngineLoadFailed(kind) / OutOfMemory(kind) / UnitUnavailable(NPU). */
    class LoadException(val kind: ModelKind, cause: Throwable) : Exception(cause)

    private inline fun <T> stage(kind: ModelKind, block: () -> T): T =
        try { block() } catch (e: Exception) { throw LoadException(kind, e) } catch (e: OutOfMemoryError) { throw LoadException(kind, e) }

    override fun detect(input: FloatArray, h: Int, w: Int): FloatArray = call { detector.detect(input, h, w, it) }

    override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray =
        call { recognizer.recognize(input, n, bucketWidth, REC_HEIGHT, it) }

    /**
     * Stops the model call in flight (ORT checks the flag between kernels; a single QNN graph call runs to its end):
     * the call throws an OrtException. Used when a newer frame supersedes the running one (ADR-069 (4), ADR-077).
     */
    fun terminateRunning() {
        running?.let { r -> runCatching { r.setTerminate(true) } }
    }

    private inline fun call(block: (OrtSession.RunOptions) -> FloatArray): FloatArray =
        OrtSession.RunOptions().use { r ->
            running = r
            try { block(r) } finally { running = null }
        }

    override fun close() {
        try {
            detector.close()
        } finally {
            try { recognizer.close() } finally { options.close() }
        }
    }

    /** One detector of either kind: NCHW [1,3,h,w] -> probability map [h*w]. */
    interface Detector : AutoCloseable {
        fun detect(input: FloatArray, h: Int, w: Int, run: OrtSession.RunOptions): FloatArray
    }

    /** One recogniser of either kind: [n,3,48,bucketWidth] -> softmax [n, bucketWidth/8, classes]. */
    interface Recognizer : AutoCloseable {
        val classes: Int
        fun recognize(input: FloatArray, n: Int, bucketWidth: Int, height: Int, run: OrtSession.RunOptions): FloatArray
    }

    /** A dynamic-shape CPU session serving either stage. */
    private class CpuStage(private val session: OrtSession) : Detector, Recognizer {
        override val classes: Int = (session.outputInfo.values.first().info as TensorInfo).shape.last().toInt()

        override fun detect(input: FloatArray, h: Int, w: Int, run: OrtSession.RunOptions): FloatArray =
            run(session, input, longArrayOf(1, 3, h.toLong(), w.toLong()), run)

        override fun recognize(input: FloatArray, n: Int, bucketWidth: Int, height: Int, run: OrtSession.RunOptions): FloatArray =
            run(session, input, longArrayOf(n.toLong(), 3, height.toLong(), bucketWidth.toLong()), run)

        override fun close() = session.close()
    }

    companion object {
        const val ROLE_MODEL = "model"
        const val ROLE_DICT = "dict"
        private const val REC_HEIGHT = 48

        /**
         * The one place a CPU OCR session is created (the QNN EP sessions: [QnnEp.open], one per context graph).
         * CPU EP: ORT's default provider, nothing to append.
         */
        fun session(spec: LoadSpec, options: OrtSession.SessionOptions): OrtSession =
            OrtEnvironment.getEnvironment().createSession(file(spec, ROLE_MODEL).path, options)

        fun sessionOptions(config: OcrConfig): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            setIntraOpNumThreads(config.threads)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }

        /** Runs [s] on one NCHW float tensor and returns its first output, flat. */
        fun run(s: OrtSession, input: FloatArray, shape: LongArray, options: OrtSession.RunOptions): FloatArray =
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), shape).use { t ->
                s.run(mapOf(s.inputNames.first() to t), options).use { r ->
                    val out = (r.get(0) as OnnxTensor).floatBuffer
                    FloatArray(out.remaining()).also { out.get(it) }
                }
            }

        private fun file(spec: LoadSpec, role: String): File {
            val rel = requireNotNull(spec.layout[role]) { "layout role '$role' missing" }
            val f = File(spec.dir, rel)
            require(f.isFile) { "artifact file for role '$role' missing" }
            return f
        }
    }
}
