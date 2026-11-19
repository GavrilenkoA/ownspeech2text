package app.murmur.infer.llm

import java.io.File

/**
 * Scripted stand-in for libmurmur_llm_jni.so. Tokens = UTF-8 bytes / 4 (rounded up), prefix snapshots are files
 * holding "<tokens>\n<prefix text>" so that load/rebuild paths can be observed (like MLLMST02, a load restores only the
 * exact prefix text). Records every call that matters to the tests.
 */
class FakeLlmBackend(
    var devices: String = "CPU|Intel CPU|cpu;",
    var runtimeInfo: String = "cpu_backend=libggml-cpu-haswell.so;",
    var loadRc: Int = LlmBackend.OK,
) : LlmBackend {
    val calls = mutableListOf<String>()
    var initArgs: List<Any?>? = null
    var lastLoad: Map<String, Any>? = null
    var lastGenerate: Map<String, Any?>? = null
    var lastSuffixBytes: ByteArray? = null
    var cancelFlag = false
    var nextHandle = 7L

    /** What generate streams (each element = one piece, as UTF-8 bytes) and returns. */
    var pieces: List<ByteArray> = listOf("Да".encodeToByteArray(), ", готово.".encodeToByteArray())
    var generateRc = LlmBackend.OK
    var stopReason = 0
    /** Called inside generate between pieces (e.g. to cancel from "another thread"). */
    var duringGenerate: (() -> Unit)? = null
    var prefixLoadRc: Int? = null
    var prefixSetRc: Int? = null
    /** Called inside prefixLoad / bench (e.g. to advance a test clock or to cancel). */
    var duringPrefixLoad: (() -> Unit)? = null
    var duringBench: (() -> Unit)? = null

    fun tokens(b: ByteArray) = (b.size + 3) / 4

    override fun init(nativeLibDir: String, htpLibDir: String?, powerMode: String?, verbose: Int): Int {
        calls += "init"; initArgs = listOf(nativeLibDir, htpLibDir, powerMode, verbose); return LlmBackend.OK
    }
    override fun buildInfo() = "llama.cpp b12520 ff6b8d176186 ggml-hexagon cpu-x86_64"
    override fun runtimeInfo() = runtimeInfo
    override fun devices() = devices

    override fun load(path: String, device: Int, nCtx: Int, nBatch: Int, nThreads: Int, useMmap: Boolean, kvType: Int,
                      out: LongArray, info: DoubleArray): Int {
        calls += "load"
        lastLoad = mapOf("path" to path, "device" to device, "nCtx" to nCtx, "nBatch" to nBatch, "threads" to nThreads,
            "mmap" to useMmap, "kv" to kvType)
        if (loadRc != LlmBackend.OK) return loadRc
        if (device == LlmBackend.DEV_NPU && !devices.contains("HTP")) return LlmBackend.E_DEVICE
        out[0] = nextHandle
        return LlmBackend.OK
    }
    override fun deviceName(h: Long) = "CPU"
    override fun tokenCount(h: Long, utf8: ByteArray): Int { calls += "tokenCount"; return tokens(utf8) }

    override fun prefixSet(h: Long, prefixUtf8: ByteArray, stats: DoubleArray): Int {
        calls += "prefixSet"
        prefixSetRc?.let { return it }
        stats[0] = tokens(prefixUtf8).toDouble(); stats[1] = 1000.0; stats[3] = 4096.0
        lastPrefix = prefixUtf8
        return LlmBackend.OK
    }
    private var lastPrefix = ByteArray(0)
    override fun prefixSave(h: Long, file: String): Int {
        calls += "prefixSave"; File(file).writeText("${tokens(lastPrefix)}\n${lastPrefix.decodeToString()}"); return LlmBackend.OK
    }
    override fun prefixLoad(h: Long, file: String, prefixUtf8: ByteArray, stats: DoubleArray): Int {
        calls += "prefixLoad"
        duringPrefixLoad?.invoke()
        prefixLoadRc?.let { return it }
        val content = File(file).readText()
        val n = content.substringBefore('\n').toIntOrNull() ?: return LlmBackend.E_STATE
        if ('\n' !in content || content.substringAfter('\n') != prefixUtf8.decodeToString()) return LlmBackend.E_STATE
        stats[0] = n.toDouble(); stats[1] = 4.0; stats[3] = File(file).length().toDouble()
        lastPrefix = prefixUtf8
        return LlmBackend.OK
    }

    override fun generate(h: Long, suffixUtf8: ByteArray, maxTokens: Int, temperature: Float, repeatPenalty: Float,
                          stopsUtf8: Array<ByteArray>?, sink: TokenSink, stats: DoubleArray): Int {
        calls += "generate"
        lastSuffixBytes = suffixUtf8
        lastGenerate = mapOf("maxTokens" to maxTokens, "temperature" to temperature,
            "stops" to stopsUtf8?.map { it.decodeToString() })
        var n = 0
        for (p in pieces) {
            if (cancelFlag) { fill(stats, suffixUtf8, n, 3); return LlmBackend.E_CANCELLED }
            n++
            if (!sink.onPiece(p)) { fill(stats, suffixUtf8, n, LlmBackend.STOP_CALLBACK); return LlmBackend.OK }
            duringGenerate?.invoke()
        }
        if (cancelFlag) { fill(stats, suffixUtf8, n, 3); return LlmBackend.E_CANCELLED }
        fill(stats, suffixUtf8, n, stopReason)
        return generateRc
    }
    private fun fill(s: DoubleArray, suffix: ByteArray, n: Int, stop: Int) {
        s[0] = tokens(suffix).toDouble(); s[1] = n.toDouble(); s[2] = 3.0; s[3] = 200.0; s[4] = 250.0; s[5] = 100.0
        s[6] = 400.0; s[7] = stop.toDouble()
    }

    override fun cancel(h: Long) { calls += "cancel"; cancelFlag = true }
    override fun cancelClear(h: Long) { calls += "cancelClear"; cancelFlag = false }
    override fun bench(h: Long, nPrompt: Int, nGen: Int, reps: Int, out: DoubleArray): Int {
        calls += "bench"
        duringBench?.invoke()
        if (cancelFlag) return LlmBackend.E_CANCELLED           // murmur_llm: a cancel in the pp or the tg phase
        out[0] = 300.0; out[1] = 30.0; out[2] = 853.3; out[3] = 1066.7; return LlmBackend.OK
    }
    override fun lastError(h: Long) = ""
    override fun unload(h: Long) { calls += "unload:$h" }
}
