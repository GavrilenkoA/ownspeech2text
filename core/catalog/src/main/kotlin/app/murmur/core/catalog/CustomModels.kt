package app.murmur.core.catalog

import app.murmur.core.model.ModelKind
import app.murmur.core.model.Outcome

/**
 * D3 "the user can configure any model": the user pastes a Hugging Face repo (and optionally a revision
 * and file), Murmur builds a [ModelEntry] with origin CUSTOM for a supported runtime:
 *  - LLM: one GGUF file -> runtime llama/gguf; template from the GGUF architecture ([GgufTemplates]); prompt
 *    profile from [GgufTemplates.profileFor] unless the user picks one (ADR-046).
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
    /** render.py TEMPLATES key; null = from the GGUF general.architecture ([GgufTemplates.templateFor]). */
    val llmTemplate: String? = null,
    /** prompts profile; null = [GgufTemplates.profileFor] (fine-tunes get finetuned.v1, others [GgufTemplates.STOCK_PROFILE]). */
    val promptProfile: String? = null,
)

interface CustomModelResolver {
    /**
     * Reads the repo file list (HF API, sizes + LFS sha256), detects the layout, reads the GGUF header (first
     * MBs via an HTTP Range request: general.architecture, general.name, murmur.prompt_profile), returns a
     * validated entry with nCtx = roundUp(prefixTokens + CONTEXT_RESERVE_TOKENS) once Preparing measured the
     * prefix (until then the [GgufTemplates.DEFAULT_CUSTOM_NCTX]).
     */
    suspend fun resolve(request: CustomModelRequest): Outcome<ModelEntry>
}

/**
 * GGUF metadata -> render.py template and prompt profile (ADR-046). render.py has exactly four templates; any other
 * architecture is rejected with a clear error, because the prompt format would be wrong.
 * VERIFIED (architect, GGUF header read of the cached files, 2026-11-19): Qwen3.5-0.8B/2B = "qwen35", LFM3 = "lfm3",
 * LFM2.5-1.2B/350M = "lfm2", Qwen3-0.6B = "qwen3"; the ML dry-run fine-tunes have "qwen35" and general.name
 * "murmur-dryrun-lora" / "murmur-dryrun-full" (no prompt-profile key yet).
 */
object GgufTemplates {
    val byArchitecture: Map<String, String> = mapOf(
        "qwen35" to "qwen3.5",
        "qwen3" to "qwen3",
        "lfm3" to "lfm3",
        "lfm2" to "lfm2.5",
    )

    /** GGUF key written by ml/train/export.py for Murmur fine-tunes (requested from the ML team, ADR-046). */
    const val PROFILE_KEY = "murmur.prompt_profile"
    const val FINETUNE_NAME_PREFIX = "murmur-cleanup-"
    /** The stock cleanup profile of every stock catalog LLM (ADR-085: default.v1 since catalog 2026-11-20.2). */
    const val STOCK_PROFILE = "default.v1"
    const val FINETUNE_PROFILE = "finetuned.v1"
    const val DEFAULT_CUSTOM_NCTX = 9_216

    fun templateFor(architecture: String): String? = byArchitecture[architecture]

    /** Explicit GGUF key wins, then the fine-tune name convention, else the stock profile. */
    fun profileFor(generalName: String?, profileKey: String?): String = when {
        !profileKey.isNullOrBlank() -> profileKey
        generalName?.startsWith(FINETUNE_NAME_PREFIX) == true -> FINETUNE_PROFILE
        else -> STOCK_PROFILE
    }

    /** nCtx rule of [LlmTraits.nCtx]. */
    fun nCtxFor(prefixTokens: Int): Int {
        val need = prefixTokens + LlmTraits.CONTEXT_RESERVE_TOKENS
        return ((need + 1023) / 1024) * 1024
    }

    /**
     * A custom LLM entry after Preparing measured its prefix (PrefixInfo.tokens): prefixTokens set and nCtx re-derived
     * by [nCtxFor]. The caller stores it again with CatalogRepository.addCustom (upsert). Non-LLM entries unchanged.
     */
    fun withMeasuredPrefix(entry: ModelEntry, prefixTokens: Int): ModelEntry {
        val l = entry.llm ?: return entry
        return entry.copy(llm = l.copy(prefixTokens = prefixTokens, nCtx = nCtxFor(prefixTokens)))
    }
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
