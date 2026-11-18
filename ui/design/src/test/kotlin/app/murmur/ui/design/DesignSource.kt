package app.murmur.ui.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

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
