package app.murmur.feature.history.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.murmur.feature.history.DayKind
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates and times in the user's locale (DESIGN 6.3: "the weekday and date in the user's locale"; times follow the
 * system 12/24-hour setting). Patterns come from the platform's skeleton matcher, so each locale gets its own order
 * and punctuation.
 */
internal class HistoryFormats(val locale: Locale, is24Hour: Boolean) {
    private val time = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"), locale)
    private val dayLong = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    private val dayShort = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    private val decimal = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }

    fun time(t: LocalDateTime): String = time.format(t)

    fun time(t: ZonedDateTime): String = time.format(t)

    /** "Monday, 16 November" (capitalised: Russian weekdays are lower-case inside a sentence). */
    fun dayLong(day: LocalDate): String = dayLong.format(day).capitalize()

    /** "Wed 25 Nov". */
    fun dayShort(day: LocalDate): String = dayShort.format(day).capitalize()

    fun dayLabel(day: LocalDate, kind: DayKind, today: String, yesterday: String): String = when (kind) {
        DayKind.TODAY -> today
        DayKind.YESTERDAY -> yesterday
        DayKind.DATE -> dayLong(day)
    }

    /** Seconds with one decimal ("0.4", "0,4"). */
    fun seconds(ms: Long): String = decimal.format(ms / 1000.0)

    private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

@Composable
internal fun rememberHistoryFormats(): HistoryFormats {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val locale = config.locales[0] ?: Locale.getDefault()
    val is24 = is24Hour(context)
    return remember(locale, is24) { HistoryFormats(locale, is24) }
}

private fun is24Hour(context: Context): Boolean = DateFormat.is24HourFormat(context)
