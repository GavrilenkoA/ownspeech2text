package app.murmur.core.catalog

import app.murmur.core.model.ModelKind
import app.murmur.core.model.Outcome

/**
 * D3 "the user can configure any model": the user pastes a Hugging Face repo (and optionally a revision
 * and file), Murmur builds a [ModelEntry] with origin CUSTOM for a supported runtime:
 *  - LLM: one GGUF file -> runtime llama/gguf (template/profile chosen by the user, default "qwen3.5"/default profile).
 *  - ASR: a sherpa-onnx layout (encoder/decoder/joiner + tokens.txt, or model.onnx + tokens.txt) -> sherpa runtime.
 * Implemented in :data:models (needs network); this file is the contract.
 */
data class CustomModelRequest(
    val kind: ModelKind,
    val repo: String,                 // "owner/name"
    val revision: String? = null,     // null = resolve "main" to a commit sha at add time (pinned afterwards)
    val file: String? = null,         // GGUF path for LLMs; null = pick by rules (prefer Q4_0)
    val runtime: String? = null,      // null = detect from the file list
    val displayName: String? = null,
    val llmTemplate: String? = null,  // prompts/render.py TEMPLATES key
)

interface CustomModelResolver {
    /** Reads the repo file list (HF API, sizes + LFS sha256), detects the layout, returns a validated entry. */
    suspend fun resolve(request: CustomModelRequest): Outcome<ModelEntry>
}

/** Pure layout detection used by the resolver (unit-testable without network). */
object SherpaLayout {
    /** Returns role -> path for a recognised sherpa-onnx offline/online model file list, or null. */
    fun detect(paths: List<String>): Pair<String, Map<String, String>>? {
        fun find(re: Regex) = paths.firstOrNull { re.containsMatchIn(it.substringAfterLast('/')) }
        val tokens = find(Regex("tokens\\.txt$")) ?: return null
        val enc = find(Regex("^encoder.*\\.onnx$"))
        val dec = find(Regex("^decoder.*\\.onnx$"))
        val joi = find(Regex("^joiner.*\\.onnx$"))
        if (enc != null && dec != null && joi != null)
            return RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER to mapOf("encoder" to enc, "decoder" to dec, "joiner" to joi, "tokens" to tokens)
        val wEnc = find(Regex("encoder.*\\.onnx$")); val wDec = find(Regex("decoder.*\\.onnx$"))
        if (wEnc != null && wDec != null && paths.any { it.contains("whisper", ignoreCase = true) })
            return RuntimeKeys.SHERPA_OFFLINE_WHISPER to mapOf("encoder" to wEnc, "decoder" to wDec, "tokens" to tokens)
        val model = find(Regex("^model.*\\.onnx$"))
        if (model != null) return RuntimeKeys.SHERPA_OFFLINE_CTC to mapOf("model" to model, "tokens" to tokens)
        return null
    }
}
