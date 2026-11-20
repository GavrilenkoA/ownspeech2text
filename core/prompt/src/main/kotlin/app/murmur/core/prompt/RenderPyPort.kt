package app.murmur.core.prompt

import app.murmur.core.context.CtxV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Kotlin port of prompts/render.py (D8): byte-identical to `render.render_prompt` / `render.cache_prefix` (and, with a
 * style override, to ml/eval/contract.render_prompt) for the four shipped chat templates, checked on the golden corpus
 * of core/prompt/tools/make_goldens.py. Only ctx v2 is rendered (ADR-040); a null ctx renders like Python's `None`.
 *
 * Request-independent work (system prompt, rendered few-shot turns, prefix and its sha256) is cached per
 * (profile, template, preferences, system override); everything else is recomputed per request.
 */
class RenderPyPort(private val assets: PromptAssets) : PromptRenderer {
    private val tones: JsonObject by lazy { Json.parseToJsonElement(assets.text("tones/tones.v1.json")).jsonObject }

    private data class PrefixKey(val profile: PromptProfile, val template: String, val preferences: String?, val systemOverride: String?)
    private class Prefix(val text: String, val key: String)

    private val turns = Lru<PromptProfile, List<Pair<String, String>>>(4)
    private val prefixes = Lru<PrefixKey, Prefix>(8)

    override fun render(input: RenderInput): RenderedPrompt {
        val tpl = RenderPy.template(input.template)
        val p = prefix(input.profile, input.template, input.preferences, input.systemOverride)
        val user = RenderPy.userMessage(input.ctx, input.transcript, input.styleOverride, input.profile.ctx2, tones)
        return RenderedPrompt(p.text, RenderPy.suffix(user, tpl, input.profile.answerPrefix), p.key)
    }

    override fun renderContext(ctx: CtxV2, ctx2: JsonObject?): String = RenderPy.renderContext(ctx, ctx2)

    override fun cachePrefix(profile: PromptProfile, template: String, preferences: String?, systemOverride: String?): String =
        prefix(profile, template, preferences, systemOverride).text

    /**
     * render.system_prompt for [profile] (+ the preferences block). [systemOverride] (ADR-046) replaces the text of
     * `system/<stem>.md` after `rstrip("\n")` by `override.replace("\r\n", "\n").rstrip("\n")`; the caller passes it
     * only when [PromptEditRules.overrideApplies].
     */
    fun systemPrompt(profile: PromptProfile, preferences: String?, systemOverride: String?): String {
        val base = systemOverride?.replace("\r\n", "\n")?.trimEnd('\n') ?: systemFile(profile.system)
        val prefs = if (!preferences.isNullOrEmpty()) RenderPy.clean(preferences, keepNewlines = true) else ""
        if (prefs.isEmpty()) return base
        return base + systemFile(profile.preferencesBlock).replace("{{PREFERENCES}}", Py.head(prefs, PromptEditRules.MAX_PREFERENCES_CHARS))
    }

    /** render.style_line with the shipped tones (prompts/tones/tones.v1.json). */
    fun styleLine(ctx: CtxV2?, styleOverride: String?): String = RenderPy.styleLine(ctx, tones, styleOverride)

    override fun toneText(tone: String): String? = Tones.namedText(tones, tone)

    /** ADR-020: the style override for a per-app tone ([tone] = Tone enum name, null = category default). */
    fun resolveTone(ctx: CtxV2?, tone: String?): ToneResolution = Tones.resolve(tones, ctx, tone)

    /** `(HERE / "system" / f"{_versioned(stem)}.md").read_text(encoding="utf-8").rstrip("\n")` (text mode: universal newlines). */
    private fun systemFile(stem: String): String =
        assets.systemPrompt(RenderPy.versioned(stem)).replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')

    private fun profileTurns(profile: PromptProfile): List<Pair<String, String>> = turns.getOrPut(profile) {
        val path = profile.shots
        if (path.isNullOrEmpty()) emptyList()
        else assets.shots(path).map { RenderPy.userMessage(it.ctx, it.transcript, null, profile.ctx2, tones) to it.target }
    }

    private fun prefix(profile: PromptProfile, template: String, preferences: String?, systemOverride: String?): Prefix =
        prefixes.getOrPut(PrefixKey(profile, template, preferences, systemOverride)) {
            val text = RenderPy.prefix(systemPrompt(profile, preferences, systemOverride), profileTurns(profile), RenderPy.template(template))
            Prefix(text, sha256Hex(text))
        }

    private class Lru<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(max * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }

        fun getOrPut(key: K, make: () -> V): V {
            synchronized(map) { map[key]?.let { return it } }
            val v = make()
            synchronized(map) { map[key] = v }
            return v
        }
    }

    companion object {
        /** RenderedPrompt.prefixKey: lowercase hex sha256 of the UTF-8 prefix (ADR-060). */
        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Per-app tone (ADR-020). [styleOverride] goes into [RenderInput.styleOverride]; [styleLine] is what the model sees.
 * [preview] = a named tone was chosen but prompts/tones/ has no text for it, so the category line is used and the
 * Settings UI marks the tone "preview".
 */
data class ToneResolution(val styleOverride: String?, val styleLine: String, val preview: Boolean)

internal object Tones {
    /**
     * Named tone texts are read from `named.<casual|neutral|formal|verbatim>.text` of the tones file; tones.v1.json has
     * no `named` section yet (ADR-020 open question to the prompt owner), so every named tone resolves to the category
     * default with [ToneResolution.preview] = true.
     */
    fun namedText(tones: JsonObject, tone: String): String? =
        ((tones["named"] as? JsonObject)?.get(tone.lowercase(Locale.ROOT)) as? JsonObject)
            ?.let { (it["text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.takeIf { it.isNotBlank() }

    fun resolve(tones: JsonObject, ctx: CtxV2?, tone: String?): ToneResolution {
        val text = tone?.let { namedText(tones, it) }
        return ToneResolution(text, RenderPy.styleLine(ctx, tones, text), preview = tone != null && text == null)
    }
}
