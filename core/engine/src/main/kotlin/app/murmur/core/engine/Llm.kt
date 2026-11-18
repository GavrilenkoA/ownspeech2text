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
    /** Benchmark button (WP-14): synthetic prefill/decode speed of the loaded model. */
    suspend fun bench(nPrompt: Int, nGen: Int, reps: Int): Outcome<SyntheticBench>
}

@Serializable
data class SyntheticBench(val ppTps: Double, val tgTps: Double, val ppMs: Double, val tgMs: Double)

@Serializable
data class PrefixSpec(
    val text: String,
    /** sha256(model file sha256 + prefix text + unit); names the persisted state file. */
    val cacheKey: String,
)

@Serializable
data class PrefixInfo(val tokens: Int, val prefillMs: Long, val restoredFromDisk: Boolean, val bytes: Long)

@Serializable
data class LlmRequest(
    val suffix: String,
    val maxTokens: Int,
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
    /** 0 eog, 1 max_tokens, 2 stop string, 3 cancelled (murmur_llm.h). */
    val stopReason: Int,
    val unit: ComputeUnit,
)
