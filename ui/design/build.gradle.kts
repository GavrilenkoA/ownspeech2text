import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    // rememberIsWide(): currentWindowAdaptiveInfoV2().windowSizeClass (ADR-032; same artifact :app uses).
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.compose.ui.graphics)
    androidTestImplementation(libs.androidx.test.core)
}

// D7: docs/design/tokens.json is the source of truth. Generate Kotlin token objects at build time so that
// nobody hand-copies a hex value. Hand-written Theme.kt / Type.kt / Elevation.kt map them onto Compose (DESIGN 9).
abstract class GenerateDesignTokens : DefaultTask() {
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj() = this as Map<String, Any?>

    private fun hex(v: Any?): String {
        val s = (v as String).removePrefix("#")
        val argb = if (s.length == 8) s.substring(6, 8) + s.substring(0, 6) else "FF$s"
        return "Color(0x${argb.uppercase()})"
    }

    private fun num(v: Any?): String {
        val d = (v as Number).toDouble()
        return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }

    private fun f(v: Any?): String = "${(v as Number).toFloat()}f"

    /** size.bubble.edgeTab.hitW -> bubbleEdgeTabHitW */
    private fun flatten(prefix: String, m: Map<String, Any?>, out: MutableMap<String, Any?>) {
        for ((k, v) in m) {
            if (k.startsWith("$")) continue
            val name = if (prefix.isEmpty()) k else prefix + k.replaceFirstChar { it.uppercase() }
            if (v is Map<*, *>) flatten(name, v.obj(), out) else out[name] = v
        }
    }

    @TaskAction
    fun run() {
        val t = JsonSlurper().parse(tokens.get().asFile).obj()
        val color = t["color"].obj()
        val light = color["light"].obj(); val dark = color["dark"].obj()
        require(light.keys == dark.keys) { "tokens.json: light/dark color keys differ" }
        val keys = light.keys.sorted()
        val sb = StringBuilder()
        sb.append("// GENERATED from docs/design/tokens.json by :ui:design:generateDesignTokens. Do not edit.\n")
        sb.append("package app.murmur.ui.design\n\n")
        sb.append("import androidx.compose.animation.core.CubicBezierEasing\n")
        sb.append("import androidx.compose.runtime.Immutable\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.unit.dp\n\n")
        sb.append("@Immutable\ndata class MurmurColors(\n")
        keys.forEach { sb.append("    val $it: Color,\n") }
        sb.append(")\n\nobject TokenColors {\n")
        for ((name, m) in listOf("light" to light, "dark" to dark)) {
            sb.append("    val $name = MurmurColors(\n")
            keys.forEach { k -> sb.append("        $k = ${hex(m[k].obj()["\$value"])},\n") }
            sb.append("    )\n")
        }
        @Suppress("UNCHECKED_CAST")
        val palette = color["appTile"].obj()["palette"] as List<Any?>
        sb.append("    /** Fallback letter tiles for host apps without a loadable launcher icon. */\n")
        sb.append("    val appTilePalette: List<Color> = listOf(${palette.joinToString(", ") { hex(it) }})\n")
        sb.append("}\n\nobject TokenType {\n")
        for ((k, raw) in t["typography"].obj()) {
            val v = raw.obj()
            val mono = v["fontFamily"] == "mono"
            val upper = v["textTransform"] == "uppercase"
            val tnum = v["fontFeatures"] == "tnum"
            sb.append("    val $k = TypeSpec(size = ${v["fontSize"]}, line = ${v["lineHeight"]}, weight = ${v["fontWeight"]}, " +
                "tracking = ${f(v["letterSpacing"])}, mono = $mono, uppercase = $upper, tabular = $tnum)\n")
        }
        sb.append("    val all: Map<String, TypeSpec> = linkedMapOf(${t["typography"].obj().keys.joinToString(", ") { "\"$it\" to $it" }})\n")
        sb.append("}\n\nobject Spacing {\n")
        t["spacing"].obj().filterKeys { !it.startsWith("$") }.forEach { (k, v) -> sb.append("    val $k = ${num(v)}.dp\n") }
        sb.append("}\n\nobject Radius {\n")
        t["radius"].obj().filterKeys { !it.startsWith("$") }.forEach { (k, v) -> sb.append("    val $k = ${num(v)}.dp\n") }
        sb.append("}\n\n/** tokens.json size: nested keys flattened (bubble.edgeTab.hitW -> bubbleEdgeTabHitW). All dp. */\nobject Sizes {\n")
        val sizes = linkedMapOf<String, Any?>(); flatten("", t["size"].obj(), sizes)
        sizes.forEach { (k, v) ->
            if (k.endsWith("Count")) sb.append("    const val $k = ${num(v)}\n") else sb.append("    val $k = ${num(v)}.dp\n")
        }
        sb.append("}\n\n/** tokens.json layout: pane widths and window size class bounds (dp). */\nobject Layout {\n")
        val layout = t["layout"].obj()
        layout["windowSizeClass"].obj().filterKeys { it != "source" }.forEach { (k, v) -> sb.append("    val $k = ${num(v)}.dp\n") }
        layout["fold7Inner"].obj()["panes"].obj().forEach { (k, v) -> sb.append("    val ${k}Pane = ${num(v)}.dp\n") }
        sb.append("}\n\n/** tokens.json elevation: CSS box-shadow layers (y offset, blur) + Compose shadow elevation. */\nobject Elevation {\n")
        for ((k, raw) in t["elevation"].obj()) {
            val v = raw.obj()
            val sh = v["shadow"]
            val layers = if (sh is List<*>) sh.joinToString(", ") {
                val l = it.obj(); "ShadowLayer(y = ${num(l["y"])}.dp, blur = ${num(l["blur"])}.dp, color = ${hex(l["color"])})"
            } else ""
            val ce = v["composeShadowElevation"]?.let { "${num(it)}.dp" } ?: "0.dp"
            sb.append("    val $k = ElevationSpec(layers = listOf($layers), shadowElevation = $ce)\n")
        }
        sb.append("}\n\nobject Motion {\n")
        val motion = t["motion"].obj()
        motion["duration"].obj().forEach { (k, v) -> sb.append("    const val ${k}Ms = $v\n") }
        // bubbleTimings mixes units: levelSampleRate is Hz and dragSlop is dp; everything else is ms.
        motion["bubbleTimings"].obj().forEach { (k, v) ->
            when (k) {
                "levelSampleRate" -> {
                    sb.append("    const val levelSampleRateHz = $v\n")
                    sb.append("    @Deprecated(\"Hz, not ms\", ReplaceWith(\"Motion.levelSampleRateHz\")) const val levelSampleRateMs = $v\n")
                }
                "dragSlop" -> {
                    sb.append("    val dragSlop = ${num(v)}.dp\n")
                    sb.append("    @Deprecated(\"dp, not ms\", ReplaceWith(\"Motion.dragSlop\")) const val dragSlopMs = $v\n")
                }
                else -> sb.append("    const val ${k}Ms = $v\n")
            }
        }
        motion["easing"].obj().forEach { (k, v) ->
            val p = (v as List<*>).map { f(it) }
            sb.append("    val ${k}Easing = CubicBezierEasing(${p.joinToString(", ")})\n")
        }
        motion["spring"].obj().forEach { (k, raw) ->
            val v = raw.obj()
            sb.append("    val ${k}Spring = SpringToken(stiffness = ${f(v["stiffness"])}, dampingRatio = ${f(v["dampingRatio"])})\n")
        }
        sb.append("}\n")
        val out = outDir.get().file("app/murmur/ui/design/GeneratedTokens.kt").asFile
        out.parentFile.mkdirs(); out.writeText(sb.toString())
    }
}

// DESIGN 3.4: the prototype's ICONS map is the source icon set. Each SVG element becomes SVG path data (rect, circle
// and ellipse are converted here); IconBuilder.kt turns the data into ImageVectors (24 dp grid, round caps).
abstract class GenerateIcons : DefaultTask() {
    @get:InputFile abstract val prototype: RegularFileProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    private fun n(d: Double): String {
        val r = Math.round(d * 1000.0) / 1000.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun toPath(tag: String, a: Map<String, String>): String {
        fun d(k: String, def: Double = 0.0) = a[k]?.toDouble() ?: def
        return when (tag) {
            "path" -> requireNotNull(a["d"]) { "path without d" }
            "rect" -> {
                val x = d("x"); val y = d("y"); val w = d("width"); val h = d("height")
                val rx = minOf(d("rx", d("ry")), w / 2, h / 2)
                if (rx <= 0.0) "M${n(x)} ${n(y)}H${n(x + w)}V${n(y + h)}H${n(x)}Z"
                else "M${n(x + rx)} ${n(y)}H${n(x + w - rx)}A${n(rx)} ${n(rx)} 0 0 1 ${n(x + w)} ${n(y + rx)}" +
                    "V${n(y + h - rx)}A${n(rx)} ${n(rx)} 0 0 1 ${n(x + w - rx)} ${n(y + h)}" +
                    "H${n(x + rx)}A${n(rx)} ${n(rx)} 0 0 1 ${n(x)} ${n(y + h - rx)}" +
                    "V${n(y + rx)}A${n(rx)} ${n(rx)} 0 0 1 ${n(x + rx)} ${n(y)}Z"
            }
            "circle", "ellipse" -> {
                val cx = d("cx"); val cy = d("cy")
                val rx = if (tag == "circle") d("r") else d("rx"); val ry = if (tag == "circle") d("r") else d("ry")
                "M${n(cx - rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx + rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx - rx)} ${n(cy)}Z"
            }
            else -> error("unsupported SVG element <$tag>")
        }
    }

    @TaskAction
    fun run() {
        val html = prototype.get().asFile.readText()
        val start = html.indexOf("const ICONS = {"); val end = html.indexOf("\n};", start)
        require(start >= 0 && end > start) { "ICONS map not found in ${prototype.get().asFile}" }
        val line = Regex("""^\s*([A-Za-z0-9]+):'(.*)',?\s*$""")
        val el = Regex("""<(path|rect|circle|ellipse)\s+([^>]*?)\s*/>""")
        val attr = Regex("""([a-zA-Z-]+)="([^"]*)"""")
        val icons = linkedMapOf<String, List<String>>()
        for (l in html.substring(start, end).lines().drop(1)) {
            val m = line.matchEntire(l) ?: continue
            val elements = el.findAll(m.groupValues[2]).map { e ->
                val a = attr.findAll(e.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                val path = toPath(e.groupValues[1], a)
                val alpha = a["opacity"]?.toFloat() ?: 1f
                if (a["class"] == "f") "IconPath(\"$path\", filled = true, alpha = ${alpha}f)" else "IconPath(\"$path\")"
            }.toList()
            require(elements.isNotEmpty()) { "icon ${m.groupValues[1]} has no drawable elements" }
            icons[m.groupValues[1]] = elements
        }
        require(icons.size >= 45) { "expected at least 45 prototype icons, found ${icons.size}" }
        val sb = StringBuilder()
        sb.append("// GENERATED from docs/design/prototype/index.html (ICONS) by :ui:design:generateIcons. Do not edit.\n")
        sb.append("package app.murmur.ui.design\n\nimport androidx.compose.ui.graphics.vector.ImageVector\n\n")
        sb.append("internal object IconData {\n    val paths: Map<String, List<IconPath>> = linkedMapOf(\n")
        icons.forEach { (k, v) -> sb.append("        \"$k\" to listOf(${v.joinToString(", ")}),\n") }
        sb.append("    )\n}\n\n")
        sb.append("/** The prototype icon set (DESIGN 3.4): 24 dp grid, 1.75 dp stroke, round caps and joins; the mark at 2.25. */\n")
        sb.append("object MurmurIcons {\n")
        icons.keys.forEach { k -> sb.append("    val $k: ImageVector by lazy { buildIcon(\"$k\") }\n") }
        sb.append("\n    /** Every icon by its prototype name. */\n")
        sb.append("    val all: Map<String, ImageVector> by lazy { linkedMapOf(${icons.keys.joinToString(", ") { "\"$it\" to $it" }}) }\n")
        sb.append("\n    /** The same glyph at the bold 2.25 dp stroke (prototype class b2: in-use check, step ticks). */\n")
        sb.append("    fun bold(name: String): ImageVector = boldIcon(name)\n")
        sb.append("}\n")
        val out = outDir.get().file("app/murmur/ui/design/GeneratedIcons.kt").asFile
        out.parentFile.mkdirs(); out.writeText(sb.toString())
    }
}

val genTokens = tasks.register<GenerateDesignTokens>("generateDesignTokens") {
    tokens.set(rootDir.resolve("docs/design/tokens.json"))
}
val genIcons = tasks.register<GenerateIcons>("generateIcons") {
    prototype.set(rootDir.resolve("docs/design/prototype/index.html"))
}
androidComponents {
    onVariants { v ->
        v.sources.kotlin?.addGeneratedSourceDirectory(genTokens, GenerateDesignTokens::outDir)
        v.sources.kotlin?.addGeneratedSourceDirectory(genIcons, GenerateIcons::outDir)
    }
}

// Unit tests read docs/design (tokens.json, prototype ICONS) to check the generated code against its source.
tasks.withType<Test>().configureEach {
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
    inputs.file(rootDir.resolve("docs/design/tokens.json"))
    inputs.file(rootDir.resolve("docs/design/prototype/index.html"))
}
