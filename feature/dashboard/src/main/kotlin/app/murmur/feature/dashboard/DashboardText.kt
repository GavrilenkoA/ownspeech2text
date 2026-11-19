package app.murmur.feature.dashboard

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.murmur.core.store.DashboardStats
import app.murmur.core.store.DefaultStatsCalculator
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The string tables the formatter reads. Production: [AndroidDashboardStrings] (the app's resources, so the system
 * language and its plural rules apply). JVM tests read the same `res/values*` files.
 */
interface DashboardStrings {
    val locale: Locale
    fun get(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
    fun array(@ArrayRes id: Int): List<String>
}

/** [DashboardStrings] over Android [Resources] (current configuration). */
class AndroidDashboardStrings(private val res: Resources) : DashboardStrings {
    override val locale: Locale = res.configuration.locales[0]
    override fun get(id: Int, vararg args: Any): String = if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any): String = res.getQuantityString(id, count, *args)
    override fun array(id: Int): List<String> = res.getStringArray(id).toList()
}

/** Text with an optional bold span ("roughly **23 min saved**"). [boldStart] < 0: no bold part. */
data class RichText(val text: String, val boldStart: Int = -1, val boldEnd: Int = -1)

/** Today card: the display number, its unit ("words"), and the speaking / time-saved sentence. */
data class TodayTexts(val number: String, val unit: String, val sentence: RichText, val isZero: Boolean)

/** One bar of the 7-day chart. [fraction] = height share of the 104 dp bar area (before the 4 dp minimum). */
data class ChartDay(val date: LocalDate, val label: String, val words: Int, val fraction: Float, val isToday: Boolean)

/**
 * "Last 7 days": [total] for the header ("11,374 words"), the 7 bars oldest first, the index of the one labelled bar
 * (the maximum; -1 when every day is 0) with its [maxLabel], and the full TalkBack [description].
 */
data class WeekTexts(
    val total: String,
    val days: List<ChartDay>,
    val maxIndex: Int,
    val maxLabel: String?,
    val description: String,
)

/** One "How it's going" row. [value] null = no data yet ("—"). */
data class MetricTexts(val title: String, val value: String?, val unit: String?, val subtitle: String)

/** Colour role of a language segment: the largest language ink, the next ink4, then ink2; Mixed is the accent. */
enum class LangTone { Primary, Secondary, Other, Mixed }

/** One language: legend [text] ("Russian 62%"), the bar [share] (0..1) and its tone. */
data class LangTexts(val label: String, val percent: Int, val share: Float, val tone: LangTone, val text: String)

/** One "Where you dictate" row: [fraction] of the top app's words (bar length). */
data class AppRowTexts(val pkg: String, val label: String, val words: String, val fraction: Float)

data class AppsTexts(val caption: String, val rows: List<AppRowTexts>, val others: String?)

/** Every dynamic text of the dashboard for one [DashboardState.Ready]. */
data class DashboardTexts(
    val today: TodayTexts,
    val week: WeekTexts,
    val metrics: List<MetricTexts>,
    val languages: List<LangTexts>,
    val apps: AppsTexts,
)

/** The three rows of the empty state's "What you'll see here" (value always "—"). */
fun emptyMetrics(s: DashboardStrings): List<MetricTexts> = listOf(
    MetricTexts(s.get(R.string.dash_pace), null, null, s.get(R.string.dash_empty_pace)),
    MetricTexts(s.get(R.string.dash_ready), null, null, s.get(R.string.dash_empty_ready)),
    MetricTexts(s.get(R.string.dash_typed), null, null, s.get(R.string.dash_empty_typed)),
)

/**
 * Turns the numbers of a [DashboardState.Ready] into the copy of DESIGN 6.2 / 7 in the strings' language. Numbers use
 * the locale's [NumberFormat] (1,284 / 1 284); counts pick their plural form from the strings (one/few/many).
 * [appLabel] maps a package to its launcher label (AppInfoProvider; the package name when unknown).
 */
class DashboardFormatter(private val s: DashboardStrings, private val appLabel: (String) -> String) {
    private val locale = s.locale
    private val integer: NumberFormat = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }
    private val oneDecimal: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

    fun int(n: Int): String = integer.format(n.toLong())
    fun int(n: Long): String = integer.format(n)
    private fun seconds(ms: Long): String = oneDecimal.format(ms / 1000.0)

    fun format(ready: DashboardState.Ready): DashboardTexts = DashboardTexts(
        today = today(ready.week, ready.typingWpm),
        week = week(ready.week),
        metrics = metrics(ready.ranged, ready.range, ready.copiedInTopApp),
        languages = languages(ready.ranged.languages),
        apps = apps(ready.ranged, ready.range),
    )

    // ------------------------------------------------------------------------------------------------ Today

    fun today(stats: DashboardStats, typingWpm: Int): TodayTexts {
        val words = stats.todayWords
        val speakingMin = stats.todaySpeakingMin
        val speaking = when {
            words == 0 && speakingMin <= 0.0 -> s.get(R.string.dash_nothing_today)
            speakingMin.roundToLong() < 1 -> s.get(R.string.dash_speaking_short)
            else -> s.get(R.string.dash_speaking, int(speakingMin.roundToLong()))
        }
        // DESIGN 6.2: "Hidden if ≤ 0". The shown minutes add up (32 min of typing − 9 min of speaking = 23 min
        // saved), so the saving is the difference of the rounded figures; one that comes to 0 is hidden as well.
        val typingMin = if (typingWpm > 0) (words.toDouble() / typingWpm).roundToLong() else 0L
        val saved = (typingMin - speakingMin.roundToLong()).takeIf { stats.timeSavedMin != null && it >= 1 }
        val sentence = if (saved == null) {
            RichText(speaking)
        } else {
            val bold = s.get(R.string.dash_min_saved, int(saved))
            val second = s.get(R.string.dash_time_saved, int(typingWpm), int(typingMin), bold)
            val text = "$speaking $second"
            val at = text.lastIndexOf(bold)
            RichText(text, at, at + bold.length)
        }
        return TodayTexts(int(words), s.plural(R.plurals.dash_words_unit, words), sentence, isZero = words == 0)
    }

    // ------------------------------------------------------------------------------------------------ Week

    fun week(stats: DashboardStats): WeekTexts {
        val days = stats.last7Days
        val today = days.lastOrNull()?.first
        val total = days.sumOf { it.second }
        val max = days.maxOfOrNull { it.second } ?: 0
        val scale = niceMax(max)
        val short = s.array(R.array.dash_days_short)
        val full = s.array(R.array.dash_days_full)
        // The labelled bar: the largest day, the most recent one on a tie.
        val maxIndex = if (max <= 0) -1 else days.indexOfLast { it.second == max }
        val bars = days.map { (date, words) ->
            ChartDay(date, short[date.dayOfWeek.value - 1], words, (words.toFloat() / scale).coerceIn(0f, 1f), date == today)
        }
        val described = days.joinToString(", ") { (date, words) ->
            val name = if (date == today) s.get(R.string.dash_chart_today) else full[date.dayOfWeek.value - 1]
            "$name ${int(words)}"
        }
        return WeekTexts(
            total = s.plural(R.plurals.dash_words_count, total, int(total)),
            days = bars,
            maxIndex = maxIndex,
            maxLabel = if (maxIndex >= 0) int(max) else null,
            description = s.get(R.string.dash_chart_cd, int(total), described),
        )
    }

    // ------------------------------------------------------------------------------------------------ Metrics

    fun metrics(stats: DashboardStats, range: DashboardRange, copiedInTopApp: Int): List<MetricTexts> {
        val pace = stats.paceWpmMedian
        val readyMedian = stats.readyMedianMs
        val readyP90 = stats.readyP90Ms
        val typed = stats.typedShare
        val changed = stats.changedShare
        return listOf(
            MetricTexts(
                s.get(R.string.dash_pace),
                pace?.let { int(it.roundToLong()) },
                pace?.let { s.get(R.string.dash_pace_unit) },
                s.get(if (range == DashboardRange.Week) R.string.dash_pace_sub_week else R.string.dash_pace_sub_today),
            ),
            MetricTexts(
                s.get(R.string.dash_ready),
                readyMedian?.let { seconds(it) },
                readyMedian?.let { s.get(R.string.dash_ready_unit) },
                if (readyP90 != null) s.get(R.string.dash_ready_sub, seconds(readyP90)) else s.get(R.string.dash_empty_ready),
            ),
            MetricTexts(
                s.get(R.string.dash_typed),
                typed?.let { int(typedPercent(it, stats.copiedOnlyCount)) },
                typed?.let { s.get(R.string.dash_percent_unit) },
                if (typed == null) s.get(R.string.dash_empty_typed) else copiedLine(stats, copiedInTopApp),
            ),
            MetricTexts(
                s.get(R.string.dash_cleanup),
                changed?.let { changedPercent(it) },
                changed?.let { s.get(R.string.dash_cleanup_unit) },
                if (changed == null) {
                    s.get(R.string.dash_cleanup_none)
                } else {
                    s.get(
                        R.string.dash_cleanup_sub,
                        s.plural(R.plurals.dash_fillers, stats.fillersRemoved, int(stats.fillersRemoved)),
                        s.plural(R.plurals.dash_fixes, stats.fixes, int(stats.fixes)),
                    )
                },
            ),
        )
    }

    /** "4 copied only, all in Termux" and its variants (none / one / most / some in the top app). */
    private fun copiedLine(stats: DashboardStats, inTop: Int): String {
        val n = stats.copiedOnlyCount
        val top = stats.copiedOnlyTopApp
        if (n == 0 || top == null) return s.get(R.string.dash_copied_none)
        val base = s.plural(R.plurals.dash_copied_only, n, int(n))
        val app = appLabel(top)
        return when {
            inTop >= n && n == 1 -> s.get(R.string.dash_copied_one_in, base, app)
            inTop >= n -> s.get(R.string.dash_copied_all_in, base, app)
            inTop * 2 > n -> s.get(R.string.dash_copied_most_in, base, app)
            else -> s.get(R.string.dash_copied_some_in, base, int(inTop), app)
        }
    }

    // ------------------------------------------------------------------------------------------------ Languages

    fun languages(counts: Map<String, Int>): List<LangTexts> {
        val total = counts.values.sum()
        if (total <= 0) return emptyList()
        val mixed = counts[DefaultStatsCalculator.MIXED] ?: 0
        val plain = counts.filterKeys { it != DefaultStatsCalculator.MIXED }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        // At most two named languages (ink, ink4); the rest are one "Other" segment in its own neutral (ink2), so
        // every segment and legend swatch differs from its neighbours (DESIGN 6.2: ink / ink4 / accent).
        val named = plain.take(MAX_NAMED_LANGUAGES).map { languageName(it.key) to it.value }
        val rest = plain.drop(MAX_NAMED_LANGUAGES).sumOf { it.value }
        val segments = buildList {
            named.forEachIndexed { i, (name, n) -> add(Triple(name, n, if (i == 0) LangTone.Primary else LangTone.Secondary)) }
            if (rest > 0) add(Triple(s.get(R.string.dash_lang_other), rest, LangTone.Other))
            if (mixed > 0) add(Triple(s.get(R.string.dash_lang_mixed), mixed, LangTone.Mixed))
        }
        val percents = largestRemainder(segments.map { it.second }, 100)
        return segments.mapIndexed { i, (name, n, tone) ->
            LangTexts(name, percents[i], n.toFloat() / total, tone, s.get(R.string.dash_lang_item, name, int(percents[i])))
        }
    }

    private fun languageName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        if (name.isBlank() || name == code) return code
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    // ------------------------------------------------------------------------------------------------ Apps

    fun apps(stats: DashboardStats, range: DashboardRange): AppsTexts {
        val top = stats.topApps.firstOrNull()?.second ?: 0
        val rows = stats.topApps.map { (pkg, words) ->
            AppRowTexts(pkg, appLabel(pkg), int(words), if (top > 0) words.toFloat() / top else 0f)
        }
        val others = if (stats.otherAppsCount > 0) {
            s.get(
                R.string.dash_other_apps_line,
                s.plural(R.plurals.dash_other_apps, stats.otherAppsCount, int(stats.otherAppsCount)),
                s.plural(R.plurals.dash_words_count, stats.otherAppsWords, int(stats.otherAppsWords)),
            )
        } else null
        val caption = s.get(if (range == DashboardRange.Week) R.string.dash_apps_caption_week else R.string.dash_apps_caption_today)
        return AppsTexts(caption, rows, others)
    }

    companion object {
        const val MAX_NAMED_LANGUAGES = 2

        /**
         * The chart's full scale: the maximum rounded up to two significant digits (2,950 → 3,000; 1,284 → 1,300),
         * so the tallest bar sits just below the top as in the design, and small days still read as small.
         */
        fun niceMax(max: Int): Int {
            if (max <= 0) return 1
            val magnitude = 10.0.pow(floor(log10(max.toDouble())) - 1).coerceAtLeast(1.0)
            return (ceil(max / magnitude) * magnitude).roundToInt()
        }

        /**
         * Inserted share in whole percent, never 100 while something was only copied and never 0 while something
         * was inserted (99.6 % with one copy reads "99", not "100").
         */
        fun typedPercent(share: Double, copied: Int): Int {
            var p = (share * 100).roundToInt()
            if (copied > 0) p = p.coerceAtMost(99)
            if (share > 0) p = p.coerceAtLeast(1)
            return p
        }

        /** Whole percent; "<1" for a change that exists but rounds to 0. */
        fun changedPercent(share: Double): String {
            val p = share * 100
            return if (p > 0 && p < 0.5) "<1" else p.roundToInt().toString()
        }

        /** Whole percents of [counts] that add up to [total] (largest remainder; ties go to the earlier item). */
        fun largestRemainder(counts: List<Int>, total: Int): List<Int> {
            val sum = counts.sum()
            if (sum <= 0) return counts.map { 0 }
            val exact = counts.map { it.toDouble() * total / sum }
            val floors = exact.map { floor(it).toInt() }.toMutableList()
            var left = total - floors.sum()
            val order = exact.indices.sortedWith(compareByDescending<Int> { exact[it] - floors[it] }.thenBy { it })
            for (i in order) {
                if (left <= 0) break
                floors[i]++
                left--
            }
            return floors
        }
    }
}
