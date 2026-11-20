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
    /**
     * The style text of a named tone (`named.<tone>.text` of prompts/tones/tones.v1.json, ADR-020) for
     * [RenderInput.styleOverride]; null when the tone has no text (then the category style line applies). [tone] is the
     * lowercase `Tone` name. Default: no named texts (ADR-088).
     */
    fun toneText(tone: String): String? = null
}

/** Kotlin port of prompts/guard.py; the cases in prompts/tests/test_guard.py are the contract. */
interface OutputGuard {
    fun precheck(transcript: String): Precheck
    fun guard(transcript: String, output: String, ctx: CtxV2?): GuardVerdict
    /** guard.py FILLERS (casefolded); the dashboard's "fillers removed" uses the same lexicon (DeliveryMetrics). */
    val fillers: Set<String>
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
    /**
     * Chat-template markup of the four render templates (prompts/render.py TEMPLATES): special tokens and the thinking
     * tags. The request-layout tags (`<context>`, `<transcript>`) are NOT markup here: the shipped system prompts name
     * them ("a <context> block and a raw speech-to-text <transcript>"), the system text is its own chat message, so it
     * cannot open or close the user message's blocks, and forbidding them made the shipped default itself invalid.
     */
    private val forbidden = listOf("<|im_", "<|startoftext|>", "<|endoftext|>", "<think>", "</think>")

    /**
     * The user's edited instructions apply only to the profile they were written for (review 2026-11-19, ADR-046):
     * a stock profile whose name equals [override].baseProfile. Never to fine-tune profiles (`finetuned.*`), which
     * were trained on their own prompt, and not after the catalog moves to a newer stock profile (Settings then shows
     * "Your edit was made for an older prompt" with Reset / Keep editing).
     */
    fun overrideApplies(profile: PromptProfile, overrideBaseProfile: String?): Boolean =
        overrideBaseProfile != null && !profile.system.startsWith("finetuned") && profile.name == overrideBaseProfile

    /**
     * Whether the Settings editor may change the system prompt of [profile]: not for fine-tunes (`finetuned.*`), which
     * keep the prompt they were trained on (the editor is read-only there).
     */
    fun editable(profile: PromptProfile): Boolean = !profile.system.startsWith("finetuned")

    /**
     * What Settings -> Cleanup prompt shows for the active [profile] and the stored override's `baseProfile`
     * ([overrideBaseProfile], null = no override): [PromptEditState.DEFAULT] (no edit), [PromptEditState.EDITED] (the
     * edit applies), [PromptEditState.MADE_FOR_OLDER_PROMPT] (an edit exists and its baseProfile is not the active
     * profile, so it is not applied), [PromptEditState.NOT_EDITABLE] (a fine-tune and no stored edit).
     * MADE_FOR_OLDER_PROMPT also covers an active fine-tune with a stored edit; the UI tells the two apart with
     * [editable]: on a stock profile it shows "Your edit was made for an older prompt" with Reset; on a fine-tune
     * (editable == false) it shows the read-only shipped prompt and "Your edit is kept for the standard prompt" with no
     * Reset (ADR-078, WP-19 brief; pending OWNER_QUESTIONS 5).
     */
    fun editState(profile: PromptProfile, overrideBaseProfile: String?): PromptEditState = when {
        overrideBaseProfile == null -> if (editable(profile)) PromptEditState.DEFAULT else PromptEditState.NOT_EDITABLE
        overrideApplies(profile, overrideBaseProfile) -> PromptEditState.EDITED
        else -> PromptEditState.MADE_FOR_OLDER_PROMPT
    }

    /** "Edited" value of the Cleanup prompt row: the stored edit applies to the active profile. */
    fun isEdited(profile: PromptProfile, overrideBaseProfile: String?): Boolean =
        editState(profile, overrideBaseProfile) == PromptEditState.EDITED

    /** "Your edit was made for an older prompt": an override exists and its baseProfile != the active profile name. */
    fun madeForOlderPrompt(profile: PromptProfile, overrideBaseProfile: String?): Boolean =
        editState(profile, overrideBaseProfile) == PromptEditState.MADE_FOR_OLDER_PROMPT

    /** Returns problems (empty = valid). Keys map to strings.xml messages in :feature:settings. */
    fun validateSystem(text: String): List<String> = buildList {
        if (text.isBlank()) add("empty")
        if (text.length > MAX_SYSTEM_CHARS) add("too_long")
        if (forbidden.any { text.contains(it) }) add("markup")
    }
}

enum class PromptEditState { DEFAULT, EDITED, MADE_FOR_OLDER_PROMPT, NOT_EDITABLE }
