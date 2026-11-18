package app.murmur.infer.api

import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Binder contract constants shared by clients (:main) and services (:asr, :llm). */
object InferApi {
    /** Bump on any breaking change of an AIDL method or a JSON payload type. Checked right after bind. */
    const val API_VERSION = 1

    /** Service classes, by name so that :infer:api does not depend on the service modules. */
    const val ASR_SERVICE = "app.murmur.infer.asr.AsrService"
    const val LLM_SERVICE = "app.murmur.infer.llm.LlmService"

    fun serviceClass(p: InferProcess): String = when (p) {
        InferProcess.ASR -> ASR_SERVICE
        InferProcess.LLM -> LLM_SERVICE
    }

    /** One Json configuration for every payload on both sides of the binder. */
    val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun <T> encode(s: KSerializer<T>, v: T): String = json.encodeToString(s, v)
    fun <T> decode(s: KSerializer<T>, text: String): T = json.decodeFromString(s, text)
    fun encodeError(e: MurmurError): String = json.encodeToString(MurmurError.serializer(), e)
    fun decodeError(text: String): MurmurError =
        runCatching { json.decodeFromString(MurmurError.serializer(), text) }.getOrElse { MurmurError.Internal("bad error payload") }
}

/**
 * Client-side timeouts for binder round trips (ARCHITECTURE.md 4.3). The prefix prefill and the synthetic benchmark
 * scale with the work (review 2026-11-19: a fixed 30 s killed the 3.1k-token default.v4 prefill on the emulator).
 * [scale] = Settings.debugTimeoutScale in debug builds (1.0 in release). A timeout means "treat the process as dead":
 * shutdown() + Err(Timeout(stage)).
 */
data class BinderTimeouts(
    val bindMs: Long = 3_000,
    val asrLoadMs: Long = 15_000,
    val llmLoadMs: Long = 30_000,
    val ocrMs: Long = 4_000,
    val callMs: Long = 2_000,
    /** Floor prefill speed for timeouts when no benchmark exists (tok/s; emulator Qwen3.5 measured ~60, LFM3 ~15). */
    val prefillFloorTps: Double = 40.0,
    val decodeFloorTps: Double = 5.0,
    val scale: Double = 1.0,
) {
    /** setPrefix: max(30 s, 2 x tokens / speed); a restore from disk is milliseconds, a prefill is the slow case. */
    fun prefixMs(prefixTokens: Int, prefillTps: Double? = null): Long =
        (maxOf(30_000.0, 2.0 * prefixTokens / (prefillTps ?: prefillFloorTps) * 1000) * scale).toLong()

    /** bench(nPrompt, nGen, reps): (nPrompt / pp + nGen / tg) x reps x 2, at the floor speeds, at least 30 s. */
    fun benchMs(nPrompt: Int, nGen: Int, reps: Int): Long =
        (maxOf(30_000.0, (nPrompt / prefillFloorTps + nGen / decodeFloorTps) * reps * 2 * 1000) * scale).toLong()

    fun scaled(ms: Long): Long = (ms * scale).toLong()
}
