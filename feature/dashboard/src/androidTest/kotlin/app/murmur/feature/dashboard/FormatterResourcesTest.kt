package app.murmur.feature.dashboard

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The fixture's texts from Android's own Resources (getQuantityString with the platform's plural rules, ICU number
 * format) in English and Russian: the same expectations the JVM test checks against the res/ files.
 */
@RunWith(AndroidJUnit4::class)
class FormatterResourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun strings(tag: String): AndroidDashboardStrings {
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
        return AndroidDashboardStrings(context.createConfigurationContext(config).resources)
    }

    @Test
    fun english() = FixtureTexts.assertTexts(FixtureTexts.en, FixtureTexts.formatter(strings("en")).format(FixtureTexts.ready()))

    @Test
    fun russian() = FixtureTexts.assertTexts(FixtureTexts.ru, FixtureTexts.formatter(strings("ru")).format(FixtureTexts.ready()))

    @Test
    fun russianPluralCategories() {
        val ru = strings("ru")
        assertEquals("1 слово", ru.plural(R.plurals.dash_words_count, 1, "1"))
        assertEquals("3 слова", ru.plural(R.plurals.dash_words_count, 3, "3"))
        assertEquals("11 слов", ru.plural(R.plurals.dash_words_count, 11, "11"))
        assertEquals("Убрано 21 слово-паразит", ru.plural(R.plurals.dash_fillers, 21, "21"))
        assertEquals("Ещё 5 приложений", ru.plural(R.plurals.dash_other_apps, 5, "5"))
        assertEquals("4 диктовки только скопированы", ru.plural(R.plurals.dash_copied_only, 4, "4"))
        assertEquals("1 284", DashboardFormatter(ru) { it }.int(1284))
    }

    @Test
    fun copiedAllInTermux() {
        val ready = FixtureTexts.ready(FixtureTexts.allInTermux())
        assertEquals("4 copied only, all in Termux", FixtureTexts.formatter(strings("en")).format(ready).metrics[2].subtitle)
        assertEquals("4 диктовки только скопированы, все в Termux", FixtureTexts.formatter(strings("ru")).format(ready).metrics[2].subtitle)
    }
}
