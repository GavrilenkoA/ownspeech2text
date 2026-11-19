package app.murmur.core.prompt

import app.murmur.core.prompt.Goldens.str
import app.murmur.core.prompt.Goldens.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** goldens/render_extra.json: render_context with non-shipped ctx2 options, custom profiles, ocr_terms. */
class RenderExtraTest {
    private val assets = ClasspathPromptAssets()
    private val extra = Goldens.json("render_extra.json")
    private val profileJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Every render_context / ocr_terms call prompts/tests/test_render_v2.py makes ("the Kotlin port must pass the same cases"). */
    @Test
    fun testRenderV2SuiteCalls() {
        val c = Goldens.Collector("test_render_v2 calls")
        for ((n, e) in extra.getValue("render_v2_suite").jsonArray.withIndex()) {
            val o = e.jsonObject
            when (o.str("fn")) {
                "render_context" -> {
                    val ctx = Goldens.ctx(o["ctx"])!!
                    c.check("#$n render_context ${o["ctx2"]}", o.str("result"), RenderPy.renderContext(ctx, o["ctx2"] as? JsonObject))
                }
                "ocr_terms" -> c.check("#$n ocr_terms", o.getValue("result").strings(),
                    RenderPy.ocrTerms(o.getValue("texts").strings(), o.getValue("limit").jsonPrimitive.int))
                else -> error("unknown fn ${o["fn"]}")
            }
        }
        c.done()
        assertTrue(c.checked >= 20)
    }

    @Test
    fun contextWithNonShippedOptions() {
        val renderer = RenderPyPort(assets)
        val c = Goldens.Collector("render_context options")
        for (e in extra.getValue("context_options").jsonArray) {
            val o = e.jsonObject
            val case = Goldens.byId.getValue(o.str("case")!!)
            val ctx2 = o.getValue("ctx2").jsonObject
            try {
                c.check("${case.id} $ctx2", o.str("result"), renderer.renderContext(case.ctx!!, ctx2))
            } catch (t: Throwable) {
                c.error("${case.id} $ctx2", t)
            }
        }
        c.done()
        assertTrue(c.checked >= 300)
    }

    /** Profiles with ctx2 place=after, ctx2 order/terms, answer_prefix, other shots and a bare system name. */
    @Test
    fun customProfiles() {
        val renderer = RenderPyPort(assets)
        val c = Goldens.Collector("custom profiles")
        for (e in extra.getValue("custom_profiles").jsonArray) {
            val o = e.jsonObject
            val profile = profileJson.decodeFromJsonElement(PromptProfile.serializer(), o.getValue("profile"))
            val case = Goldens.byId.getValue(o.str("case")!!)
            val tpl = o.str("template")!!
            val r = renderer.render(RenderInput(profile, tpl, case.ctx, case.transcript, o.str("preferences")))
            val label = "${profile.name} ${case.id} $tpl"
            c.check("$label full", o.str("full"), r.full)
            c.check("$label prefix chars", o.getValue("prefix_chars").jsonPrimitive.int, Py.len(r.prefix))
        }
        c.done()
    }

    @Test
    fun ocrTermsOfEveryScreen() {
        val c = Goldens.Collector("ocr_terms")
        for (e in extra.getValue("ocr_terms").jsonArray) {
            val o = e.jsonObject
            c.check("${o.str("case")} limit ${o["limit"]}", o.getValue("result").strings(),
                RenderPy.ocrTerms(o.getValue("texts").strings(), o.getValue("limit").jsonPrimitive.int))
        }
        c.done()
        assertTrue(c.checked >= 200)
    }

    /** prompts/examples/ctxv2_*.context.txt (render_context with the shipped options). */
    @Test
    fun exampleContextBlocks() {
        val root = java.io.File(System.getProperty("murmur.repoRoot") ?: ".")
        val dir = java.io.File(root, "prompts/examples")
        val inputs = dir.listFiles { f -> f.name.startsWith("ctxv2_") && f.name.endsWith(".input.json") }!!.sorted()
        assertEquals(2, inputs.size)
        for (inp in inputs) {
            val g = Json.parseToJsonElement(inp.readText()).jsonObject
            val stem = inp.name.removeSuffix(".input.json")
            Goldens.assertSame(stem, java.io.File(dir, "$stem.context.txt").readText(),
                RenderPyPort(assets).renderContext(Goldens.ctx(g["context"])!!, null))
        }
    }
}
