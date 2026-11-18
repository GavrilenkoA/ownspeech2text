import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

// D7: docs/design/tokens.json is the source of truth. Generate Kotlin token objects at build time so that
// nobody hand-copies a hex value. Hand-written Theme.kt maps them onto MaterialTheme (DESIGN 9).
abstract class GenerateDesignTokens : DefaultTask() {
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        @Suppress("UNCHECKED_CAST")
        val t = JsonSlurper().parse(tokens.get().asFile) as Map<String, Any?>
        val color = t["color"] as Map<String, Map<String, Map<String, Any?>>>
        val light = color.getValue("light"); val dark = color.getValue("dark")
        require(light.keys == dark.keys) { "tokens.json: light/dark color keys differ" }
        fun hex(v: Any?): String {
            val s = (v as String).removePrefix("#")
            val argb = if (s.length == 8) s.substring(6, 8) + s.substring(0, 6) else "FF$s"
            return "Color(0x${argb.uppercase()})"
        }
        val keys = light.keys.sorted()
        val sb = StringBuilder()
        sb.append("// GENERATED from docs/design/tokens.json by :ui:design:generateDesignTokens. Do not edit.\n")
        sb.append("package app.murmur.ui.design\n\nimport androidx.compose.runtime.Immutable\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.unit.dp\n\n")
        sb.append("@Immutable\ndata class MurmurColors(\n")
        keys.forEach { sb.append("    val $it: Color,\n") }
        sb.append(")\n\nobject TokenColors {\n")
        for ((name, m) in listOf("light" to light, "dark" to dark)) {
            sb.append("    val $name = MurmurColors(\n")
            keys.forEach { k -> sb.append("        $k = ${hex(m.getValue(k)["\$value"])},\n") }
            sb.append("    )\n")
        }
        sb.append("}\n\nobject TokenType {\n")
        @Suppress("UNCHECKED_CAST")
        val typo = t["typography"] as Map<String, Map<String, Any?>>
        for ((k, v) in typo) {
            val mono = v["fontFamily"] == "mono"
            val upper = v["textTransform"] == "uppercase"
            val tnum = v["fontFeatures"] == "tnum"
            sb.append("    val $k = TypeSpec(size = ${v["fontSize"]}, line = ${v["lineHeight"]}, weight = ${v["fontWeight"]}, " +
                "tracking = ${(v["letterSpacing"] as Number).toFloat()}f, mono = $mono, uppercase = $upper, tabular = $tnum)\n")
        }
        sb.append("}\n\nobject Spacing {\n")
        @Suppress("UNCHECKED_CAST")
        (t["spacing"] as Map<String, Any?>).filterKeys { !it.startsWith("$") }.forEach { (k, v) -> sb.append("    val $k = $v.dp\n") }
        sb.append("}\n\nobject Radius {\n")
        @Suppress("UNCHECKED_CAST")
        (t["radius"] as Map<String, Any?>).filterKeys { !it.startsWith("$") }.forEach { (k, v) -> sb.append("    val $k = $v.dp\n") }
        sb.append("}\n\nobject Motion {\n")
        @Suppress("UNCHECKED_CAST")
        val motion = t["motion"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        (motion["duration"] as Map<String, Any?>).forEach { (k, v) -> sb.append("    const val ${k}Ms = $v\n") }
        @Suppress("UNCHECKED_CAST")
        (motion["bubbleTimings"] as Map<String, Any?>).forEach { (k, v) -> sb.append("    const val ${k}Ms = $v\n") }
        sb.append("}\n")
        val f = outDir.get().file("app/murmur/ui/design/GeneratedTokens.kt").asFile
        f.parentFile.mkdirs(); f.writeText(sb.toString())
    }
}

val genTokens = tasks.register<GenerateDesignTokens>("generateDesignTokens") {
    tokens.set(rootDir.resolve("docs/design/tokens.json"))
}
androidComponents {
    onVariants { v -> v.sources.kotlin?.addGeneratedSourceDirectory(genTokens, GenerateDesignTokens::outDir) }
}
