package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.infer.asr.ocr.OrtOcrModels
import java.io.File

/**
 * PP-OCR detector on the HTP: one EPContext session per compiled input shape ([QnnContexts.detector]). An input of
 * a shape that was compiled runs as is (the Fold7 cover 960x416 and inner 960x864, both rotations, 960x960); any
 * other shape the resize rule can produce (a window capture in split screen: both sides <= 960) is placed at the
 * top-left of the smallest context that contains it, the rest filled with 0 (the mean colour after normalisation),
 * and the probability map is cropped back. Boxes away from the right/bottom edge are unaffected; DEVICE_CHECKLIST
 * DC4 / the simulator suite measure the padded case too.
 */
class QnnDetector private constructor(private val sessions: Map<QnnContexts.Shape, OrtSession>) : OrtOcrModels.Detector {
    val shapes: Set<QnnContexts.Shape> get() = sessions.keys

    override fun detect(input: FloatArray, h: Int, w: Int, run: OrtSession.RunOptions): FloatArray {
        require(input.size == 3 * h * w) { "detector input size" }
        val shape = QnnContexts.pick(sessions.keys, h, w) ?: throw IllegalArgumentException("no detector context holds ${h}x$w")
        val s = sessions.getValue(shape)
        if (shape.h == h && shape.w == w) return OrtOcrModels.run(s, input, longArrayOf(1, 3, h.toLong(), w.toLong()), run)
        val padded = FloatArray(3 * shape.h * shape.w)
        for (c in 0 until 3) for (y in 0 until h) System.arraycopy(input, (c * h + y) * w, padded, (c * shape.h + y) * shape.w, w)
        val map = OrtOcrModels.run(s, padded, longArrayOf(1, 3, shape.h.toLong(), shape.w.toLong()), run)
        val out = FloatArray(h * w)
        for (y in 0 until h) System.arraycopy(map, y * shape.w, out, y * w, w)
        return out
    }

    override fun close() = closeAll(sessions.values)

    companion object {
        /** Opens every detector context of [spec]; nothing stays open when one fails. */
        fun open(spec: LoadSpec, ep: QnnEp): QnnDetector {
            ep.requireAvailable()
            val files = QnnContexts.detector(spec.dir, spec.layout)
            require(files.isNotEmpty()) { "no detector contexts in the layout" }
            return QnnDetector(openAll(files, ep))
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

        private fun inputShape(s: OrtSession): List<Long> = (s.inputInfo.values.first().info as TensorInfo).shape.toList()
    }
}

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
