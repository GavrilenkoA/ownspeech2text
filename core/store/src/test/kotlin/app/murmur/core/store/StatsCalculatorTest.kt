package app.murmur.core.store

import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.testing.contrib.Wp12MetricsFixture.CHROME
import app.murmur.testing.contrib.Wp12MetricsFixture.GMAIL
import app.murmur.testing.contrib.Wp12MetricsFixture.NOTES
import app.murmur.testing.contrib.Wp12MetricsFixture.TELEGRAM
import app.murmur.testing.contrib.Wp12MetricsFixture.TERMUX
import app.murmur.testing.contrib.Wp12MetricsFixture.TODAY
import app.murmur.testing.contrib.Wp12MetricsFixture.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** DESIGN 6.2 table against [Wp12MetricsFixture.week]; every expected value is worked out by hand in the comments. */
class StatsCalculatorTest {
    private val calc = DefaultStatsCalculator()
    private val stats = calc.compute(Wp12MetricsFixture.week, TODAY, typingWpm = 40)
    private val eps = 1e-9

    @Test
    fun todayWordsCountInsertedAndCopiedOnly() {
        // 100 + 50 + 30 (copied) + 4; the failed Gmail row adds 0, yesterday and tomorrow are other days.
        assertEquals(184, stats.todayWords)
    }

    @Test
    fun speakingMinutesUseVadOrRecordingTime() {
        // 40 000 + 20 000 + 14 000 (no VAD: recordMs) + 1 500 ms; the failed row is not counted.
        assertEquals(75_500 / 60_000.0, stats.todaySpeakingMin, eps)
    }

    @Test
    fun timeSavedIsTypingMinusSpeaking() {
        // 184 words / 40 wpm = 4.6 min of typing, minus 1.2583 min of speaking.
        assertEquals(184 / 40.0 - 75_500 / 60_000.0, stats.timeSavedMin!!, eps)
    }

    @Test
    fun timeSavedIsHiddenWhenNotPositive() {
        // A 200 wpm typist: 0.92 min of typing < 1.26 min of speaking.
        assertNull(calc.compute(Wp12MetricsFixture.week, TODAY, typingWpm = 200).timeSavedMin)
        // Exactly 0 is hidden too: 2 words at 40 wpm = 3 s of typing for 3 s of speaking.
        val even = listOf(row(TODAY, TELEGRAM, wordsFinal = 2, recordMs = 3_000, speechMs = 3_000, stopToDoneMs = 500))
        assertNull(calc.compute(even, TODAY, typingWpm = 40).timeSavedMin)
        assertNull(calc.compute(Wp12MetricsFixture.week, TODAY, typingWpm = 0).timeSavedMin)
    }

    @Test
    fun lastSevenDaysOldestFirstWithEmptyDays() {
        val expected = listOf(12 to 15, 13 to 5, 14 to 90, 15 to 0, 16 to 60, 17 to 60, 18 to 184)
            .map { (d, w) -> LocalDate.of(2026, 11, d) to w }
        assertEquals(expected, stats.last7Days)
    }

    @Test
    fun speakingPaceIsMedianOverDictationsOfThreeSecondsOrMore() {
        // Per dictation wordsRaw / speech minutes (WhatsApp 2 s and the failed row excluded):
        // 165, 156, 128.57 (Termux, recordMs stands in), 165, 150, 165, 159.375, 120, 100, 154.29 -> 10 values,
        // median = (154.2857 + 156) / 2.
        assertEquals((18 * 60_000.0 / 7_000 + 156.0) / 2, stats.paceWpmMedian!!, eps)
    }

    @Test
    fun readyAfterStopMedianAndP90() {
        // Sorted: 700 800 900 1000 1100 [1200] 1400 1500 2000 2600 3000; p90 nearest rank = ceil(0.9 * 11) = 10th.
        assertEquals(1_200L, stats.readyMedianMs)
        assertEquals(2_600L, stats.readyP90Ms)
    }

    @Test
    fun p90IsTheNearestRankWhereNinetyPercentIsWhole() {
        // n = 10 and n = 20: 90 % is exactly 9 and 18 values, so nearest rank is the 9th / 18th value, not the next one.
        fun readyOf(n: Int) = calc.compute(
            (1..n).map { row(TODAY, "a.app", EntryStatus.INSERTED, wordsFinal = 1, recordMs = 5_000, speechMs = 4_000, stopToDoneMs = it * 100L) },
            TODAY,
            40,
        ).readyP90Ms
        assertEquals(900L, readyOf(10))
        assertEquals(1_800L, readyOf(20))
        // One value: it is its own p90.
        assertEquals(100L, readyOf(1))
    }

    @Test
    fun typedIntoTheAppShareAndCopiedOnlyApp() {
        // 8 inserted, 3 copied (Termux twice, Telegram once).
        assertEquals(8 / 11.0, stats.typedShare!!, eps)
        assertEquals(3, stats.copiedOnlyCount)
        assertEquals(TERMUX, stats.copiedOnlyTopApp)
    }

    @Test
    fun changedByCleanup() {
        // Σ edit distance 12+2+6+4+9+3 = 36 over Σ raw words 444; fillers 8+2+3+4+1+3; fixes 4+3+8.
        assertEquals(36 / 444.0, stats.changedShare!!, eps)
        assertEquals(21, stats.fillersRemoved)
        assertEquals(15, stats.fixes)
    }

    @Test
    fun languagesWithMixed() {
        assertEquals(listOf("ru" to 6, "en" to 4, DefaultStatsCalculator.MIXED to 1), stats.languages.toList())
    }

    @Test
    fun whereYouDictateTopFiveThenOthers() {
        // Telegram 100+50+15, Gmail 80 (failed adds 0), Chrome 60, Termux 30+20, Notes 40; then Keep 10, Slack 5, WhatsApp 4.
        assertEquals(listOf(TELEGRAM to 165, GMAIL to 80, CHROME to 60, TERMUX to 50, NOTES to 40), stats.topApps)
        assertEquals(3, stats.otherAppsCount)
        assertEquals(19, stats.otherAppsWords)
    }

    @Test
    fun failedEntriesCountZero() {
        val failedOnly = Wp12MetricsFixture.week.filter { it.status == EntryStatus.FAILED }
        val s = calc.compute(failedOnly, TODAY, 40)
        assertEquals(empty(), s)
    }

    @Test
    fun emptyRows() {
        assertEquals(empty(), calc.compute(emptyList(), TODAY, 40))
    }

    @Test
    fun deterministicTieBreaks() {
        val tie = listOf(
            row(TODAY, "b.app", EntryStatus.COPIED, wordsFinal = 10, recordMs = 5_000, speechMs = 4_000, stopToDoneMs = 1_000),
            row(TODAY, "a.app", EntryStatus.COPIED, wordsFinal = 10, recordMs = 5_000, speechMs = 4_000, stopToDoneMs = 1_000),
        )
        val s = calc.compute(tie, TODAY, 40)
        assertEquals("a.app", s.copiedOnlyTopApp)
        assertEquals(listOf("a.app" to 10, "b.app" to 10), s.topApps)
        assertEquals(calc.compute(tie.reversed(), TODAY, 40), s)
        // Even count: the median is the mean of the two middle values.
        assertEquals(1_000L, s.readyMedianMs)
    }

    private fun empty() = DashboardStats(
        todayWords = 0, todaySpeakingMin = 0.0, timeSavedMin = null,
        last7Days = (12..18).map { LocalDate.of(2026, 11, it) to 0 },
        paceWpmMedian = null, readyMedianMs = null, readyP90Ms = null, typedShare = null, copiedOnlyCount = 0,
        copiedOnlyTopApp = null, changedShare = null, fillersRemoved = 0, fixes = 0, languages = emptyMap(),
        topApps = emptyList(), otherAppsCount = 0, otherAppsWords = 0,
    )
}
