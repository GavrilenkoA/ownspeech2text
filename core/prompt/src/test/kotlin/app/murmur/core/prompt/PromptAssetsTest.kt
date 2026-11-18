package app.murmur.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

class PromptAssetsTest {
    private val assets = ClasspathPromptAssets()
    private val root = File(System.getProperty("murmur.repoRoot") ?: ".")

    @Test
    fun packagedPromptsAreByteIdenticalToTheRepo() {
        // D8: what the APK ships is exactly prompts/ (no hand copies).
        for (rel in listOf("system/default.v4.md", "profiles/default.v4.json", "shots/default.v4.jsonl", "tones/tones.v1.json")) {
            assertEquals(rel, File(root, "prompts/$rel").readText(), assets.text(rel))
        }
    }

    @Test
    fun shippedProfileParses() {
        val p = assets.profile("default.v4")
        assertEquals("default.v4", p.system)
        assertTrue(assets.shots(p.shots!!).isNotEmpty())
    }

    @Test
    fun editRules() {
        assertEquals(listOf("markup"), PromptEditRules.validateSystem("hello <context> x"))
        assertTrue(PromptEditRules.validateSystem("Fix punctuation.").isEmpty())
    }

    @Test
    fun userOverrideNeverAppliesToFineTunes() {
        // ADR-046: the edited instructions belong to the stock profile they were written for.
        assertTrue(PromptEditRules.overrideApplies(assets.profile("default.v4"), "default.v4"))
        assertTrue(!PromptEditRules.overrideApplies(assets.profile("default.v4s"), "default.v4"))
        assertTrue(!PromptEditRules.overrideApplies(assets.profile("finetuned.v1"), "finetuned.v1"))
        assertTrue(!PromptEditRules.overrideApplies(assets.profile("default.v4"), null))
        assertEquals("finetuned.v1", assets.profile("finetuned.v1").system)
    }

    @Ignore("WP-04 enables: Kotlin renderer must reproduce prompts/examples/ctxv2_* byte-for-byte")
    @Test
    fun goldenCtxV2Prompts() {
        val r = RenderPyPort(assets)
        val dir = File(root, "prompts/examples")
        for (inp in dir.listFiles { f -> f.name.startsWith("ctxv2_") && f.name.endsWith(".input.json") }!!) {
            val stem = inp.name.removeSuffix(".input.json")
            for (tpl in listOf("qwen3.5", "lfm3")) {
                val expected = File(dir, "$stem.$tpl.default.v4.txt").readText()
                val g = kotlinx.serialization.json.Json.parseToJsonElement(inp.readText()) as kotlinx.serialization.json.JsonObject
                val ctx = app.murmur.core.context.CtxV2.json.decodeFromJsonElement(app.murmur.core.context.CtxV2.serializer(), g.getValue("context"))
                val transcript = (g.getValue("transcript") as kotlinx.serialization.json.JsonPrimitive).content
                assertEquals("$stem/$tpl", expected, r.render(RenderInput(assets.profile("default.v4"), tpl, ctx, transcript)).full)
            }
        }
    }
}
