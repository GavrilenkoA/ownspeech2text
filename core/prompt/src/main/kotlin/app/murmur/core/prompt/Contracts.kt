package app.murmur.core.prompt

import app.murmur.core.context.CtxV2

/**
 * Kotlin port of prompts/render.py (D8). Must reproduce it byte-for-byte on prompts/examples/ctxv2_*
 * (PromptGoldenTest) and on the generated golden corpus (WP-04).
 */
interface PromptRenderer {
    fun render(input: RenderInput): RenderedPrompt
    /** render.render_context for ctx v2 (the `<context>` block). */
    fun renderContext(ctx: CtxV2, ctx2: kotlinx.serialization.json.JsonObject? = null): String
    /** render.cache_prefix: what the :llm process prefills and snapshots at load time. */
    fun cachePrefix(profile: PromptProfile, template: String, preferences: String?, systemOverride: String?): String
}

/** Kotlin port of prompts/guard.py; the cases in prompts/tests/test_guard.py are the contract. */
interface OutputGuard {
    fun precheck(transcript: String): Precheck
    fun guard(transcript: String, output: String, ctx: CtxV2?): GuardVerdict
}

/** Read access to the packaged prompts (classpath /prompts/...). */
interface PromptAssets {
    fun systemPrompt(stem: String): String
    fun profile(name: String): PromptProfile
    fun shots(path: String): List<Shot>
    fun text(path: String): String
}

/**
 * Prompt editor rules (Settings -> Cleanup prompt). ADR-019: the user edits the system-prompt
 * instructions and "My preferences"; the user-message layout (Style / <context> / <transcript>) is fixed
 * by render.py and is not editable, so `{transcript}`-style variables do not exist in the edited text.
 */
object PromptEditRules {
    const val MAX_SYSTEM_CHARS = 8_000
    const val MAX_PREFERENCES_CHARS = 1_000
    private val forbidden = listOf("<|im_", "<|startoftext|>", "<context>", "</context>", "<transcript>", "</transcript>", "<think>")

    /** Returns problems (empty = valid). Keys map to strings.xml messages in :feature:settings. */
    fun validateSystem(text: String): List<String> = buildList {
        if (text.isBlank()) add("empty")
        if (text.length > MAX_SYSTEM_CHARS) add("too_long")
        if (forbidden.any { text.contains(it) }) add("markup")
    }
}
