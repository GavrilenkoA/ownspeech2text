package app.murmur.feature.dashboard

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [DashboardStrings] for JVM tests: reads the module's `res/values/strings.xml` and, for Russian, overlays
 * `res/values-ru/strings.xml`, exactly the files the APK ships. Resource ids are mapped to names through the
 * generated R class. Plural categories follow CLDR for integers (en: one/other; ru: one/few/many), which is what
 * Android's `getQuantityString` applies; the instrumented FormatterResourcesTest checks the same texts against
 * real Resources.
 */
class ResFileStrings(override val locale: Locale) : DashboardStrings {
    private val res = File(requireNotNull(System.getProperty("murmur.dashboardRes")) { "murmur.dashboardRes not set" })
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val arrays = HashMap<String, List<String>>()

    init {
        load(File(res, "values/strings.xml"))
        if (locale.language != "en") {
            val f = File(res, "values-${locale.language}/strings.xml")
            require(f.isFile) { "no strings for ${locale.language}: $f" }
            load(f)
        }
    }

    private fun load(f: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> strings[name] = unescape(e.textContent)
                "plurals" -> plurals[name] = children(e, "item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
                "string-array" -> arrays[name] = children(e, "item").map { unescape(it.textContent) }
            }
        }
    }

    private fun children(e: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val n = e.childNodes
        for (i in 0 until n.length) (n.item(i) as? Element)?.takeIf { it.tagName == tag }?.let(out::add)
        return out
    }

    /** Android string resource escapes: \' \" \\ \n \t \uXXXX. */
    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> {
                        out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun name(type: String, id: Int): String {
        val cls = Class.forName("app.murmur.feature.dashboard.R\$$type")
        return cls.fields.first { it.getInt(null) == id }.name
    }

    override fun get(id: Int, vararg args: Any): String {
        val raw = requireNotNull(strings[name("string", id)]) { "missing string ${name("string", id)}" }
        return if (args.isEmpty()) raw else String.format(locale, raw, *args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val forms = requireNotNull(plurals[name("plurals", id)])
        val form = forms[category(count)] ?: forms.getValue("other")
        return if (args.isEmpty()) form else String.format(locale, form, *args)
    }

    override fun array(id: Int): List<String> = requireNotNull(arrays[name("array", id)])

    /** CLDR plural category of a non-negative integer. */
    fun category(n: Int): String = when (locale.language) {
        "ru" -> when {
            n % 10 == 1 && n % 100 != 11 -> "one"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
            else -> "many"
        }
        else -> if (n == 1) "one" else "other"
    }

    /** Every key of the base (English) table, for completeness checks. */
    fun keys(): Triple<Set<String>, Set<String>, Set<String>> = Triple(strings.keys, plurals.keys, arrays.keys)

    fun pluralForms(name: String): Map<String, String> = plurals.getValue(name)

    fun arrayByName(name: String): List<String> = arrays.getValue(name)

    companion object {
        val EN: Locale = Locale.ENGLISH
        val RU: Locale = Locale.forLanguageTag("ru")
    }
}
