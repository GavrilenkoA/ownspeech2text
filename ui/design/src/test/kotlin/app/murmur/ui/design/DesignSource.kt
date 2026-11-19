package app.murmur.ui.design

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The design sources the generated code must match (passed by ui/design/build.gradle.kts). */
object DesignSource {
    private val root: File = File(requireNotNull(System.getProperty("murmur.repoRoot")) { "murmur.repoRoot not set" })

    val tokens: JsonObject by lazy { Json.parseToJsonElement(File(root, "docs/design/tokens.json").readText()).jsonObject }

    val prototype: String by lazy { File(root, "docs/design/prototype/index.html").readText() }

    /** Icon names of the prototype's `const ICONS = { … }` map, in source order (parsed independently of the generator). */
    val iconNames: List<String> by lazy {
        val start = prototype.indexOf("const ICONS = {")
        val end = prototype.indexOf("};", start)
        check(start >= 0 && end > start) { "ICONS map not found" }
        Regex("""^\s*([A-Za-z0-9]+)\s*:\s*'""", RegexOption.MULTILINE)
            .findAll(prototype.substring(start, end)).map { it.groupValues[1] }.toList()
    }

    /** One SVG element of a prototype icon: its tag and attributes, as written in the prototype. */
    data class SvgElement(val tag: String, val attrs: Map<String, String>) {
        fun num(name: String, default: Double? = null): Double =
            attrs[name]?.toDouble() ?: default ?: error("<$tag> without $name")
    }

    /**
     * Every element of every icon of the prototype's ICONS map, in source order. Parsed here on its own (every tag,
     * not only the ones the generator knows), so that the icon tests compare the generated ImageVectors with the
     * prototype rather than with the generator's reading of it.
     */
    val iconElements: Map<String, List<SvgElement>> by lazy {
        val start = prototype.indexOf("const ICONS = {")
        val end = prototype.indexOf("};", start)
        val entry = Regex("""^\s*([A-Za-z0-9]+)\s*:\s*'(.*)'\s*,?\s*$""", RegexOption.MULTILINE)
        val tag = Regex("""<\s*([A-Za-z]+)([^>]*?)/?>""")
        val attr = Regex("""([A-Za-z:-]+)\s*=\s*"([^"]*)"""")
        entry.findAll(prototype.substring(start, end)).associate { m ->
            m.groupValues[1] to tag.findAll(m.groupValues[2]).map { t ->
                SvgElement(t.groupValues[1], attr.findAll(t.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] })
            }.toList()
        }
    }

    /** "#RRGGBB" or "#RRGGBBAA" -> ARGB int. */
    fun argb(hex: String): Int {
        val s = hex.removePrefix("#")
        val v = when (s.length) {
            6 -> "FF$s"
            8 -> s.substring(6, 8) + s.substring(0, 6)
            else -> error("bad colour $hex")
        }
        return v.toLong(16).toInt()
    }
}
