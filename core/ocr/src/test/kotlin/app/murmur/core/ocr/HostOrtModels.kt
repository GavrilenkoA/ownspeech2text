package app.murmur.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** The OCR artifacts in the shared model cache (tools/models/fetch; catalog layout det.onnx / rec.onnx / rec.yml). */
object TestModels {
    const val DET = "pp-ocrv6-tiny-det@onnx"
    const val REC = "pp-ocrv5-cyrillic-mobile-rec@onnx"
    private val dir = File(System.getProperty("murmur.modelsDir") ?: ".cache/models")

    val detModel get() = file(DET, "det.onnx")
    val recModel get() = file(REC, "rec.onnx")
    val recYml get() = file(REC, "rec.yml")

    private fun file(artifact: String, name: String): File {
        val f = File(File(dir, artifact), name)
        check(f.isFile) { "$f missing: run tools/models/fetch $DET $REC" }
        return f
    }
}

/**
 * [OcrModels] on the desktop ONNX Runtime (host tests only), configured like ml/ocr's sessions and like
 * :infer:asr's OrtOcrModels: CPU EP, intra-op [threads], inter-op 1, sequential, graph optimisation ALL, no spinning.
 */
class HostOrtModels(threads: Int = 4) : OcrModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    private val options = OrtSession.SessionOptions().apply {
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }
    private val det = env.createSession(TestModels.detModel.path, options)
    private val rec = env.createSession(TestModels.recModel.path, options)

    override val dictionary: List<String> = OcrDictionary.fromInferenceYml(TestModels.recYml.readText())
    override val classes: Int =
        (rec.outputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape.last().toInt()

    override fun detect(input: FloatArray, h: Int, w: Int): FloatArray = run(det, input, longArrayOf(1, 3, h.toLong(), w.toLong()))

    override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray =
        run(rec, input, longArrayOf(n.toLong(), 3, 48, bucketWidth.toLong()))

    private fun run(s: OrtSession, input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val out = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }
}
