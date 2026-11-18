package app.murmur.core.prompt

import app.murmur.core.context.CtxV2
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** prompts/profiles/<name>.json (render.py load_profile). */
@Serializable
data class PromptProfile(
    val name: String,
    /** System prompt file stem, e.g. "default.v4" -> prompts/system/default.v4.md. */
    val system: String,
    /** Few-shot file relative to prompts/, e.g. "shots/default.v4.jsonl"; null = none. */
    val shots: String? = null,
    @kotlinx.serialization.SerialName("answer_prefix") val answerPrefix: String = "",
    /** ctx v2 options (render.CTX2_DEFAULT overrides); {} = defaults. */
    val ctx2: JsonObject? = null,
    @kotlinx.serialization.SerialName("preferences_block") val preferencesBlock: String = "preferences_block",
)

/** One few-shot row of a prompts/shots JSONL file (render.profile_turns). */
@Serializable
data class Shot(val ctx: CtxV2? = null, val transcript: String, val target: String)

/**
 * Everything the renderer needs for one request.
 *
 * [systemOverride] = the user's edited instructions (Settings -> Cleanup prompt); null = the profile's shipped
 * system prompt (D8 "default restorable"). Exact semantics (ADR-046), mirrored in the golden generator
 * (core/prompt/tools/make_goldens.py, helper `system_prompt_with_override`): in render.system_prompt the text of
 * `system/<stem>.md` after `.rstrip("\n")` is replaced by `override.replace("\r\n", "\n").rstrip("\n")`; the
 * preferences block is appended after it exactly as for the shipped text. The caller passes an override only when
 * [PromptEditRules.overrideApplies] is true (stock profiles only, never finetuned.*).
 */
data class RenderInput(
    val profile: PromptProfile,
    /** render.py TEMPLATES key from the catalog (LlmTraits.template). */
    val template: String,
    val ctx: CtxV2?,
    val transcript: String,
    val preferences: String? = null,
    val styleOverride: String? = null,
    val systemOverride: String? = null,
)

/**
 * [prefix] = render.cache_prefix (request-independent: its KV state is cached per model load, ADR-011);
 * [suffix] = the rest; prefix + suffix == render.render_prompt byte-for-byte.
 * [prefixKey] = lowercase hex sha256 of the UTF-8 [prefix] only; :main combines it with the model sha, unit, native
 * build and context parameters into the snapshot key (core:engine PrefixCacheKey).
 */
data class RenderedPrompt(val prefix: String, val suffix: String, val prefixKey: String) {
    val full: String get() = prefix + suffix
}

/** Output guard verdict (prompts/guard.py). */
enum class GuardAction { ACCEPT, FALLBACK, EMPTY }

data class GuardVerdict(val text: String, val action: GuardAction, val reason: String)

data class Precheck(val skip: Boolean, val transcript: String, val reason: String)
