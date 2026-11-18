package app.murmur.core.store

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DESIGN decision 4: deletion per calendar day at local midnight; entries from day D are deleted at 00:00
 * on D + N. [days] = 0 means "Don't keep history" (nothing is saved at all).
 */
object RetentionPolicy {
    const val DEFAULT_DAYS = 7
    val OPTIONS = listOf(1, 3, 7, 0)
    const val STATS_DAYS = 90

    /** Entries with localDay < this are deleted. */
    fun firstKeptDay(today: LocalDate, days: Int): LocalDate = today.minusDays((days - 1).coerceAtLeast(0).toLong())

    /** When an entry from [day] is deleted (shown in History detail). */
    fun deletesAt(day: LocalDate, days: Int, zone: ZoneId): ZonedDateTime = day.plusDays(days.toLong()).atStartOfDay(zone)

    /** History header: the oldest kept day shows "Deletes tonight". */
    fun deletesTonight(day: LocalDate, today: LocalDate, days: Int): Boolean = day == firstKeptDay(today, days)
}
