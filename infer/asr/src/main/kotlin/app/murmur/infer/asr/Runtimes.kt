package app.murmur.infer.asr

import app.murmur.core.audio.VadModel
import app.murmur.core.engine.LoadSpec

/**
 * Runtime adapters of the :asr process. sherpa-onnx's Kotlin API (com.k2fsa.sherpa.onnx.*, Apache-2.0) is
 * vendored by WP-06 under src/main/kotlin/com/k2fsa/sherpa/onnx/ because libsherpa-onnx-jni.so binds JNI
 * symbols to those class names. provider = "qnn" for ComputeUnit.NPU, else "cpu".
 */
interface AsrRuntime {
    val key: String
    fun load(spec: LoadSpec)
    fun close()
}

/** Silero VAD v5 through the ONNX Runtime Java API (state [2,1,128], 512-sample frames). WP-06. */
class SileroVad(private val spec: LoadSpec) : VadModel {
    override fun probability(frame: FloatArray): Float = TODO("WP-06")
    override fun reset() = TODO("WP-06")
}
