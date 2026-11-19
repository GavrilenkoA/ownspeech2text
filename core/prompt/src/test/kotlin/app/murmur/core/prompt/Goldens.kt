package app.murmur.core.prompt

import app.murmur.core.context.CtxV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail

/** Reads core/prompt/src/test/resources/goldens (written by core/prompt/tools/make_goldens.py). */
object Goldens {
    fun text(path: String): String =
        Goldens::class.java.getResourceAsStream("/goldens/$path")?.use { it.readBytes().decodeToString() }
            ?: error("golden missing: $path (run python3 -I core/prompt/tools/make_goldens.py)")

    fun json(path: String): JsonObject = Json.parseToJsonElement(text(path)).jsonObject

    class Case(val id: String, val variant: String, val ctx: CtxV2?, val transcript: String, val styleOverride: String?,
               val preferences: String?, val systemOverride: String?)

    val cases: List<Case> by lazy {
        text("cases.jsonl").lineSequence().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            Case(o.str("id")!!, o.str("variant")!!, ctx(o["ctx"]), o.str("transcript")!!, o.str("style_override"),
                o.str("preferences"), o.str("system_override"))
        }.toList()
    }

    val byId: Map<String, Case> by lazy { cases.associateBy { it.id } }

    fun ctx(e: JsonElement?): CtxV2? =
        if (e == null || e is JsonNull) null else CtxV2.json.decodeFromJsonElement(CtxV2.serializer(), e)

    fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    /** Fails with the first differing offset (code points and UTF-16) and both sides around it, escaped. */
    fun assertSame(label: String, expected: String, actual: String) {
        val d = firstDiff(expected, actual) ?: return
        fail("$label: $d")
    }

    fun firstDiff(expected: String, actual: String): String? {
        if (expected == actual) return null
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val cp = expected.codePointCount(0, i)
        fun around(s: String) = escape(s.substring(maxOf(0, i - 40), minOf(s.length, i + 40)))
        return "first difference at code point $cp (UTF-16 $i; lengths ${expected.length}/${actual.length})\n" +
            "  expected …${around(expected)}…\n  actual   …${around(actual)}…"
    }

    fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 || c.code in 0x7F..0x9F || Character.getType(c) == Character.FORMAT.toInt() ->
                append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    /** Collects mismatches; fails once with a count and the first few. */
    class Collector(private val what: String) {
        private val failures = ArrayList<String>()
        var checked = 0
            private set

        fun check(label: String, expected: Any?, actual: Any?) {
            checked++
            if (expected == actual) return
            failures += if (expected is String && actual is String) "$label: ${firstDiff(expected, actual)}"
            else "$label: expected <$expected> but was <$actual>"
        }

        fun error(label: String, t: Throwable) {
            checked++
            failures += "$label: threw $t"
        }

        fun done() {
            if (failures.isNotEmpty()) {
                fail("$what: ${failures.size} of $checked differ\n" + failures.take(8).joinToString("\n"))
            }
        }
    }
}
