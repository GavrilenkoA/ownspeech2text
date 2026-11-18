package app.murmur.infer.llm

/**
 * Kotlin side of native/llama/include/murmur_llm_jni.h (WP-10 builds the shim in native/llama, not in Gradle).
 * Text crosses JNI as UTF-8 byte arrays only (llm spike D11). All calls except [cancel] on the engine thread.
 */
object LlmNative {
    @Volatile private var loaded = false

    /** Loads libmurmur_llm_jni.so; false when the prebuilt set is not packaged (EngineRuntimeMissing). */
    fun ensureLoaded(): Boolean {
        if (!loaded) loaded = runCatching { System.loadLibrary("murmur_llm_jni") }.isSuccess
        return loaded
    }

    external fun init(nativeLibDir: String, htpLibDir: String?, powerMode: String?, verbose: Int): Int
    external fun devices(): String
    external fun load(path: String, device: Int, nCtx: Int, nThreads: Int, prefixMode: Int, out: LongArray): Int
    external fun prefixSet(h: Long, prefixUtf8: ByteArray, stats: DoubleArray): Int
    external fun prefixSave(h: Long, file: String): Int
    external fun prefixLoad(h: Long, file: String, stats: DoubleArray): Int
    external fun generate(h: Long, suffixUtf8: ByteArray, maxTokens: Int, temperature: Float, repeatPenalty: Float,
                          stops: Array<String>?, sink: TokenSink, stats: DoubleArray): Int
    external fun cancel(h: Long)
    external fun bench(h: Long, nPrompt: Int, nGen: Int, reps: Int, out: DoubleArray): Int
    external fun unload(h: Long)

    /** murmur_llm.h mllm_device. */
    const val DEV_CPU = 0
    const val DEV_NPU = 1
    const val DEV_GPU = 2
}

fun interface TokenSink {
    /** Return false to stop. Runs on the engine thread: only post to a channel. */
    fun onPiece(utf8: ByteArray): Boolean
}
