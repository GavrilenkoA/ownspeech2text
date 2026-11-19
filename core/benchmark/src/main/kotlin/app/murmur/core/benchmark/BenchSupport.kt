package app.murmur.core.benchmark

import app.murmur.core.audio.PcmFormat
import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.Outcome

/** What the runner needs to know about the app build and the phone right now (injected by :app). */
interface BenchEnvironment {
    /** BuildConfig.VERSION_NAME: results are saved per app version (DESIGN 6.4). */
    val appVersion: String
    /** Build.FINGERPRINT: results are only comparable on the same firmware. */
    val buildFingerprint: String
    /** PowerManager.getCurrentThermalStatus() as a stable code ("none", "light", "moderate", "severe", ...). */
    fun thermalStatus(): String
}

/**
 * Builds the load specs of one candidate from the catalog and the install state: the same code the dictation
 * resolver uses (WP-13), so the benchmark loads exactly what a dictation would load.
 */
fun interface BenchSpecProvider {
    suspend fun specs(entry: ModelEntry, candidate: Candidate): Outcome<BenchSpecs>
}

data class BenchSpecs(
    val main: LoadSpec,
    /** Batch ASR models: the Silero VAD spec (the production path decodes VAD segments, D4). */
    val vad: LoadSpec? = null,
    /** OCR: the other stage of the pair (the recogniser for a detector entry and vice versa). */
    val ocrPartner: LoadSpec? = null,
    /** LLM: sha256 of the model file, part of the prefix snapshot key ([app.murmur.core.engine.PrefixCacheKey]). */
    val modelSha256: String? = null,
)

data class BenchConfig(
    /** Whole-unit budget; every binder call has its own work-scaled timeout as well (ADR-049). */
    val unitTimeoutMs: Long = 10 * 60_000L,
    /** Synthetic LLM speed test, the AI Hub shape (llm-models.md 10). */
    val syntheticPrompt: Int = 512,
    val syntheticGen: Int = 128,
    val syntheticReps: Int = 1,
    /** Chunk size pushed into AsrSession.accept: the capture path's 20 ms frame. */
    val chunkSamples: Int = PcmFormat.FRAME_SAMPLES,
)

/** Keys of [app.murmur.core.store.BenchmarkRecord.notes]: numbers and codes only, never text (D9). */
object BenchNotes {
    const val THERMAL_BEFORE = "thermal.before"
    const val THERMAL_AFTER = "thermal.after"
    /** Wall time of the whole unit: cold start, passes, unload, memory. */
    const val WALL_MS = "wall.ms"
    const val MEM_VM_HWM_KB = "mem.vmhwm.kb"
    const val MEM_PSS_KB = "mem.pss.kb"
    /** System availMem before the load minus while loaded: DSP buffers that RSS misses (npu-runtime 8). */
    const val MEM_AVAIL_DROP_MB = "mem.avail_drop.mb"
    /** In-process load time reported by the engine (LoadInfo.loadMs); the record's load time also covers process start. */
    const val LOAD_ENGINE_MS = "load.engine.ms"
    const val LLM_PREFIX_TOKENS = "llm.prefix.tokens"
    const val LLM_PREFIX_MS = "llm.prefix.ms"
    const val LLM_PREFIX_RESTORED = "llm.prefix.restored"
    const val LLM_PROMPT_TOKENS = "llm.prompt.tokens"
    const val LLM_GENERATED_TOKENS = "llm.generated.tokens"
    const val LLM_PP_TPS = "llm.pp512.tps"
    const val LLM_TG_TPS = "llm.tg128.tps"
    /** guard.py rule of the fixed case ("ok" or the R-rule that fell back). */
    const val LLM_GUARD_REASON = "llm.guard.reason"
    /** Followed by the page id: "ocr.ms.cover", "ocr.ms.inner". */
    const val OCR_MS_PREFIX = "ocr.ms."
}

internal object Stats {
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of nothing" }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun medianLong(values: List<Long>): Long = Math.round(median(values.map { it.toDouble() }))
}
