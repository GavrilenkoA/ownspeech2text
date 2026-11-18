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

/** Client-side timeouts for binder round trips (ARCHITECTURE.md 4.3). */
data class BinderTimeouts(
    val bindMs: Long = 3_000,
    val asrLoadMs: Long = 15_000,
    val llmLoadMs: Long = 30_000,
    val prefixMs: Long = 30_000,
    val ocrMs: Long = 4_000,
    val callMs: Long = 2_000,
)
