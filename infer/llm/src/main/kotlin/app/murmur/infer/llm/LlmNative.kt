package app.murmur.infer.llm

/**
 * The native engine surface [LlmEngine] drives: [LlmNative] (JNI over native/llama/include/murmur_llm.h) on the
 * device, a fake in JVM tests. Contract, array layouts and threading: native/llama/include/murmur_llm_jni.h.
 * Text crosses as UTF-8 byte arrays only (llm spike D11). Every call except [cancel] runs on the engine thread.
 */
interface LlmBackend {
    fun init(nativeLibDir: String, htpLibDir: String?, powerMode: String?, verbose: Int): Int
    fun buildInfo(): String
    fun runtimeInfo(): String
    fun devices(): String
    /** out[0] = handle; info = [load_ms, ctx_ms, n_layers, n_vocab]. */
    fun load(path: String, device: Int, nCtx: Int, nBatch: Int, nThreads: Int, useMmap: Boolean, kvType: Int,
             out: LongArray, info: DoubleArray): Int
    fun deviceName(h: Long): String
    fun tokenCount(h: Long, utf8: ByteArray): Int
    /** stats = [n_tokens, prefill_ms, snapshot_ms, snapshot_bytes]. */
    fun prefixSet(h: Long, prefixUtf8: ByteArray, stats: DoubleArray): Int
    fun prefixSave(h: Long, file: String): Int
    /**
     * Restores the state file only if it holds exactly [prefixUtf8] (else E_STATE).
     * stats = [n_tokens, map_check_restore_ms, snapshot_ms, snapshot_bytes].
     */
    fun prefixLoad(h: Long, file: String, prefixUtf8: ByteArray, stats: DoubleArray): Int
    /** stats = [n_prompt, n_gen, restore_ms, prefill_ms, ttft_ms, decode_ms, total_ms, stop_reason]. */
    fun generate(h: Long, suffixUtf8: ByteArray, maxTokens: Int, temperature: Float, repeatPenalty: Float,
                 stopsUtf8: Array<ByteArray>?, sink: TokenSink, stats: DoubleArray): Int
    fun cancel(h: Long)
    fun cancelClear(h: Long)
    /** out = [pp_tps, tg_tps, pp_ms, tg_ms]. */
    fun bench(h: Long, nPrompt: Int, nGen: Int, reps: Int, out: DoubleArray): Int
    /** Fixed English messages from murmur_llm.cpp (codes and numbers, never user text). */
    fun lastError(h: Long): String
    fun unload(h: Long)

    companion object {
        /** murmur_llm.h return codes. */
        const val OK = 0
        const val E_ARG = -1
        const val E_LOAD = -2
        const val E_DEVICE = -3
        const val E_CTX = -4
        const val E_DECODE = -5
        const val E_CANCELLED = -6
        const val E_STATE = -7
        const val E_OVERFLOW = -8
        const val E_NOMEM = -9

        /** mllm_device. */
        const val DEV_CPU = 0
        const val DEV_NPU = 1
        const val DEV_GPU = 2

        /** mllm_kv_type. */
        const val KV_F16 = 0
        const val KV_Q8_0 = 1

        /** mllm_gen_stats.stop_reason 4: the sink returned false (the binder client went away). */
        const val STOP_CALLBACK = 4
    }
}

fun interface TokenSink {
    /** One complete UTF-8 piece (never a split code point). Return false to stop. Runs on the engine thread. */
    fun onPiece(utf8: ByteArray): Boolean
}

/** JNI binding of libmurmur_llm_jni.so (native/llama/src/murmur_llm_jni.cpp). */
object LlmNative : LlmBackend {
    /** Loads libmurmur_llm_jni.so (and through it libmurmur_llm, libllama, libggml*); null when not packaged. */
    fun loadOrNull(): LlmBackend? = if (loaded) this else null

    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("murmur_llm_jni") }.isSuccess }

    external override fun init(nativeLibDir: String, htpLibDir: String?, powerMode: String?, verbose: Int): Int
    external override fun buildInfo(): String
    external override fun runtimeInfo(): String
    external override fun devices(): String
    external override fun load(path: String, device: Int, nCtx: Int, nBatch: Int, nThreads: Int, useMmap: Boolean,
                               kvType: Int, out: LongArray, info: DoubleArray): Int
    external override fun deviceName(h: Long): String
    external override fun tokenCount(h: Long, utf8: ByteArray): Int
    external override fun prefixSet(h: Long, prefixUtf8: ByteArray, stats: DoubleArray): Int
    external override fun prefixSave(h: Long, file: String): Int
    external override fun prefixLoad(h: Long, file: String, prefixUtf8: ByteArray, stats: DoubleArray): Int
    external override fun generate(h: Long, suffixUtf8: ByteArray, maxTokens: Int, temperature: Float, repeatPenalty: Float,
                                   stopsUtf8: Array<ByteArray>?, sink: TokenSink, stats: DoubleArray): Int
    external override fun cancel(h: Long)
    external override fun cancelClear(h: Long)
    external override fun bench(h: Long, nPrompt: Int, nGen: Int, reps: Int, out: DoubleArray): Int
    external override fun lastError(h: Long): String
    external override fun unload(h: Long)
}
