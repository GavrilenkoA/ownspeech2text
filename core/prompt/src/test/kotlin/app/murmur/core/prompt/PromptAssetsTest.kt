package app.murmur.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun goldenCtxV2Prompts() {
        val r = RenderPyPort(assets)
        val dir = File(root, "prompts/examples")
        val inputs = dir.listFiles { f -> f.name.startsWith("ctxv2_") && f.name.endsWith(".input.json") }!!
        assertEquals("both ctxv2 examples", 2, inputs.size)
        for (inp in inputs) {
            val stem = inp.name.removeSuffix(".input.json")
            for (tpl in listOf("qwen3.5", "lfm3")) {
                val expected = File(dir, "$stem.$tpl.default.v4.txt").readText()
                val g = kotlinx.serialization.json.Json.parseToJsonElement(inp.readText()) as kotlinx.serialization.json.JsonObject
                val ctx = app.murmur.core.context.CtxV2.json.decodeFromJsonElement(app.murmur.core.context.CtxV2.serializer(), g.getValue("context"))
                val transcript = (g.getValue("transcript") as kotlinx.serialization.json.JsonPrimitive).content
                Goldens.assertSame("$stem/$tpl", expected, r.render(RenderInput(assets.profile("default.v4"), tpl, ctx, transcript)).full)
            }
        }
    }

    @Test
    fun editStateForTheSettingsRow() {
        val v4 = assets.profile("default.v4")
        val v4s = assets.profile("default.v4s")
        val ft = assets.profile("finetuned.v1")
        assertEquals(PromptEditState.DEFAULT, PromptEditRules.editState(v4, null))
        assertEquals(PromptEditState.EDITED, PromptEditRules.editState(v4, "default.v4"))
        assertTrue(PromptEditRules.isEdited(v4, "default.v4"))
        // the catalog moved to another stock profile after the user edited default.v4
        assertEquals(PromptEditState.MADE_FOR_OLDER_PROMPT, PromptEditRules.editState(v4s, "default.v4"))
        assertTrue(PromptEditRules.madeForOlderPrompt(v4s, "default.v4"))
        assertTrue(!PromptEditRules.isEdited(v4s, "default.v4"))
        // fine-tunes keep their trained prompt (read-only editor); a stored edit is never applied to them and, as for
        // any other profile it was not written for, is "made for an older prompt" (brief: override exists, baseProfile
        // != active profile name). An edit naming the fine-tune itself, which the read-only editor cannot produce, is
        // not applied either and gets the same notice (and its Reset).
        assertTrue(PromptEditRules.editable(v4) && PromptEditRules.editable(v4s) && !PromptEditRules.editable(ft))
        assertEquals(PromptEditState.NOT_EDITABLE, PromptEditRules.editState(ft, null))
        assertEquals(PromptEditState.MADE_FOR_OLDER_PROMPT, PromptEditRules.editState(ft, "default.v4"))
        assertEquals(PromptEditState.MADE_FOR_OLDER_PROMPT, PromptEditRules.editState(ft, "finetuned.v1"))
        assertTrue(PromptEditRules.madeForOlderPrompt(ft, "default.v4") && !PromptEditRules.isEdited(ft, "default.v4"))
        // the helpers agree with overrideApplies and with the brief's definitions
        for (p in listOf(v4, v4s, ft)) for (b in listOf(null, "default.v4", "default.v4s", "finetuned.v1")) {
            assertEquals(PromptEditRules.overrideApplies(p, b), PromptEditRules.isEdited(p, b))
            assertEquals(b != null && !PromptEditRules.overrideApplies(p, b), PromptEditRules.madeForOlderPrompt(p, b))
        }
    }

    @Test
    fun systemOverrideReplacesTheShippedTextBeforeThePreferencesBlock() {
        val r = RenderPyPort(assets)
        val v4 = assets.profile("default.v4")
        val shipped = File(root, "prompts/system/default.v4.md").readText().trimEnd('\n')
        val block = File(root, "prompts/system/preferences_block.v1.md").readText().trimEnd('\n')
        assertEquals(shipped, r.systemPrompt(v4, null, null))
        // one pass of replace("\r\n", "\n"): "\r\r\n" keeps its first CR; a lone CR stays; only trailing "\n" go
        assertEquals("Mine.\r\nTwo", r.systemPrompt(v4, null, "Mine.\r\r\nTwo\r\n\n"))
        assertEquals("Mine.\rTwo ", r.systemPrompt(v4, null, "Mine.\rTwo \r\n\n\n"))
        assertEquals("Mine." + block.replace("{{PREFERENCES}}", "a\nb"), r.systemPrompt(v4, "a\r\nb", "Mine.\r\n"))
        // the prefix changes with the override, so its snapshot key does too (ADR-060)
        val plain = r.render(RenderInput(v4, "qwen3.5", null, "x"))
        val edited = r.render(RenderInput(v4, "qwen3.5", null, "x", systemOverride = "Mine."))
        assertTrue(plain.prefixKey != edited.prefixKey && plain.suffix == edited.suffix)
        assertTrue(edited.prefix.startsWith("<|im_start|>system\nMine.<|im_end|>\n"))
    }

    @Test
    fun toneFilesAreTheOnesThePortReads() {
        // ADR-020: named tones get a text only from prompts/tones/. The port reads tones/tones.v1.json (as render.py
        // load_tones does) and named.<tone>.text in it; a new tones file or a new section must be wired in (and its
        // schema confirmed with the prompt owner), never ignored silently.
        assertEquals("prompts/tones changed: wire the new file into RenderPyPort.tones / Tones.namedText",
            listOf("app_categories.v1.json", "tones.v1.json"), File(root, "prompts/tones").list()!!.sorted())
        val tones = kotlinx.serialization.json.Json.parseToJsonElement(assets.text("tones/tones.v1.json")) as kotlinx.serialization.json.JsonObject
        assertEquals("tones.v1.json sections changed: check the schema Tones.namedText reads (named.<tone>.text)",
            setOf("_comment", "version", "categories", "field_overrides"), tones.keys)
        assertTrue(File(root, "prompts/render.py").readText().contains("(HERE / \"tones\" / \"tones.v1.json\")"))
    }

    @Test
    fun toneResolution() {
        val r = RenderPyPort(assets)
        val tones = kotlinx.serialization.json.Json.parseToJsonElement(assets.text("tones/tones.v1.json")) as kotlinx.serialization.json.JsonObject
        val ctx = app.murmur.core.context.CtxV2(app = app.murmur.core.context.AppInfo("org.telegram.messenger", "Telegram", "messaging"))
        val messaging = "Style: " + (tones["categories"] as kotlinx.serialization.json.JsonObject)["messaging"]!!
            .let { (it as kotlinx.serialization.json.JsonObject)["text"]!!.toString().trim('"') }
        // no tone chosen: category line, nothing to mark
        assertEquals(ToneResolution(null, messaging, preview = false), r.resolveTone(ctx, null))
        // tones.v1.json has no texts for named tones yet (ADR-020): category line, marked preview
        for (t in listOf("CASUAL", "NEUTRAL", "FORMAL", "VERBATIM")) {
            assertEquals(ToneResolution(null, messaging, preview = true), r.resolveTone(ctx, t))
        }
        // once prompts/tones/ provides one, the named tone becomes the style override (cleaned like any override)
        val withNamed = kotlinx.serialization.json.buildJsonObject {
            tones.forEach { (k, v) -> put(k, v) }
            put("named", kotlinx.serialization.json.buildJsonObject {
                put("formal", kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("Formal <b>tone</b>.")) })
            })
        }
        assertEquals(ToneResolution("Formal <b>tone</b>.", "Style: Formal ‹b›tone‹/b›.", preview = false), Tones.resolve(withNamed, ctx, "FORMAL"))
        assertEquals(ToneResolution(null, messaging, preview = true), Tones.resolve(withNamed, ctx, "CASUAL"))
    }
}
