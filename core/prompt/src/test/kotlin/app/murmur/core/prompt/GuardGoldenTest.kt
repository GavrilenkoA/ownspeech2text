package app.murmur.core.prompt

import app.murmur.core.prompt.Goldens.str
import app.murmur.core.prompt.Goldens.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * goldens/guard.json: every call prompts/tests/test_guard.py makes (recorded while the Python suite runs; ctx v1
 * dicts restated as the ctx v2 the phone sends, with the Python verdict checked to be unchanged), the guard calls
 * of test_render_v2.py, and guard/precheck over the eval rows with typical model outputs.
 */
class GuardGoldenTest {
    private val g = GuardPyPort()
    private val golden = Goldens.json("guard.json")

    private fun verdict(o: JsonObject) = Triple(o.str("text"), o.str("action")!!.uppercase(), o.str("reason"))
    private fun GuardVerdict.triple() = Triple(text, action.name, reason)
    private fun precheckOf(o: JsonObject) = Precheck(o.getValue("skip").jsonPrimitive.boolean, o.str("transcript")!!, o.str("reason")!!)

    @Test
    fun versionAndFillers() {
        assertEquals(golden.str("guard_version"), GuardPyPort.GUARD_VERSION)
        assertEquals(golden.getValue("fillers").strings().toSet(), g.fillers)
    }

    @Test
    fun everyTestGuardCaseIsIdentical() {
        val c = Goldens.Collector("test_guard.py cases")
        val fns = HashSet<String>()
        for ((n, e) in golden.getValue("test_guard").jsonArray.withIndex()) {
            val o = e.jsonObject
            val a = o.getValue("args").jsonObject
            val fn = o.str("fn")!!
            fns += fn
            val ctx = Goldens.ctx(a["ctx"])
            val label = "#$n $fn ${a.str("transcript") ?: a.str("s") ?: a.str("term")}"
            val r = o.getValue("result")
            when (fn) {
                "precheck" -> c.check(label, precheckOf(r.jsonObject), g.precheck(a.str("transcript")!!))
                "guard" -> c.check(label, verdict(r.jsonObject), g.guard(a.str("transcript")!!, a.str("output")!!, ctx).triple())
                "repair" -> c.check(label, r.jsonPrimitive.content, g.repair(a.str("output")!!, a.str("transcript")!!, ctx))
                "fallback_text" -> c.check(label, r.jsonPrimitive.content, g.fallbackText(a.str("transcript")!!, ctx))
                "phonetic_key" -> c.check(label, r.jsonPrimitive.content, g.phoneticKey(a.str("s")!!))
                "spoken" -> c.check(label, r.jsonPrimitive.boolean, g.spoken(a.str("term")!!, a.str("transcript")!!))
                else -> error("unknown fn $fn")
            }
        }
        c.done()
        assertEquals(setOf("precheck", "guard", "repair", "fallback_text", "phonetic_key", "spoken"), fns)
        assertTrue("all cases of the Python suite, got ${c.checked}", c.checked >= 80)
    }

    @Test
    fun testRenderV2GuardCalls() {
        val c = Goldens.Collector("test_render_v2.py guard calls")
        for (e in golden.getValue("test_render_v2").jsonArray) {
            val o = e.jsonObject
            val a = o.getValue("args").jsonObject
            val ctx = Goldens.ctx(a["ctx"])
            when (o.str("fn")) {
                "screen_terms" -> c.check("screen_terms", o.getValue("result").strings(), g.screenTerms(ctx))
                "guard" -> c.check("guard", verdict(o.getValue("result").jsonObject),
                    g.guard(a.str("transcript")!!, a.str("output")!!, ctx).triple())
                else -> error("unknown fn ${o["fn"]}")
            }
        }
        c.done()
        assertEquals(2, c.checked)
    }

    @Test
    fun evalRowsPrecheck() {
        val c = Goldens.Collector("eval precheck")
        for (e in golden.getValue("eval_precheck").jsonArray) {
            val o = e.jsonObject
            c.check(o.str("case")!!, precheckOf(o.getValue("result").jsonObject), g.precheck(o.str("transcript")!!))
        }
        c.done()
    }

    @Test
    fun evalRowsGuard() {
        val c = Goldens.Collector("eval guard")
        val reasons = HashSet<String>()
        for (e in golden.getValue("eval_guard").jsonArray) {
            val o = e.jsonObject
            val case = Goldens.byId.getValue(o.str("case")!!)
            val want = verdict(o.getValue("result").jsonObject)
            reasons += want.third!!
            try {
                c.check("${case.id} «${Goldens.escape(o.str("output")!!.take(60))}»", want,
                    g.guard(case.transcript, o.str("output")!!, case.ctx).triple())
            } catch (t: Throwable) {
                c.error(case.id, t)
            }
        }
        c.done()
        assertTrue(c.checked >= 1000)
        assertTrue("the outputs exercise several rules: $reasons", reasons.size >= 6)
    }
}
