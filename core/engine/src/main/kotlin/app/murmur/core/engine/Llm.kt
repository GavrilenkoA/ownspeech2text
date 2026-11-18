package app.murmur.core.engine

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Cleanup LLM in the :llm process (llama.cpp + ggml-hexagon, ADR-009). Text crosses binder and JNI as
 * UTF-8 bytes (llm spike D11). One engine thread; [cancel] is the only cross-thread call and never races
 * unload (llm spike JNI rule).
 */
interface LlmEngine {
    suspend fun load(spec: LoadSpec): Outcome<LoadInfo>
    /**
     * Prefill the request-independent prefix (system + preferences + few-shot turns) and snapshot it.
     * Restores a persisted snapshot when [PrefixSpec.cacheKey] matches (ADR-011). Call right after load.
     */
    suspend fun setPrefix(prefix: PrefixSpec): Outcome<PrefixInfo>
    /** One cleanup: restore prefix -> prefill suffix -> greedy decode. Cold flow; collecting starts it. */
    fun generate(request: LlmRequest): Flow<LlmEvent>
    fun cancel()
    suspend fun unload()
    /** Benchmark button (WP-14): synthetic prefill/decode speed of the loaded model (binder timeout BinderTimeouts.benchMs). */
    suspend fun bench(nPrompt: Int, nGen: Int, reps: Int): Outcome<SyntheticBench>
}

@Serializable
data class SyntheticBench(val ppTps: Double, val tgTps: Double, val ppMs: Double, val tgMs: Double)

@Serializable
data class PrefixSpec(
    val text: String,
    /** [PrefixCacheKey.of]; names the persisted state file `prefix-<cacheKey>.state` (ADR-011). */
    val cacheKey: String,
)

/**
 * The persisted prefix snapshot is valid only for the exact model file, prefix text, unit, native runtime build and
 * context parameters (review 2026-11-19): any of them changing must miss the cache. Built in :main (the renderer
 * gives [prefixSha] = sha256(prefix text); the probe gives [nativeBuildId]). On a state-file load error
 * (murmur_llm MLLM_E_STATE) :llm deletes the file, prefills again and saves (WP-10).
 */
object PrefixCacheKey {
    fun of(modelSha256: String, prefixSha256: String, unit: ComputeUnit, nativeBuildId: String, nCtx: Int,
           kvType: String = "f16", nBatch: Int = 512): String =
        sha256Hex(listOf(modelSha256, prefixSha256, unit.name, nativeBuildId, nCtx, kvType, nBatch).joinToString("|"))

    fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

@Serializable
data class PrefixInfo(val tokens: Int, val prefillMs: Long, val restoredFromDisk: Boolean, val bytes: Long)

@Serializable
data class LlmRequest(
    val suffix: String,
    /** Output cap (CleanupBudget.maxTokens). :llm clamps it to n_ctx - n_past - prompt tokens. */
    val maxTokens: Int,
    /**
     * Minimum output room (CleanupBudget.minTokens, about 1.1 x transcript tokens). After exact tokenisation, if
     * n_ctx - n_past - prompt tokens < minTokens, :llm answers Failed(LlmContextOverflow) without decoding.
     */
    val minTokens: Int = 0,
    val stop: List<String> = emptyList(),
    /** <= 0 = greedy (the only mode the app uses; CONTEXT_AND_PROMPT 7.6). */
    val temperature: Float = 0f,
)

sealed interface LlmEvent {
    data class Piece(val text: String) : LlmEvent
    data class Done(val text: String, val stats: LlmStats) : LlmEvent
    data class Failed(val error: app.murmur.core.model.MurmurError) : LlmEvent
}

@Serializable
data class LlmStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val restoreMs: Double,
    val prefillMs: Double,
    val ttftMs: Double,
    val decodeMs: Double,
    val totalMs: Double,
    /** [STOP_EOG], [STOP_MAX_TOKENS], [STOP_STRING], [STOP_CANCELLED] (murmur_llm.h). MAX_TOKENS = truncated output: never inserted. */
    val stopReason: Int,
    val unit: ComputeUnit,
) {
    companion object {
        const val STOP_EOG = 0
        const val STOP_MAX_TOKENS = 1
        const val STOP_STRING = 2
        const val STOP_CANCELLED = 3
    }
}
