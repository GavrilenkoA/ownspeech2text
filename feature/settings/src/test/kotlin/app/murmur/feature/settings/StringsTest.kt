package app.murmur.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** EN and RU copy (DESIGN 7): the same keys, the same format arguments, Russian plurals with one/few/many, no product name. */
class StringsTest {
    private val res = File(requireNotNull(System.getProperty("murmur.repoRoot")), "feature/settings/src/main/res")

    private fun read(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = linkedMapOf<String, List<String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i)
            out[e.attributes.getNamedItem("name").nodeValue] = listOf(e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i)
            val items = e.childNodes
            val texts = (0 until items.length).map { items.item(it) }.filter { it.nodeName == "item" }
                .map { it.attributes.getNamedItem("quantity").nodeValue + "=" + it.textContent }
            out["plurals:" + e.attributes.getNamedItem("name").nodeValue] = texts
        }
        return out
    }

    private fun args(s: String): Set<String> = Regex("""%(\d+\$)?[sd]""").findAll(s).map { it.value }.toSet()

    @Test
    fun russianHasEveryKeyWithTheSameArguments() {
        val en = read("values")
        val ru = read("values-ru")
        assertEquals(en.keys, ru.keys)
        for ((k, v) in en) {
            val enArgs = v.flatMap { args(it) }.toSet()
            val ruArgs = ru.getValue(k).flatMap { args(it) }.toSet()
            assertEquals("arguments of $k", enArgs, ruArgs)
        }
    }

    @Test
    fun russianPluralsHaveOneFewMany() {
        read("values-ru").filterKeys { it.startsWith("plurals:") }.forEach { (k, items) ->
            val q = items.map { it.substringBefore('=') }.toSet()
            assertTrue("$k: $q", q.containsAll(setOf("one", "few", "many", "other")))
        }
    }

    @Test
    fun theProductNameIsNeverWrittenLiterally() {
        for (dir in listOf("values", "values-ru")) {
            read(dir).forEach { (k, v) -> v.forEach { assertFalse("$dir $k", it.contains("Murmur")) } }
        }
    }
}
