package app.murmur.infer.llm

import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.SyntheticBench
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The engine of the :llm process, independent of Android so that its rules are JVM-tested with a fake [LlmBackend].
 * [LlmService] owns one instance and calls everything except [cancel] and [nextTicket] on its single engine thread.
 *
 * Rules (WP-10, ARCHITECTURE 4.3, ADR-011/031/045/060):
 *  - load: `n_ctx` comes from the catalog (no default), CPU loads with `use_mmap = 0` unless asked otherwise;
 *    a unit that does not register, or refuses the weights (rpcmem/VTCM), is [MurmurError.UnitUnavailable]; the
 *    fallback to another unit belongs to the caller (ADR-050).
 *  - setPrefix: restore `<dir>/prefix-<cacheKey>.state` when it exists, else prefill + snapshot + save (atomically);
 *    a state file that does not load (stale or truncated) or carries another token count is deleted and rebuilt.
 *  - generate: exact room check after tokenising the suffix; too little room is [MurmurError.LlmContextOverflow]
 *    without any decode; otherwise `max_tokens = min(request.maxTokens, room)`.
 *  - cancel: tickets order requests; a cancel covers the running request and every request already queued.
 * Nothing here logs or stores text (D9).
 */
class LlmEngine(
    /** Null when libmurmur_llm_jni.so is not packaged: every load is [MurmurError.EngineRuntimeMissing]. */
    private val native: LlmBackend?,
    /** Directory of the extracted native libraries (`ApplicationInfo.nativeLibraryDir`, useLegacyPackaging). */
    private val nativeLibDir: String,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Loaded(val handle: Long, val unit: ComputeUnit, val nCtx: Int, val dir: File)

    private sealed interface Prefix {
        data object None : Prefix
        data class Ready(val tokens: Int) : Prefix
        /** The last setPrefix failed half-way: the native snapshot is not usable until a new setPrefix succeeds. */
        data object Broken : Prefix
    }

    private val handleLock = Any()
    @Volatile private var loaded: Loaded? = null
    private var prefix: Prefix = Prefix.None
    private var initRc: Int? = null

    private val tickets = AtomicLong(0)
    @Volatile private var cancelledUpTo = 0L

    /** Called on the binder thread when a cancellable request (setPrefix, generate, bench) arrives. */
    fun nextTicket(): Long = tickets.incrementAndGet()

    /** Any thread. Cancels the running request and every request queued before this call; never races [unload]. */
    fun cancel() {
        synchronized(handleLock) {
            cancelledUpTo = tickets.get()
            loaded?.let { native?.cancel(it.handle) }
        }
    }

    val isLoaded: Boolean get() = loaded != null

    // ------------------------------------------------------------------ probe

    fun probe(): ProcessProbe {
        val n = native ?: return ProcessProbe(InferProcess.LLM, emptySet(), emptySet(), mapOf(FACT_NATIVE to "missing"))
        val rc = ensureInit(n, powerMode = null)
        val facts = linkedMapOf(ProcessProbe.FACT_NATIVE_BUILD to n.buildInfo())
        if (rc != LlmBackend.OK) {
            facts[FACT_NATIVE] = "init_failed:$rc"
            return ProcessProbe(InferProcess.LLM, emptySet(), emptySet(), facts)
        }
        val devices = n.devices()
        facts[FACT_DEVICES] = devices.split(';').filter { it.isNotBlank() }.joinToString(",") { it.substringBefore('|') }
        n.runtimeInfo().split(';').filter { '=' in it }.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2)
            when (k) {
                "cpu_backend" -> facts[FACT_CPU_VARIANT] = v
                "htp_arch" -> facts[FACT_HTP_ARCH] = v
            }
        }
        return ProcessProbe(InferProcess.LLM, setOf(RuntimeKeys.LLAMA_GGUF), unitsOf(devices), facts)
    }

    // ------------------------------------------------------------------ load / unload

    fun load(spec: LoadSpec): Outcome<LoadInfo> {
        if (spec.runtime != RuntimeKeys.LLAMA_GGUF) return err(MurmurError.EngineRuntimeMissing(spec.runtime))
        val n = native ?: return err(MurmurError.EngineRuntimeMissing(RuntimeKeys.LLAMA_GGUF))
        val opts = when (val o = LlmLoadOptions.parse(spec)) {
            is Outcome.Ok -> o.value
            is Outcome.Err -> return o
        }
        val model = File(spec.dir, spec.layout[LAYOUT_MODEL] ?: return loadFailed("layout"))
        if (!model.isFile) return loadFailed("model file missing")
        val initRc = ensureInit(n, opts.powerMode)
        if (initRc != LlmBackend.OK) return loadFailed("init rc=$initRc")
        unload()

        val out = LongArray(1)
        val info = DoubleArray(4)
        val t0 = nanoTime()
        val rc = n.load(model.path, deviceOf(spec.unit), opts.nCtx, opts.nBatch, opts.threads, opts.useMmap, opts.kvType, out, info)
        val loadMs = (nanoTime() - t0) / 1_000_000
        if (rc != LlmBackend.OK || out[0] == 0L) {
            return err(
                when {
                    rc == LlmBackend.E_DEVICE -> MurmurError.UnitUnavailable(spec.unit)
                    // NPU/GPU: the weights or the compute buffers did not fit the unit (rpcmem, VTCM, OpenCL); the
                    // same GGUF may still load on the CPU, so this is the unit's failure, not the model's.
                    spec.unit != ComputeUnit.CPU && (rc == LlmBackend.E_LOAD || rc == LlmBackend.E_CTX) ->
                        MurmurError.UnitUnavailable(spec.unit)
                    else -> MurmurError.EngineLoadFailed(ModelKind.LLM, "load rc=$rc")
                },
            )
        }
        synchronized(handleLock) { loaded = Loaded(out[0], spec.unit, opts.nCtx, File(spec.dir)) }
        prefix = Prefix.None
        return Outcome.Ok(LoadInfo(spec.unit, loadMs, n.deviceName(out[0])))
    }

    fun unload() {
        val l = synchronized(handleLock) { loaded.also { loaded = null } } ?: return
        prefix = Prefix.None
        native?.unload(l.handle)
    }

    // ------------------------------------------------------------------ prefix

    fun setPrefix(ticket: Long, spec: PrefixSpec): Outcome<PrefixInfo> {
        val n = native ?: return err(MurmurError.EngineRuntimeMissing(RuntimeKeys.LLAMA_GGUF))
        val l = loaded ?: return err(NOT_LOADED)
        if (!SAFE_KEY.matches(spec.cacheKey)) return err(MurmurError.Internal("llm.prefix_key"))
        if (!begin(n, l, ticket)) return err(CANCELLED)
        val text = spec.text.encodeToByteArray()
        val file = File(l.dir, "prefix-${spec.cacheKey}.state")
        val stats = DoubleArray(4)

        if (file.isFile) {
            val rc = n.prefixLoad(l.handle, file.path, stats)
            if (rc == LlmBackend.OK) {
                val tokens = stats[0].toInt()
                // The key covers model, prefix text, unit, build and context (ADR-060); the token count is a cheap
                // second check that the file really holds this prefix (a few ms against a multi-second prefill).
                if (tokens == n.tokenCount(l.handle, text)) {
                    prefix = Prefix.Ready(tokens)
                    return Outcome.Ok(PrefixInfo(tokens, stats[1].toLong(), restoredFromDisk = true, bytes = file.length()))
                }
            } else if (rc != LlmBackend.E_STATE) {
                prefix = Prefix.Broken
                return err(if (rc == LlmBackend.E_CANCELLED) CANCELLED else MurmurError.LlmFailed("prefix load rc=$rc"))
            }
            file.delete()           // stale, truncated or not this prefix: rebuild (ADR-060)
        }

        val t0 = nanoTime()
        val rc = n.prefixSet(l.handle, text, stats)
        if (rc != LlmBackend.OK) {
            prefix = Prefix.Broken
            return err(if (rc == LlmBackend.E_CANCELLED) CANCELLED else MurmurError.LlmFailed("prefix rc=$rc"))
        }
        val prefillMs = (nanoTime() - t0) / 1_000_000
        val tokens = stats[0].toInt()
        prefix = Prefix.Ready(tokens)
        save(n, l, file)
        return Outcome.Ok(PrefixInfo(tokens, prefillMs, restoredFromDisk = false, bytes = stats[3].toLong()))
    }

    /** Snapshot to `<file>.tmp`, then rename, so that a crash never leaves a half-written state file behind. */
    private fun save(n: LlmBackend, l: Loaded, file: File) {
        val tmp = File(file.path + ".tmp")
        if (n.prefixSave(l.handle, tmp.path) == LlmBackend.OK && tmp.renameTo(file)) {
            prune(l.dir, keep = file)
        } else {
            tmp.delete()            // the prefix is live in RAM; only the next process start pays the prefill again
        }
    }

    /** Keeps the newest [MAX_STATE_FILES] snapshots per artifact (one per unit); older keys are never used again. */
    private fun prune(dir: File, keep: File) {
        val states = dir.listFiles { f -> f.isFile && f.name.startsWith("prefix-") && (f.name.endsWith(".state") || f.name.endsWith(".state.tmp")) }
            ?: return
        states.filter { it != keep }.sortedByDescending { it.lastModified() }
            .drop(MAX_STATE_FILES - 1).forEach { it.delete() }
    }

    // ------------------------------------------------------------------ generate

    /** Result of one [generate]: [Done] also covers a cancelled generation (stopReason = STOP_CANCELLED). */
    sealed interface Generation {
        data class Done(val text: String, val stats: LlmStats) : Generation
        data class Failed(val error: MurmurError) : Generation
    }

    /**
     * One cleanup. [onPiece] gets every streamed piece on the engine thread and returns false when nobody listens
     * any more (binder client died), which stops the decode.
     */
    fun generate(ticket: Long, request: LlmRequest, onPiece: (String) -> Boolean): Generation {
        val n = native ?: return Generation.Failed(MurmurError.EngineRuntimeMissing(RuntimeKeys.LLAMA_GGUF))
        val l = loaded ?: return Generation.Failed(NOT_LOADED)
        if (request.maxTokens <= 0) return Generation.Failed(MurmurError.Internal("llm.max_tokens"))
        val nPast = when (val p = prefix) {
            Prefix.None -> 0
            is Prefix.Ready -> p.tokens
            Prefix.Broken -> return Generation.Failed(MurmurError.LlmFailed("prefix"))
        }
        if (isCancelled(ticket)) return Generation.Done("", cancelledStats(l.unit))

        val suffix = request.suffix.encodeToByteArray()
        val promptTokens = n.tokenCount(l.handle, suffix)
        if (promptTokens < 0) return Generation.Failed(MurmurError.LlmFailed("tokenize rc=$promptTokens"))
        val room = l.nCtx - nPast - promptTokens
        val minTokens = maxOf(request.minTokens, 1)
        if (room < minTokens) {
            return Generation.Failed(MurmurError.LlmContextOverflow(nPast + promptTokens + minTokens, l.nCtx))
        }
        val maxTokens = minOf(request.maxTokens, room)
        val stops = (request.stop + IM_END).filter { it.isNotEmpty() }.distinct().map { it.encodeToByteArray() }.toTypedArray()

        if (!begin(n, l, ticket)) return Generation.Done("", cancelledStats(l.unit))
        val text = StringBuilder()
        var listening = true
        val stats = DoubleArray(8)
        val rc = n.generate(l.handle, suffix, maxTokens, request.temperature, 1.0f, stops, { utf8 ->
            val piece = utf8.decodeToString()
            text.append(piece)
            listening = onPiece(piece)
            listening
        }, stats)
        return when (rc) {
            LlmBackend.OK, LlmBackend.E_CANCELLED -> {
                val reason = stats[7].toInt()
                // a stop by the sink means the client is gone: report it as a cancellation
                val stop = if (rc == LlmBackend.E_CANCELLED || reason == LlmBackend.STOP_CALLBACK || !listening) LlmStats.STOP_CANCELLED else reason
                Generation.Done(text.toString(), statsOf(stats, stop, l.unit))
            }
            LlmBackend.E_OVERFLOW -> Generation.Failed(MurmurError.LlmContextOverflow(nPast + promptTokens + maxTokens, l.nCtx))
            else -> Generation.Failed(MurmurError.LlmFailed("generate rc=$rc"))
        }
    }

    // ------------------------------------------------------------------ bench

    fun bench(ticket: Long, nPrompt: Int, nGen: Int, reps: Int): Outcome<SyntheticBench> {
        val n = native ?: return err(MurmurError.EngineRuntimeMissing(RuntimeKeys.LLAMA_GGUF))
        val l = loaded ?: return err(NOT_LOADED)
        if (nPrompt <= 0 || nGen <= 0 || reps <= 0 || nPrompt + nGen > l.nCtx) return err(MurmurError.Internal("llm.bench_args"))
        if (!begin(n, l, ticket)) return err(CANCELLED)
        val out = DoubleArray(4)
        return when (val rc = n.bench(l.handle, nPrompt, nGen, reps, out)) {
            LlmBackend.OK -> Outcome.Ok(SyntheticBench(out[0], out[1], out[2], out[3]))
            LlmBackend.E_CANCELLED -> err(CANCELLED)
            else -> err(MurmurError.LlmFailed("bench rc=$rc"))
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * mllm_global_init is process-wide and irreversible: the Hexagon power mode of the first probe or load holds
     * until the process exits (every LoadSpec the app builds uses the default, burst).
     */
    private fun ensureInit(n: LlmBackend, powerMode: String?): Int =
        initRc ?: n.init(nativeLibDir, nativeLibDir, powerMode ?: DEFAULT_POWER_MODE, 0).also { initRc = it }

    private fun isCancelled(ticket: Long) = ticket <= cancelledUpTo

    /**
     * Clears the native cancel flag for [ticket] unless a cancel already covers it. The second check closes the
     * window between the first one and the clear (a cancel there set the flag before we cleared it).
     */
    private fun begin(n: LlmBackend, l: Loaded, ticket: Long): Boolean {
        if (isCancelled(ticket)) return false
        n.cancelClear(l.handle)
        return !isCancelled(ticket)
    }

    private fun statsOf(s: DoubleArray, stop: Int, unit: ComputeUnit) = LlmStats(
        promptTokens = s[0].toInt(), generatedTokens = s[1].toInt(), restoreMs = s[2], prefillMs = s[3],
        ttftMs = s[4], decodeMs = s[5], totalMs = s[6], stopReason = stop, unit = unit,
    )

    private fun cancelledStats(unit: ComputeUnit) = LlmStats(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, LlmStats.STOP_CANCELLED, unit)

    private fun <T> err(e: MurmurError): Outcome<T> = Outcome.Err(e)
    private fun <T> loadFailed(detail: String): Outcome<T> = err(MurmurError.EngineLoadFailed(ModelKind.LLM, detail))

    companion object {
        const val LAYOUT_MODEL = "model"
        const val DEFAULT_POWER_MODE = "burst"
        const val MAX_STATE_FILES = 3
        const val IM_END = "<|im_end|>"

        const val FACT_NATIVE = "native"
        const val FACT_DEVICES = "ggml.devices"
        const val FACT_CPU_VARIANT = "cpu.variant"
        const val FACT_HTP_ARCH = "htp.arch"

        private val SAFE_KEY = Regex("[A-Za-z0-9._-]{1,128}")
        private val NOT_LOADED = MurmurError.Internal("llm.not_loaded")
        private val CANCELLED = MurmurError.LlmFailed("cancelled")

        fun deviceOf(unit: ComputeUnit): Int = when (unit) {
            ComputeUnit.CPU -> LlmBackend.DEV_CPU
            ComputeUnit.NPU -> LlmBackend.DEV_NPU
            ComputeUnit.GPU -> LlmBackend.DEV_GPU
        }

        /** mllm_devices "name|description|type;..." -> units. CPU always; HTP* -> NPU; other GPU devices -> GPU. */
        fun unitsOf(devices: String): Set<ComputeUnit> {
            val units = linkedSetOf<ComputeUnit>()
            devices.split(';').filter { it.isNotBlank() }.forEach { d ->
                val parts = d.split('|')
                val name = parts[0]
                val type = parts.getOrNull(2).orEmpty()
                when {
                    name.startsWith("HTP") -> units += ComputeUnit.NPU
                    type == "gpu" -> units += ComputeUnit.GPU
                    type == "cpu" -> units += ComputeUnit.CPU
                }
            }
            return units
        }
    }
}

/** LoadSpec.options of a llama/gguf artifact ([LoadOptions] keys), validated. */
internal data class LlmLoadOptions(
    val nCtx: Int,
    val threads: Int,
    val nBatch: Int,
    val useMmap: Boolean,
    val kvType: Int,
    val powerMode: String?,
) {
    companion object {
        const val DEFAULT_THREADS = 4
        const val DEFAULT_N_BATCH = 512

        fun parse(spec: LoadSpec): Outcome<LlmLoadOptions> {
            val o = spec.options
            fun bad(key: String): Outcome<LlmLoadOptions> = Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.LLM, "option $key"))
            fun int(key: String, default: Int?): Int? = o[key]?.toIntOrNull()?.takeIf { it > 0 } ?: if (key in o) null else default
            // n_ctx is the catalog's LlmTraits.nCtx (ADR-045): there is no safe default.
            val nCtx = int(LoadOptions.N_CTX, null) ?: return bad(LoadOptions.N_CTX)
            val threads = int(LoadOptions.THREADS, DEFAULT_THREADS) ?: return bad(LoadOptions.THREADS)
            val nBatch = int(LoadOptions.N_BATCH, DEFAULT_N_BATCH) ?: return bad(LoadOptions.N_BATCH)
            val useMmap = when (o[LoadOptions.USE_MMAP] ?: "0") {          // ADR-031: CPU LLMs read, never mmap
                "0", "false" -> false
                "1", "true" -> true
                else -> return bad(LoadOptions.USE_MMAP)
            }
            val kvType = when (o[LoadOptions.KV_TYPE] ?: "f16") {
                "f16" -> LlmBackend.KV_F16
                "q8_0" -> LlmBackend.KV_Q8_0
                else -> return bad(LoadOptions.KV_TYPE)
            }
            val power = o[LoadOptions.POWER_MODE]?.takeIf { POWER_MODE.matches(it) }
                ?: if (LoadOptions.POWER_MODE in o) return bad(LoadOptions.POWER_MODE) else null
            return Outcome.Ok(LlmLoadOptions(nCtx, threads, nBatch, useMmap, kvType, power))
        }

        private val POWER_MODE = Regex("[a-z_]{1,40}")
    }
}
