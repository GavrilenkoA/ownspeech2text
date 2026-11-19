package app.murmur.testing.contrib

import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A week of metrics rows that exercises every Dashboard element (DESIGN 6.2), shared by WP-12's StatsCalculatorTest
 * and WP-17's view-model tests. [TODAY] = Wednesday 18 November 2026 in [ZONE]; 15 November has no dictations; one row
 * lies before and one after the 7-day window. The expected values are written out in StatsCalculatorTest.
 */
object Wp12MetricsFixture {
    val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    val TODAY: LocalDate = LocalDate.of(2026, 11, 18)

    const val TELEGRAM = "org.telegram.messenger"
    const val TERMUX = "com.termux"
    const val GMAIL = "com.google.android.gm"
    const val WHATSAPP = "com.whatsapp"
    const val CHROME = "com.android.chrome"
    const val NOTES = "com.samsung.android.app.notes"
    const val KEEP = "com.google.android.keep"
    const val SLACK = "com.Slack"

    fun row(
        day: LocalDate,
        pkg: String,
        status: EntryStatus = EntryStatus.INSERTED,
        language: String? = "ru",
        mixed: Boolean = false,
        wordsFinal: Int,
        wordsRaw: Int = wordsFinal,
        recordMs: Long,
        speechMs: Long,
        stopToDoneMs: Long?,
        editDistance: Int = 0,
        fillers: Int = 0,
        fixes: Int = 0,
        at: LocalTime = LocalTime.NOON,
    ) = DictationMetrics(
        createdAt = day.atTime(at).atZone(ZONE).toInstant(),
        localDay = day,
        pkg = pkg,
        status = status,
        language = language,
        mixedLanguage = mixed,
        wordsFinal = wordsFinal,
        wordsRaw = wordsRaw,
        recordMs = recordMs,
        speechMs = speechMs,
        stopToDoneMs = stopToDoneMs,
        editDistanceWords = editDistance,
        fillersRemoved = fillers,
        fixes = fixes,
        cleanupApplied = editDistance > 0,
    )

    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    val week: List<DictationMetrics> = listOf(
        // Outside the window (Wednesday a week ago, and tomorrow): must not count anywhere.
        row(d(11), TELEGRAM, wordsFinal = 999, recordMs = 600_000, speechMs = 500_000, stopToDoneMs = 99_000, editDistance = 99),
        row(d(19), TELEGRAM, wordsFinal = 999, recordMs = 600_000, speechMs = 500_000, stopToDoneMs = 99_000, editDistance = 99),
        // Thursday 12
        row(d(12), TELEGRAM, EntryStatus.COPIED, wordsFinal = 15, wordsRaw = 18, recordMs = 9_000, speechMs = 7_000,
            stopToDoneMs = 2_000, editDistance = 3, fillers = 3),
        // Friday 13
        row(d(13), SLACK, language = "en", wordsFinal = 5, recordMs = 4_000, speechMs = 3_000, stopToDoneMs = 700),
        // Saturday 14
        row(d(14), GMAIL, language = "en", wordsFinal = 80, wordsRaw = 85, recordMs = 40_000, speechMs = 32_000,
            stopToDoneMs = 1_500, editDistance = 9, fillers = 1, fixes = 8),
        row(d(14), KEEP, wordsFinal = 10, recordMs = 6_000, speechMs = 5_000, stopToDoneMs = 800),
        // Sunday 15: nothing.
        // Monday 16
        row(d(16), TERMUX, EntryStatus.COPIED, wordsFinal = 20, recordMs = 10_000, speechMs = 8_000, stopToDoneMs = 3_000),
        row(d(16), NOTES, wordsFinal = 40, wordsRaw = 44, recordMs = 20_000, speechMs = 16_000, stopToDoneMs = 1_100,
            editDistance = 4, fillers = 4),
        // Tuesday 17
        row(d(17), CHROME, language = "en", wordsFinal = 60, wordsRaw = 66, recordMs = 30_000, speechMs = 24_000,
            stopToDoneMs = 1_000, editDistance = 6, fillers = 3, fixes = 3),
        // Wednesday 18 = today
        row(d(18), TELEGRAM, wordsFinal = 100, wordsRaw = 110, recordMs = 50_000, speechMs = 40_000, stopToDoneMs = 1_200,
            editDistance = 12, fillers = 8, fixes = 4, at = LocalTime.of(9, 5)),
        row(d(18), TELEGRAM, language = "en", mixed = true, wordsFinal = 50, wordsRaw = 52, recordMs = 25_000,
            speechMs = 20_000, stopToDoneMs = 1_400, editDistance = 2, fillers = 2, at = LocalTime.of(10, 40)),
        // Copied only, and no VAD figure (speechMs = 0): its recording time stands in.
        row(d(18), TERMUX, EntryStatus.COPIED, language = "en", wordsFinal = 30, recordMs = 14_000, speechMs = 0,
            stopToDoneMs = 2_600, at = LocalTime.of(13, 15)),
        // Shorter than 3 s: counts for words and latency, not for the speaking pace.
        row(d(18), WHATSAPP, wordsFinal = 4, recordMs = 2_000, speechMs = 1_500, stopToDoneMs = 900, at = LocalTime.of(15, 0)),
        // Failed: counts 0 everywhere even though some numbers are set.
        row(d(18), GMAIL, EntryStatus.FAILED, wordsFinal = 0, wordsRaw = 3, recordMs = 4_000, speechMs = 3_500,
            stopToDoneMs = 5_000, editDistance = 3, fillers = 1, fixes = 2, at = LocalTime.of(18, 30)),
    )
}
