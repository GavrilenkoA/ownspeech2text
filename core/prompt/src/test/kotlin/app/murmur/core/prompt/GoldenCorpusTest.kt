package app.murmur.core.prompt

import app.murmur.core.prompt.Goldens.strings
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden corpus (core/prompt/tools/make_goldens.py, prompts/render.py as the oracle): every case x profile x
 * template rendered by the Kotlin port must equal the Python prefix and suffix byte for byte.
 */
class GoldenCorpusTest {
    private val assets = ClasspathPromptAssets()
    private val index = Goldens.json("index.json")
    private val profiles = index.getValue("profiles").strings()
    private val templates = index.getValue("templates").strings()
    private val perVariant = index.getValue("layout").jsonObject.getValue("profiles_per_variant").jsonObject

    @Test
    fun everyGoldenIsByteIdentical() {
        val renderer = RenderPyPort(assets)
        val c = Goldens.Collector("goldens")
        val seen = HashSet<Pair<String, String>>()
        var goldens = 0
        for (case in Goldens.cases) {
            for (prof in perVariant.getValue(case.variant).strings()) {
                for (tpl in templates) {
                    val label = "${case.id} $prof $tpl"
                    val prefixFile = "prefix/$prof.$tpl.${case.variant}.txt"
                    val expectedPrefix = Goldens.text(prefixFile)
                    val expectedSuffix = Goldens.text("suffix/$tpl/${case.id}.txt")
                    val input = RenderInput(assets.profile(prof), tpl, case.ctx, case.transcript, case.preferences,
                        case.styleOverride, case.systemOverride)
                    try {
                        val r = renderer.render(input)
                        c.check("$label prefix ($prefixFile)", expectedPrefix, r.prefix)
                        c.check("$label suffix", expectedSuffix, r.suffix)
                        c.check("$label full", expectedPrefix + expectedSuffix, r.full)
                        c.check("$label cachePrefix + suffix == full", r.full,
                            renderer.cachePrefix(input.profile, tpl, input.preferences, input.systemOverride) + r.suffix)
                        c.check("$label prefixKey", RenderPyPort.sha256Hex(expectedPrefix), r.prefixKey)
                    } catch (t: Throwable) {
                        c.error(label, t)
                    }
                    seen += prof to tpl
                    goldens++
                }
            }
        }
        c.done()
        assertEquals(index.getValue("counts").jsonObject.getValue("goldens").jsonPrimitive.int, goldens)
        assertTrue("at least 300 goldens, got $goldens", goldens >= 300)
        assertEquals("every profile x template is covered", profiles.size * templates.size, seen.size)
        assertEquals(setOf("qwen3.5", "qwen3", "lfm3", "lfm2.5"), templates.toSet())
        assertEquals(setOf("default.v1", "default.v4", "default.v4s", "finetuned.v1"), profiles.toSet()) // default.v1 = stock (ADR-085)
    }

    @Test
    fun prefixCharCountsMatchTheIndex() {
        val renderer = RenderPyPort(assets)
        val chars = index.getValue("prefix_chars").jsonObject
        val sha = index.getValue("prefix_sha256").jsonObject
        for (prof in profiles) for (tpl in templates) {
            val prefix = renderer.cachePrefix(assets.profile(prof), tpl, null, null)
            assertEquals("$prof $tpl (Python len = code points)", chars.getValue(prof).jsonObject.getValue(tpl).jsonPrimitive.int,
                Py.len(prefix))
            assertEquals("$prof $tpl", sha.getValue(prof).jsonObject.getValue(tpl).jsonPrimitive.content, RenderPyPort.sha256Hex(prefix))
        }
    }

    /** The case kinds the brief requires are present in the corpus (guards against a generator that lost them). */
    @Test
    fun corpusCoversTheRequiredCases() {
        val ids = Goldens.cases.map { it.id }
        val variants = Goldens.cases.map { it.variant }.toSet()
        assertTrue(variants.containsAll(listOf("prefs_short", "prefs_1000", "prefs_1500", "prefs_ctrl_crlf", "override_crlf")))
        val p1000 = Goldens.cases.first { it.variant == "prefs_1000" }.preferences!!
        val p1500 = Goldens.cases.first { it.variant == "prefs_1500" }.preferences!!
        assertEquals(1000, Py.len(p1000))
        assertTrue(p1000.length > 1000)  // non-BMP emoji: UTF-16 is longer
        assertEquals(1500, Py.len(p1500))
        assertTrue(Goldens.cases.first { it.variant == "prefs_ctrl_crlf" }.preferences!!.contains("\r\n"))
        val ov = Goldens.cases.first { it.variant == "override_crlf" }.systemOverride!!
        assertTrue(ov.contains("\r\n") && ov.endsWith("\n\n"))
        for (p in listOf("syn.style.", "syn.tone.", "syn.emoji.", "syn.bidi", "syn.angle", "syn.screen.empty_lines",
            "syn.privacy.password", "syn.before_cursor.long_spaces", "syn.budget.exact_1400", "syn.budget.over_1401")) {
            assertTrue(p, ids.any { it.startsWith(p) })
        }
        val tones = kotlinx.serialization.json.Json.parseToJsonElement(assets.text("tones/tones.v1.json")).jsonObject
        for (cat in tones.getValue("categories").jsonObject.keys) assertTrue(cat, "syn.tone.$cat" in ids)
        assertTrue(Goldens.cases.count { !it.id.startsWith("syn.") } >= 150)
    }
}
