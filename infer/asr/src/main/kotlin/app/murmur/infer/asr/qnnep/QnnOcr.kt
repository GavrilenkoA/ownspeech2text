package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.infer.asr.ocr.OrtOcrModels
import java.io.File

/**
 * PP-OCR detector on the HTP: one EPContext session per compiled input shape ([QnnContexts.detector]): the Fold7 cover
 * 960x416 and inner 960x864, both rotations, and 960x960. Any other shape the resize rule can produce (a window capture
 * in split screen) runs on the artifact's own dynamic-shape graph on the CPU EP (layout role "model", the unchanged
 * det.onnx shipped inside the NPU artifact), never padded into a larger context: the detector has global pooling
 * (squeeze-excitation), so padding changes the whole probability map (simulator: all 14 cover pages padded into 960x960
 * left the README tolerance, 5 of them with merged lines). Without that role an uncompiled shape is an error.
 */
class QnnDetector private constructor(
    private val sessions: Map<QnnContexts.Shape, OrtSession>,
    private val other: OrtSession?,
) : OrtOcrModels.Detector {
    val shapes: Set<QnnContexts.Shape> get() = sessions.keys

    /** Calls that ran on the CPU graph because their shape has no context (numbers for the benchmark and tests). */
    @Volatile var cpuCalls = 0
        private set

    override fun detect(input: FloatArray, h: Int, w: Int, run: OrtSession.RunOptions): FloatArray {
        require(input.size == 3 * h * w) { "detector input size" }
        val s = sessions[QnnContexts.Shape(h, w)] ?: other?.also { cpuCalls++ }
            ?: throw IllegalArgumentException("no detector context for ${h}x$w")
        return OrtOcrModels.run(s, input, longArrayOf(1, 3, h.toLong(), w.toLong()), run)
    }

    override fun close() {
        try { closeAll(sessions.values) } finally { other?.close() }
    }

    companion object {
        /**
         * Opens every detector context of [spec] (and its CPU graph with [cpuOptions] when the layout has one); nothing
         * stays open when one fails.
         */
        fun open(spec: LoadSpec, ep: QnnEp, cpuOptions: OrtSession.SessionOptions): QnnDetector {
            ep.requireAvailable()
            val files = QnnContexts.detector(spec.dir, spec.layout)
            require(files.isNotEmpty()) { "no detector contexts in the layout" }
            val sessions = openAll(files, ep)
            try {
                val wrong = sessions.filter { (shape, s) -> inputShape(s) != listOf(1L, 3L, shape.h.toLong(), shape.w.toLong()) }.keys
                require(wrong.isEmpty()) { "detector contexts with another input shape: $wrong" }
                val other = spec.layout[OrtOcrModels.ROLE_MODEL]?.let { OrtOcrModels.session(spec, cpuOptions) }
                return QnnDetector(sessions, other)
            } catch (t: Throwable) {
                closeAll(sessions.values)
                throw t
            }
        }
    }
}

/**
 * PP-OCR recogniser on the HTP: one EPContext session per bucket width of ml/ocr ([QnnContexts.recogniser]), batch 1.
 * Every bucket the pipeline can ask for must have its own context: padding a line into a wider bucket changes the
 * output (the model has global attention over the width, ml/ocr README 5.4), so it is never done.
 */
class QnnRecognizer private constructor(private val sessions: Map<Int, OrtSession>) : OrtOcrModels.Recognizer {
    override val classes: Int = (sessions.values.first().outputInfo.values.first().info as TensorInfo).shape.last().toInt()

    override fun recognize(input: FloatArray, n: Int, bucketWidth: Int, height: Int, run: OrtSession.RunOptions): FloatArray {
        val s = sessions[bucketWidth] ?: throw IllegalArgumentException("no recogniser context for width $bucketWidth")
        val per = 3 * height * bucketWidth
        require(input.size == n * per) { "recogniser input size" }
        if (n == 1) return OrtOcrModels.run(s, input, longArrayOf(1, 3, height.toLong(), bucketWidth.toLong()), run)
        val steps = bucketWidth / 8 * classes
        val out = FloatArray(n * steps)
        for (i in 0 until n) {
            val y = OrtOcrModels.run(s, input.copyOfRange(i * per, (i + 1) * per), longArrayOf(1, 3, height.toLong(), bucketWidth.toLong()), run)
            System.arraycopy(y, 0, out, i * steps, steps)
        }
        return out
    }

    override fun close() = closeAll(sessions.values)

    companion object {
        /** Opens the context of every bucket in [buckets]; a missing one fails the load (nothing stays open). */
        fun open(spec: LoadSpec, ep: QnnEp, buckets: List<Int>, height: Int): QnnRecognizer {
            ep.requireAvailable()
            val files = QnnContexts.recogniser(spec.dir, spec.layout)
            val missing = buckets.filter { it !in files }
            require(missing.isEmpty()) { "no recogniser context for widths $missing" }
            val sessions = openAll(files.filterKeys { it in buckets }, ep)
            val wrong = sessions.filter { (w, s) -> inputShape(s) != listOf(1L, 3L, height.toLong(), w.toLong()) }.keys
            if (wrong.isNotEmpty()) {
                closeAll(sessions.values)
                throw IllegalArgumentException("recogniser contexts with another input shape: $wrong")
            }
            return QnnRecognizer(sessions)
        }
    }
}

/** The fixed input shape an EPContext graph declares. */
internal fun inputShape(s: OrtSession): List<Long> = (s.inputInfo.values.first().info as TensorInfo).shape.toList()

/** Opens one session per file, in key order; on a failure the ones already open are closed. */
internal fun <K> openAll(files: Map<K, File>, ep: QnnEp): Map<K, OrtSession> {
    val open = LinkedHashMap<K, OrtSession>()
    try {
        for ((k, f) in files) {
            require(f.isFile) { "context graph missing" }
            open[k] = ep.open(f)
        }
    } catch (t: Throwable) {
        runCatching { closeAll(open.values) }
        throw t
    }
    return open
}
