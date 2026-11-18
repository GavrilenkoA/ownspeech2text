package app.murmur.core.engine

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import kotlinx.serialization.Serializable

/**
 * Everything an inference process needs to load one artifact. Built in :main from the catalog +
 * install state (ModelResolver); crosses binder as JSON (AIDL carries a String).
 */
@Serializable
data class LoadSpec(
    val kind: ModelKind,
    val modelId: ModelId,
    val artifactId: ArtifactId,
    /** RuntimeKeys value. */
    val runtime: String,
    val unit: ComputeUnit,
    /** Absolute path of the installed artifact directory (filesDir/models/<artifactId>/). */
    val dir: String,
    /** Role -> relative path (catalog Artifact.layout). */
    val layout: Map<String, String>,
    val maxWindowSec: Double? = null,
    /** Runtime knobs (threads, n_ctx, use_mmap, power mode...). Keys: [LoadOptions]. */
    val options: Map<String, String> = emptyMap(),
)

@Serializable
data class LoadInfo(
    val unit: ComputeUnit,
    val loadMs: Long,
    /** What the runtime reports it actually runs on ("HTP0", "CPU", "GPUOpenCL", "qnn", ...). */
    val deviceName: String,
    /** Share of the graph on [unit] when the runtime can tell (llama.cpp op placement), else null. */
    val unitShare: Double? = null,
)

/** Health of one inference process as seen from :main. */
sealed interface HostState {
    data object Down : HostState
    data object Starting : HostState
    data class Up(val pid: Int) : HostState
    /** Died unexpectedly [crashes] times in the current dictation; the client retries once (ARCHITECTURE 4.4). */
    data class Crashed(val crashes: Int) : HostState
}

/** Probe result of one process (cached per app version, feeds catalog DeviceProfile). */
@Serializable
data class ProcessProbe(
    val process: InferProcess,
    val runtimes: Set<String>,
    val units: Set<ComputeUnit>,
    /**
     * Free text facts for the device report (HTP arch, QNN build, OpenCL device...). Never user data. Key
     * [FACT_NATIVE_BUILD] is mandatory once the native runtime is packaged (prefix cache key, ADR-011).
     */
    val facts: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FACT_NATIVE_BUILD = "native.build"
    }
}

@Serializable
data class ProcessMemory(
    /** /proc/self/status VmHWM (peak RSS), kB. */
    val vmHwmKb: Long,
    val vmRssKb: Long,
    /** Debug.getMemoryInfo total PSS, kB. */
    val pssKb: Long,
    /** ActivityManager.MemoryInfo.availMem, MB: proxy for DSP-mapped rpcmem that RSS misses. */
    val systemAvailMb: Long,
)

/**
 * Lifecycle of one inference process (:asr or :llm), implemented over binder in :infer:api.
 * D5: bound on bubble press, idle timeout -> unload -> process exit.
 */
interface InferenceHost {
    val process: InferProcess
    val state: kotlinx.coroutines.flow.StateFlow<HostState>
    /** Bind (starts the process) without loading anything. */
    suspend fun connect(): Boolean
    suspend fun probe(): ProcessProbe
    suspend fun memory(): ProcessMemory
    /** Keep warm for [ms] after the last use, then unload everything and exit (killProcess). */
    fun scheduleIdleExit(ms: Long)
    /** Unload everything and exit now (benchmark cold start, memory pressure, settings change). */
    suspend fun shutdown()
}

/** LoadSpec.options keys (ARCHITECTURE 8.2 / WP-10, WP-06). */
object LoadOptions {
    /** LLM context window = catalog LlmTraits.nCtx (ADR-045). */
    const val N_CTX = "n_ctx"
    const val THREADS = "threads"
    /** "0" for CPU LLMs (ADR-031). */
    const val USE_MMAP = "use_mmap"
    const val POWER_MODE = "power_mode"
    const val KV_TYPE = "kv_type"
    const val N_BATCH = "n_batch"
}
