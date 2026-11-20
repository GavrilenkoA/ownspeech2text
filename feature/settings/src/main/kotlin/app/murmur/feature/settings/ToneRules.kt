package app.murmur.feature.settings

import app.murmur.core.store.Tone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/** One app in Tone per app (DESIGN 6.5 `tone`). */
data class ToneRow(
    val pkg: String,
    /** Launcher label, or null when the app is not visible (the row shows the package name). */
    val label: String?,
    /** What the dropdown shows: the stored choice, else the category's tone. */
    val tone: Tone,
    /** The app's category tone (messengers Casual, email Formal, terminals Verbatim, everything else Neutral). */
    val categoryTone: Tone,
    /** A tone is stored for this app in [app.murmur.core.store.Settings.tonePerApp]. */
    val explicit: Boolean,
    /**
     * ADR-020: the stored tone differs from the category's and `prompts/tones/` has no text for it yet, so the
     * cleanup keeps the category style line; the UI marks the row "preview".
     */
    val preview: Boolean,
    /** Added by the user (not listed from its category): its menu offers "Remove from list". */
    val removable: Boolean,
)

/**
 * Tone per app rules (ADR-020, DESIGN 6.5). The list shows the installed apps of the categories the design names
 * (messengers, work chat, email, notes and documents, terminals and code editors) in the order of
 * `prompts/tones/app_categories.v1.json`, then the apps the user added, by label.
 */
object ToneRules {
    /** Categories whose installed apps are listed without being added (DESIGN 6.5 defaults + the frame's Slack/Docs). */
    val LISTED_CATEGORIES: List<String> = listOf("messaging", "work_chat", "email", "notes", "code")

    /** DESIGN 6.5: messengers Casual; email Formal; terminals and IDEs Verbatim; others Neutral. */
    fun categoryTone(category: String?): Tone = when (category) {
        "messaging" -> Tone.CASUAL
        "email" -> Tone.FORMAL
        "code" -> Tone.VERBATIM
        else -> Tone.NEUTRAL
    }

    /**
     * Tones that have their own style text in [tonesJson] (`prompts/tones/tones.v1.json`, `named.<tone>.text`, the
     * schema the renderer reads, ADR-078). tones.v1.json has no `named` section yet, so this is empty today.
     */
    fun tonesWithText(tonesJson: String): Set<Tone> {
        val root = Json.parseToJsonElement(tonesJson).jsonObject
        val named = root["named"] as? JsonObject ?: return emptySet()
        return Tone.entries.filterTo(mutableSetOf()) { t ->
            val text = ((named[t.name.lowercase(Locale.ROOT)] as? JsonObject)?.get("text") as? JsonPrimitive)
            text != null && text.isString && text.content.isNotBlank()
        }
    }

    /** A stored [tone] that is not the category's own has an effect only with a style text of its own. */
    fun isPreview(tone: Tone, categoryTone: Tone, withText: Set<Tone>): Boolean = tone != categoryTone && tone !in withText

    /**
     * The rows of Tone per app. [launcherApps] = installed apps with a launcher entry; [categories] = package ->
     * category in file order; [label] = launcher label lookup.
     */
    fun rows(
        launcherApps: Collection<String>,
        categories: Map<String, String>,
        tonePerApp: Map<String, Tone>,
        withText: Set<Tone>,
        label: (String) -> String?,
    ): List<ToneRow> {
        val installed = launcherApps.toSet()
        val listed = LISTED_CATEGORIES.flatMap { cat ->
            categories.entries.filter { it.value == cat && it.key in installed }.map { it.key }
        }
        val listedSet = listed.toSet()
        val added = tonePerApp.keys.filter { it !in listedSet }.sortedWith(compareBy({ (label(it) ?: it).lowercase() }, { it }))
        fun row(pkg: String, removable: Boolean): ToneRow {
            val cat = categoryTone(categories[pkg])
            val stored = tonePerApp[pkg]
            val tone = stored ?: cat
            return ToneRow(pkg, label(pkg), tone, cat, stored != null, stored != null && isPreview(tone, cat, withText), removable)
        }
        return listed.map { row(it, removable = false) } + added.map { row(it, removable = true) }
    }

    /**
     * The new tonePerApp map after the user picks [tone] for [pkg]: picking a listed app's own category tone removes
     * the stored choice (the category line is what the name describes); anything else is stored.
     */
    fun pick(tonePerApp: Map<String, Tone>, pkg: String, tone: Tone, listed: Boolean, categoryTone: Tone): Map<String, Tone> =
        if (listed && tone == categoryTone) tonePerApp - pkg else tonePerApp + (pkg to tone)
}
