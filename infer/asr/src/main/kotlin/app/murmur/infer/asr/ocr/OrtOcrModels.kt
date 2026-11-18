package app.murmur.infer.asr.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ModelKind
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrDictionary
import app.murmur.core.ocr.OcrModels
import java.io.File
import java.nio.FloatBuffer

/**
 * PP-OCR det/rec ONNX sessions on ORT 1.32 default CPU EP (no XNNPACK: segfaults, asr-ocr A7; no QNN here: WP-21).
 * Session settings equal ml/ocr's: intra-op threads = [OcrConfig.threads], inter-op 1, sequential, graph optimisation
 * ALL, no spinning. Layout roles (catalog): det "model"; rec "model" + "dict" (inference.yml).
 * Not thread-safe: used only on the OCR thread of [OrtOcrHandler]. [close] releases both sessions.
 */
class OrtOcrModels(det: LoadSpec, rec: LoadSpec, config: OcrConfig = OcrConfig()) : OcrModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    private val options = sessionOptions(config)
    private val detSession: OrtSession
    private val recSession: OrtSession
    override val dictionary: List<String>
    override val classes: Int

    init {
        try {
            dictionary = stage(ModelKind.OCR_REC) { OcrDictionary.fromInferenceYml(file(rec, ROLE_DICT).readText()) }
            detSession = stage(ModelKind.OCR_DET) { session(det, options) }
            try {
                recSession = stage(ModelKind.OCR_REC) { session(rec, options) }
                classes = (recSession.outputInfo.values.first().info as TensorInfo).shape.last().toInt()
            } catch (t: Throwable) {
                detSession.close()
                throw t
            }
        } catch (t: Throwable) {
            options.close()
            throw t
        }
    }

    /** Which artifact failed to load, for MurmurError.EngineLoadFailed(kind) / OutOfMemory(kind). */
    class LoadException(val kind: ModelKind, cause: Throwable) : Exception(cause)

    private inline fun <T> stage(kind: ModelKind, block: () -> T): T =
        try { block() } catch (e: Exception) { throw LoadException(kind, e) } catch (e: OutOfMemoryError) { throw LoadException(kind, e) }

    override fun detect(input: FloatArray, h: Int, w: Int): FloatArray =
        run(detSession, input, longArrayOf(1, 3, h.toLong(), w.toLong()))

    override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray =
        run(recSession, input, longArrayOf(n.toLong(), 3, REC_HEIGHT, bucketWidth.toLong()))

    private fun run(s: OrtSession, input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val out = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }

    override fun close() {
        detSession.close()
        recSession.close()
        options.close()
    }

    companion object {
        const val ROLE_MODEL = "model"
        const val ROLE_DICT = "dict"
        private const val REC_HEIGHT = 48L

        /**
         * The one place an OCR session is created (WP-21 adds the QNN EP options here, after DEVICE_CHECKLIST DC4).
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

        private fun file(spec: LoadSpec, role: String): File {
            val rel = requireNotNull(spec.layout[role]) { "layout role '$role' missing" }
            val f = File(spec.dir, rel)
            require(f.isFile) { "artifact file for role '$role' missing" }
            return f
        }
    }
}
