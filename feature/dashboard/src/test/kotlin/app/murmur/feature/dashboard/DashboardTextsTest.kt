package app.murmur.feature.dashboard

import app.murmur.core.store.DashboardStats
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.testing.contrib.Wp12MetricsFixture.TERMUX
import app.murmur.testing.contrib.Wp12MetricsFixture.TODAY
import app.murmur.testing.contrib.Wp12MetricsFixture.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Every dashboard text for the WP-12 fixture in English and Russian, read from the shipped res/ files, plus the
 * wording edge cases of DESIGN 6.2: time saved hidden when ≤ 0, "4 copied only, all in Termux", plural forms, the
 * Today range and missing values.
 */
class DashboardTextsTest {
    private val en = ResFileStrings(ResFileStrings.EN)
    private val ru = ResFileStrings(ResFileStrings.RU)
    private val calc = DefaultStatsCalculator()

    @Test
    fun fixtureEnglish() = FixtureTexts.assertTexts(FixtureTexts.en, FixtureTexts.formatter(en).format(FixtureTexts.ready()))

    @Test
    fun fixtureRussian() = FixtureTexts.assertTexts(FixtureTexts.ru, FixtureTexts.formatter(ru).format(FixtureTexts.ready()))

    @Test
    fun timeSavedHiddenWhenNotPositive() {
        // 200 wpm: 0.92 min of typing < 1.26 min of speaking -> the calculator returns null, only speaking is shown.
        val fast = FixtureTexts.ready(wpm = 200)
        assertNull(fast.week.timeSavedMin)
        val t = FixtureTexts.formatter(en).format(fast).today
        assertEquals("1 min of speaking.", t.sentence.text)
        assertTrue(t.sentence.boldStart < 0)
        assertEquals("1 мин речи.", FixtureTexts.formatter(ru).format(fast).today.sentence.text)
    }

    @Test
    fun timeSavedHiddenWhenTheShownMinutesCancelOut() {
        // 90 words at 40 wpm = 2.25 -> "2 min" of typing for 1.6 min ("2 min") of speaking: 0.65 min > 0 for the
        // calculator, but "roughly 0 min saved" is not shown.
        val rows = listOf(row(TODAY, TERMUX, wordsFinal = 90, recordMs = 100_000, speechMs = 96_000, stopToDoneMs = 1_000))
        val ready = FixtureTexts.ready(rows)
        assertTrue(ready.week.timeSavedMin!! > 0)
        assertEquals("2 min of speaking.", FixtureTexts.formatter(en).format(ready).today.sentence.text)
    }

    @Test
    fun copiedAllInOneApp() {
        val ready = FixtureTexts.ready(FixtureTexts.allInTermux())
        assertEquals(4, ready.copiedInTopApp)
        val e = FixtureTexts.formatter(en).format(ready).metrics[2]
        assertEquals("94", e.value) // 60 / 64 = 93.75 %
        assertEquals("4 copied only, all in Termux", e.subtitle)
        assertEquals("4 диктовки только скопированы, все в Termux", FixtureTexts.formatter(ru).format(ready).metrics[2].subtitle)
    }

    @Test
    fun copiedWordingVariants() {
        val f = FixtureTexts.formatter(en)
        val r = FixtureTexts.formatter(ru)
        fun line(f: DashboardFormatter, n: Int, inTop: Int) =
            f.metrics(stats(copied = n, top = TERMUX), DashboardRange.Week, inTop)[2].subtitle
        assertEquals("1 copied only, in Termux", line(f, 1, 1))
        assertEquals("1 диктовка только скопирована, в Termux", line(r, 1, 1))
        assertEquals("5 copied only, most in Termux", line(f, 5, 3))
        assertEquals("5 диктовок только скопированы, в основном в Termux", line(r, 5, 3))
        assertEquals("6 copied only, 2 in Termux", line(f, 6, 2))
        assertEquals("6 диктовок только скопированы, 2 — в Termux", line(r, 6, 2))
        assertEquals("21 диктовка только скопирована, все в Termux", line(r, 21, 21))
        assertEquals("Every dictation went straight in", f.metrics(stats(copied = 0, top = null), DashboardRange.Week, 0)[2].subtitle)
        assertEquals("Все диктовки вставлены сразу", r.metrics(stats(copied = 0, top = null), DashboardRange.Week, 0)[2].subtitle)
    }

    @Test
    fun typedPercentNeverClaimsAllOrNothingWrongly() {
        assertEquals(99, DashboardFormatter.typedPercent(0.996, copied = 1))
        assertEquals(100, DashboardFormatter.typedPercent(1.0, copied = 0))
        assertEquals(1, DashboardFormatter.typedPercent(0.004, copied = 250))
        assertEquals(0, DashboardFormatter.typedPercent(0.0, copied = 3))
        assertEquals("<1", DashboardFormatter.changedPercent(0.004))
        assertEquals("0", DashboardFormatter.changedPercent(0.0))
        assertEquals("9", DashboardFormatter.changedPercent(0.09))
    }

    @Test
    fun russianPluralsOneFewMany() {
        assertEquals("слово", ru.plural(R.plurals.dash_words_unit, 1))
        assertEquals("слово", ru.plural(R.plurals.dash_words_unit, 21))
        assertEquals("слова", ru.plural(R.plurals.dash_words_unit, 184))
        assertEquals("слов", ru.plural(R.plurals.dash_words_unit, 11))
        assertEquals("слова", ru.plural(R.plurals.dash_words_unit, 1284))
        assertEquals("слов", ru.plural(R.plurals.dash_words_unit, 1285))
        assertEquals("word", en.plural(R.plurals.dash_words_unit, 1))
        assertEquals("words", en.plural(R.plurals.dash_words_unit, 0))
        val f = FixtureTexts.formatter(ru)
        assertEquals("Ещё 1 приложение · 1 слово", f.apps(stats(others = 1, otherWords = 1), DashboardRange.Week).others)
        assertEquals("Ещё 22 приложения · 1\u00A0284 слова", f.apps(stats(others = 22, otherWords = 1284), DashboardRange.Week).others)
        assertEquals("1 other app · 1 word", FixtureTexts.formatter(en).apps(stats(others = 1, otherWords = 1), DashboardRange.Week).others)
    }

    @Test
    fun numbersUseTheLocaleFormat() {
        assertEquals("1,284", FixtureTexts.formatter(en).int(1284))
        assertEquals("1\u00A0284", FixtureTexts.formatter(ru).int(1284))
    }

    @Test
    fun todayRangeChangesTheScopedTexts() {
        val ready = FixtureTexts.ready(range = DashboardRange.Today)
        val t = FixtureTexts.formatter(en).format(ready)
        assertEquals("Median of today, pauses excluded", t.metrics[0].subtitle)
        assertEquals("words, today", t.apps.caption)
        // Today only: Telegram 150, Termux 30, WhatsApp 4; the failed Gmail row is not an app.
        assertEquals(listOf("Telegram" to "150", "Termux" to "30", "com.whatsapp" to "4"), t.apps.rows.map { it.label to it.words })
        assertNull(t.apps.others)
        assertEquals("1 copied only, in Termux", t.metrics[2].subtitle)
        // The card and the chart stay on the week: the chip scopes the sections below them.
        assertEquals("414 words", t.week.total)
        val r = FixtureTexts.formatter(ru).format(ready)
        assertEquals("Медиана за сегодня, без пауз", r.metrics[0].subtitle)
        assertEquals("слов за сегодня", r.apps.caption)
    }

    @Test
    fun missingValuesAreDashes() {
        val none = stats().copy(typedShare = null)
        val m = FixtureTexts.formatter(en).metrics(none, DashboardRange.Week, 0)
        assertEquals(listOf(null, null, null, null), m.map { it.value })
        assertEquals(listOf(null, null, null, null), m.map { it.unit })
        assertEquals("Nothing cleaned up yet", m[3].subtitle)
        val empty = emptyMetrics(en)
        assertEquals(
            listOf(
                "Speaking pace" to "Words per minute while you talk",
                "Ready after you stop" to "How long until the text appears",
                "Typed into the app" to "Inserted directly vs. copied only",
            ),
            empty.map { it.title to it.subtitle },
        )
        assertEquals("Что здесь будет", ru.get(R.string.dash_empty_metrics))
        assertEquals(listOf("Темп речи", "Готово после остановки", "Вставлено в приложение"), emptyMetrics(ru).map { it.title })
    }

    @Test
    fun todayWithNoDictationYet() {
        val rows = Wp12MetricsFixture.week.filter { it.localDay != TODAY }
        val t = FixtureTexts.formatter(en).format(FixtureTexts.ready(rows)).today
        assertEquals("0", t.number)
        assertEquals("words", t.unit)
        assertTrue(t.isZero)
        assertEquals("Nothing dictated yet today.", t.sentence.text)
    }

    @Test
    fun chartScaleAndMaxLabel() {
        assertEquals(3000, DashboardFormatter.niceMax(2950))
        assertEquals(1300, DashboardFormatter.niceMax(1284))
        assertEquals(190, DashboardFormatter.niceMax(184))
        assertEquals(7, DashboardFormatter.niceMax(7))
        assertEquals(1, DashboardFormatter.niceMax(0))
        val w = FixtureTexts.formatter(en).week(stats(days = listOf(5, 9, 0, 9, 1, 2, 3)))
        assertEquals("ties label the most recent day", 3, w.maxIndex)
        val zero = FixtureTexts.formatter(en).week(stats(days = List(7) { 0 }))
        assertEquals(-1, zero.maxIndex)
        assertNull(zero.maxLabel)
        assertEquals(1f, FixtureTexts.formatter(en).week(stats(days = listOf(0, 0, 0, 0, 0, 0, 7))).days[6].fraction)
    }

    @Test
    fun languagesAddUpToHundredAndKeepMixedLast() {
        val l = FixtureTexts.formatter(en).languages(linkedMapOf(DefaultStatsCalculator.MIXED to 5, "ru" to 1, "en" to 1, "de" to 1, "uk" to 1))
        assertEquals(100, l.sumOf { it.percent })
        assertEquals(LangTone.Mixed, l.last().tone)
        // Three named languages at most, then "Other".
        assertEquals(listOf("German", "English", "Russian", "Other", "Mixed"), l.map { it.label })
        assertEquals(listOf(34, 33, 33), DashboardFormatter.largestRemainder(listOf(1, 1, 1), 100))
        assertTrue(FixtureTexts.formatter(en).languages(emptyMap()).isEmpty())
    }

    @Test
    fun everyKeyIsTranslated() {
        val (s, p, a) = en.keys()
        val (rs, rp, ra) = RuOnly.keys()
        assertEquals("strings missing in values-ru", emptySet<String>(), s - rs)
        assertEquals("plurals missing in values-ru", emptySet<String>(), p - rp)
        assertEquals("arrays missing in values-ru", emptySet<String>(), a - ra)
        for (name in p) {
            assertEquals("$name: one/few/many/other", setOf("one", "few", "many", "other"), RuOnly.pluralForms(name).keys)
        }
        assertEquals(7, ru.arrayByName("dash_days_short").size)
        // D10: the product name is never written literally.
        assertFalse(en.get(R.string.dash_off_body, "X").contains("Murmur"))
        assertEquals(
            "Android turned off X's accessibility service, usually after an update or a crash. Turn it on again to dictate.",
            en.get(R.string.dash_off_body, "X"),
        )
    }

    /** values-ru alone (without the English base), for the completeness check. */
    private object RuOnly {
        private val file = java.io.File(System.getProperty("murmur.dashboardRes"), "values-ru/strings.xml")
        private val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        private fun names(tag: String): Set<String> {
            val n = doc.getElementsByTagName(tag)
            return (0 until n.length).map { (n.item(it) as org.w3c.dom.Element).getAttribute("name") }.toSet()
        }
        fun keys() = Triple(names("string"), names("plurals"), names("string-array"))
        fun pluralForms(name: String): Map<String, String> {
            val n = doc.getElementsByTagName("plurals")
            val e = (0 until n.length).map { n.item(it) as org.w3c.dom.Element }.first { it.getAttribute("name") == name }
            val items = e.getElementsByTagName("item")
            return (0 until items.length).associate { (items.item(it) as org.w3c.dom.Element).let { i -> i.getAttribute("quantity") to i.textContent } }
        }
    }

    private fun stats(
        copied: Int = 0,
        top: String? = null,
        others: Int = 0,
        otherWords: Int = 0,
        days: List<Int> = List(7) { 0 },
    ) = DashboardStats(
        todayWords = days.last(), todaySpeakingMin = 0.0, timeSavedMin = null,
        last7Days = days.mapIndexed { i, w -> LocalDate.of(2026, 11, 12 + i) to w },
        paceWpmMedian = null, readyMedianMs = null, readyP90Ms = null,
        typedShare = if (copied > 0) 0.5 else 1.0, copiedOnlyCount = copied, copiedOnlyTopApp = top,
        changedShare = null, fillersRemoved = 0, fixes = 0, languages = emptyMap(),
        topApps = if (others > 0) listOf("a" to 5) else emptyList(), otherAppsCount = others, otherAppsWords = otherWords,
    )
}
