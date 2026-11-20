package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtSession
import java.io.File

/**
 * The EPContext graphs of one ort-qnn artifact, one per fixed input shape, named by its layout roles
 * (native/qnn-contexts/compile.py writes them, the catalog lists them):
 *   detector   "ctx.h<H>w<W>" -> det_h<H>_w<W>.onnx   (input [1, 3, H, W])
 *   recogniser "ctx.w<W>"     -> rec_w<W>.onnx        (input [1, 3, 48, W])
 * Each graph's context binary (<graph>_qnn.bin) sits next to it and is opened by the EP.
 */
object QnnContexts {
    private val DET = Regex("""ctx\.h(\d+)w(\d+)""")
    private val REC = Regex("""ctx\.w(\d+)""")

    /** (h, w) -> file, for every detector role of [layout]. */
    fun detector(dir: String, layout: Map<String, String>): Map<Shape, File> =
        layout.mapNotNull { (role, path) -> DET.matchEntire(role)?.let { m -> Shape(m.groupValues[1].toInt(), m.groupValues[2].toInt()) to File(dir, path) } }
            .toMap()

    /** width -> file, for every recogniser role of [layout]. */
    fun recogniser(dir: String, layout: Map<String, String>): Map<Int, File> =
        layout.mapNotNull { (role, path) -> REC.matchEntire(role)?.let { m -> m.groupValues[1].toInt() to File(dir, path) } }.toMap()

    data class Shape(val h: Int, val w: Int)
}

/** Closes every session of [sessions]; the first failure is rethrown after all were tried. */
internal fun closeAll(sessions: Collection<OrtSession>) {
    var first: Throwable? = null
    for (s in sessions) try { s.close() } catch (t: Throwable) { if (first == null) first = t }
    first?.let { throw it }
}
