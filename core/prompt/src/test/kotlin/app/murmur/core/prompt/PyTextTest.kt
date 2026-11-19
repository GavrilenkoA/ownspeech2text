package app.murmur.core.prompt

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Py] against CPython's own tables (goldens/unicode.json, written by make_goldens.py): every predicate and case
 * mapping for every code point that is assigned both in that Python's Unicode version and in this JVM's, and whose
 * Python result only uses characters this JVM knows (a mapping added in a newer Unicode cannot be expected here).
 */
class PyTextTest {
    private val u = Goldens.json("unicode.json")

    private fun set(name: String): java.util.BitSet {
        val b = java.util.BitSet(0x110000)
        for (r in u.getValue(name).jsonArray) {
            val a = r.jsonArray
            b.set(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int + 1)
        }
        return b
    }

    private fun map(name: String): Map<Int, String> =
        u.getValue(name).jsonObject.entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content }

    private val pyAssigned = set("assigned")
    private fun jvmAssigned(cp: Int) = Character.getType(cp) != Character.UNASSIGNED.toInt()
    private fun comparable(cp: Int) = cp !in 0xD800..0xDFFF && pyAssigned[cp] && jvmAssigned(cp)
    private fun known(s: String) = s.codePoints().allMatch { jvmAssigned(it) }

    private fun checkPredicate(name: String, f: (Int) -> Boolean) {
        val py = set(name)
        val bad = (0..0x10FFFF).filter { comparable(it) && py[it] != f(it) }
        assertEquals("$name differs at ${bad.take(10).map { "U+%04X".format(it) }}", 0, bad.size)
    }

    private fun checkMapping(name: String, f: (Int) -> String) {
        val py = map(name)
        var compared = 0
        val bad = ArrayList<Int>()
        for (cp in 0..0x10FFFF) {
            if (!comparable(cp)) continue
            val want = py[cp] ?: String(Character.toChars(cp))
            if (!known(want)) continue
            compared++
            if (f(cp) != want) bad += cp
        }
        assertEquals("$name differs at ${bad.take(10).map { "U+%04X".format(it) }}", 0, bad.size)
        assertTrue(compared > 250_000)
    }

    @Test fun isspace() = checkPredicate("isspace", Py::isSpace)
    @Test fun regexWord() = checkPredicate("word", Py::isWord)
    @Test fun isdecimal() = checkPredicate("isdecimal", Py::isDecimal)
    @Test fun isdigit() = checkPredicate("isdigit", Py::isDigit)
    @Test fun isalpha() = checkPredicate("isalpha", Py::isAlpha)
    @Test fun isupper() = checkPredicate("isupper", Py::isUpper)
    @Test fun islower() = checkPredicate("islower", Py::isLower)
    @Test fun casefold() = checkMapping("casefold", Py::casefold)
    @Test fun lower() = checkMapping("lower", Py::lower)
    @Test fun upper() = checkMapping("upper", Py::upper)

    @Test
    fun javaRegexClassesMeanPythonClasses() {
        val w = Regex("[${Py.W}]")
        val s = Regex("[${Py.S}]")
        for (cp in 0..0x10FFFF) {
            if (!comparable(cp)) continue
            val ch = String(Character.toChars(cp))
            assertEquals("\\w U+%04X".format(cp), Py.isWord(cp), w.matches(ch))
            assertEquals("\\s U+%04X".format(cp), Py.isSpace(cp), s.matches(ch))
        }
    }

    @Test
    fun codePointSlicing() {
        val s = "a🙂b"  // a, emoji, b
        assertEquals(3, Py.len(s))
        assertEquals("a🙂", Py.head(s, 2))
        assertEquals("🙂b", Py.tail(s, 2))
        assertEquals(s, Py.tail(s, 0))  // Python s[-0:]
        assertEquals("", Py.head(s, 0))
        assertEquals(listOf("a", "b", "c"), Py.split(" a b c\u0085"))
        assertEquals(listOf("x", "", "y"), Py.splitlines("x\r\n y\n"))
        assertEquals("STRASSE".lowercase(), Py.casefold("Straße"))
        assertEquals("οδοσ", Py.casefold("ΟΔΟΣ"))
        assertEquals("οδοσ", Py.casefold("οδος"))
    }
}
