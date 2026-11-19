package app.murmur.feature.dashboard

import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.ui.design.StaticAppInfoProvider
import org.junit.Assert.assertEquals
import java.time.LocalDate

/**
 * Expected dashboard copy for [Wp12MetricsFixture.week] (the fixture of WP-12's StatsCalculatorTest, whose numbers
 * are worked out there) in English and Russian. Shared by the JVM test (strings read from the res/ files) and the
 * instrumented test (strings from Android Resources, i.e. Android's own plural rules).
 *
 * Numbers behind the texts: today 184 words, 75.5 s of speech (1 min), 184 / 40 = 4.6 → 5 min of typing, so 5 − 1 =
 * 4 min saved (the calculator's 3.3 is positive, the shown figures add up); week 15, 5, 90, 0, 60, 60, 184 = 414;
 * pace median 155.1; ready 1.2 s / p90 2.6 s; 8 of 11 typed (73 %), 3 copied (2 in Termux: "most"); 36 / 444 = 8 %
 * changed, 21 fillers, 15 fixes; languages 6 ru, 4 en, 1 mixed (54.5 / 36.4 / 9.1 → 55 / 36 / 9); apps Telegram 165, Gmail 80, Chrome 60, Termux 50, Notes 40, 3 others 19.
 */
object FixtureTexts {
    const val NBSP = '\u00A0'

    val labels = StaticAppInfoProvider(
        mapOf(
            Wp12MetricsFixture.TELEGRAM to "Telegram",
            Wp12MetricsFixture.GMAIL to "Gmail",
            Wp12MetricsFixture.CHROME to "Chrome",
            Wp12MetricsFixture.TERMUX to "Termux",
            Wp12MetricsFixture.NOTES to "Samsung Notes",
        ),
    )

    fun formatter(s: DashboardStrings) = DashboardFormatter(s) { labels.label(it) ?: it }

    /** The Ready state of the fixture as the view model builds it (the calculator is the real WP-12 one). */
    fun ready(
        rows: List<DictationMetrics> = Wp12MetricsFixture.week,
        range: DashboardRange = DashboardRange.Week,
        wpm: Int = 40,
        today: LocalDate = Wp12MetricsFixture.TODAY,
    ): DashboardState.Ready {
        val calc = DefaultStatsCalculator()
        val first = today.minusDays(6)
        val window = rows.filter { !it.localDay.isBefore(first) && !it.localDay.isAfter(today) }
        val week = calc.compute(window, today, wpm)
        val rangeRows = if (range == DashboardRange.Week) window else window.filter { it.localDay == today }
        val ranged = if (range == DashboardRange.Week) week else calc.compute(rangeRows, today, wpm)
        val top = ranged.copiedOnlyTopApp
        val inTop = rangeRows.count { it.status == EntryStatus.COPIED && it.pkg == top }
        return DashboardState.Ready(today, week, ranged, range, inTop, wpm, empty = window.none { it.status != EntryStatus.FAILED })
    }

    class Expected(
        val todayNumber: String,
        val todayUnit: String,
        val sentence: String,
        val bold: String,
        val weekTotal: String,
        val dayLabels: List<String>,
        val chartDescription: String,
        val metrics: List<List<String?>>,
        val languages: List<String>,
        val appsCaption: String,
        val apps: List<Pair<String, String>>,
        val others: String,
    )

    val en = Expected(
        todayNumber = "184",
        todayUnit = "words",
        sentence = "1 min of speaking. Typing this at 40 wpm would take about 5 min, so roughly 4 min saved.",
        bold = "4 min saved",
        weekTotal = "414 words",
        dayLabels = listOf("Th", "Fr", "Sa", "Su", "Mo", "Tu", "We"),
        chartDescription = "Words per day, 414 in total: Thursday 15, Friday 5, Saturday 90, Sunday 0, Monday 60, " +
            "Tuesday 60, today 184.",
        metrics = listOf(
            listOf("Speaking pace", "155", "wpm", "Median of 7 days, pauses excluded"),
            listOf("Ready after you stop", "1.2", "s", "Median. 9 in 10 under 2.6 s"),
            listOf("Typed into the app", "73", "%", "3 copied only, most in Termux"),
            listOf("Changed by cleanup", "8", "% of words", "21 filler words removed, 15 fixes"),
        ),
        languages = listOf("Russian 55%", "English 36%", "Mixed 9%"),
        appsCaption = "words, 7 days",
        apps = listOf("Telegram" to "165", "Gmail" to "80", "Chrome" to "60", "Termux" to "50", "Samsung Notes" to "40"),
        others = "3 other apps · 19 words",
    )

    val ru = Expected(
        todayNumber = "184",
        todayUnit = "слова",
        sentence = "1 мин речи. Набор этого текста со скоростью 40 слов/мин занял бы около 5 мин — экономия примерно 4 мин.",
        bold = "4 мин",
        weekTotal = "414 слов",
        dayLabels = listOf("Чт", "Пт", "Сб", "Вс", "Пн", "Вт", "Ср"),
        chartDescription = "Слов по дням, всего 414: четверг 15, пятница 5, суббота 90, воскресенье 0, понедельник 60, " +
            "вторник 60, сегодня 184.",
        metrics = listOf(
            listOf("Темп речи", "155", "слов/мин", "Медиана за 7 дней, без пауз"),
            listOf("Готово после остановки", "1,2", "с", "Медиана. 9 из 10 быстрее 2,6 с"),
            listOf("Вставлено в приложение", "73", "%", "3 диктовки только скопированы, в основном в Termux"),
            listOf("Изменено правкой", "8", "% слов", "Убрано 21 слово-паразит, 15 исправлений"),
        ),
        languages = listOf("Русский 55${NBSP}%", "Английский 36${NBSP}%", "Смешанный 9${NBSP}%"),
        appsCaption = "слов за 7 дней",
        apps = listOf("Telegram" to "165", "Gmail" to "80", "Chrome" to "60", "Termux" to "50", "Samsung Notes" to "40"),
        others = "Ещё 3 приложения · 19 слов",
    )

    /** Every element of [t] against [e]. */
    fun assertTexts(e: Expected, t: DashboardTexts) {
        assertEquals("today number", e.todayNumber, t.today.number)
        assertEquals("today unit", e.todayUnit, t.today.unit)
        assertEquals("today sentence", e.sentence, t.today.sentence.text)
        assertEquals("bold part", e.bold, t.today.sentence.text.substring(t.today.sentence.boldStart, t.today.sentence.boldEnd))
        assertEquals("week total", e.weekTotal, t.week.total)
        assertEquals("day labels", e.dayLabels, t.week.days.map { it.label })
        assertEquals("only today is today", listOf(false, false, false, false, false, false, true), t.week.days.map { it.isToday })
        assertEquals("max bar", 6, t.week.maxIndex)
        assertEquals("max label", "184", t.week.maxLabel)
        assertEquals("chart description", e.chartDescription, t.week.description)
        assertEquals("metrics", e.metrics, t.metrics.map { listOf(it.title, it.value, it.unit, it.subtitle) })
        assertEquals("languages", e.languages, t.languages.map { it.text })
        assertEquals("language tones", listOf(LangTone.Primary, LangTone.Secondary, LangTone.Mixed), t.languages.map { it.tone })
        assertEquals("apps caption", e.appsCaption, t.apps.caption)
        assertEquals("apps", e.apps, t.apps.rows.map { it.label to it.words })
        assertEquals("app bars", listOf(1f, 80f / 165, 60f / 165, 50f / 165, 40f / 165), t.apps.rows.map { it.fraction })
        assertEquals("others", e.others, t.apps.others)
    }

    /** Rows for the design's wording "4 copied only, all in Termux": four copies, all in one app, plus 60 inserts. */
    fun allInTermux(): List<DictationMetrics> {
        val day = Wp12MetricsFixture.TODAY
        val copied = (1..4).map {
            Wp12MetricsFixture.row(day, Wp12MetricsFixture.TERMUX, EntryStatus.COPIED, wordsFinal = 10, recordMs = 6_000, speechMs = 5_000, stopToDoneMs = 1_000)
        }
        val inserted = (1..60).map {
            Wp12MetricsFixture.row(day.minusDays((it % 7).toLong()), Wp12MetricsFixture.TELEGRAM, wordsFinal = 10, recordMs = 6_000, speechMs = 5_000, stopToDoneMs = 1_000)
        }
        return copied + inserted
    }
}
