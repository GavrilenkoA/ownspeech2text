package app.murmur.core.store

import java.time.LocalDate

/**
 * Dashboard numbers from metrics rows (DESIGN 6.2 table, ARCHITECTURE 12). Pure and deterministic.
 *
 * Window: the 7 local days ending at `today` (rows outside are ignored, so callers may pass a longer slice).
 * "Delivered" = INSERTED or COPIED. FAILED rows count 0 everywhere: no words, no speaking time, no pace, no latency,
 * no language, no app; they only exist so that the history and the metrics agree on what happened.
 *
 * - Today, words: Σ wordsFinal of today's delivered rows.
 * - Speaking minutes: Σ speech (speechMs, or recordMs when VAD reported 0) of today's delivered rows. Restricting it to
 *   delivered rows keeps "time saved" honest: it compares typing the words that were produced with speaking them.
 * - Time saved: todayWords / typingWpm − speaking minutes; null (hidden) when ≤ 0 or typingWpm ≤ 0.
 * - Last 7 days: one pair per day, oldest first, Σ wordsFinal of delivered rows (0 for empty days).
 * - Speaking pace: median of wordsRaw / speech minutes over delivered dictations with recordMs ≥ 3 s and wordsRaw > 0.
 * - Ready after you stop: median and nearest-rank p90 of stopToDoneMs over delivered rows that have one
 *   ("9 in 10 under p90" holds by construction).
 * - Typed into the app: inserted / (inserted + copied); copied count; the package with most COPIED rows.
 * - Changed by cleanup: Σ editDistanceWords / Σ wordsRaw over delivered rows; Σ fillersRemoved; Σ fixes.
 * - Languages: dictations per language code, [MIXED] instead of the code when mixedLanguage; rows without a language
 *   and not mixed are not counted. Ordered by count, then key.
 * - Where you dictate: Σ wordsFinal per package (packages with 0 words are left out), top [TOP_APPS] by words, then
 *   the number of other packages and their words.
 *
 * Ties are broken by package / language key so that the same rows always give the same dashboard.
 */
class DefaultStatsCalculator : StatsCalculator {
    override fun compute(rows: List<DictationMetrics>, today: LocalDate, typingWpm: Int): DashboardStats {
        val firstDay = today.minusDays((WINDOW_DAYS - 1).toLong())
        val window = rows.filter { !it.localDay.isBefore(firstDay) && !it.localDay.isAfter(today) }
        val delivered = window.filter { it.status != EntryStatus.FAILED }
        val todayRows = delivered.filter { it.localDay == today }

        val todayWords = todayRows.sumOf { it.wordsFinal }
        val todaySpeakingMin = todayRows.sumOf { speechMs(it) } / MS_PER_MIN
        val timeSaved = if (typingWpm > 0) todayWords.toDouble() / typingWpm - todaySpeakingMin else null

        val wordsPerDay = delivered.groupingBy { it.localDay }.fold(0) { acc, r -> acc + r.wordsFinal }
        val last7 = (0 until WINDOW_DAYS).map { i -> firstDay.plusDays(i.toLong()).let { d -> d to (wordsPerDay[d] ?: 0) } }

        val paces = delivered
            .filter { it.recordMs >= PACE_MIN_RECORD_MS && it.wordsRaw > 0 && speechMs(it) > 0 }
            .map { it.wordsRaw / (speechMs(it) / MS_PER_MIN) }
        val ready = delivered.mapNotNull { it.stopToDoneMs }.sorted()

        val inserted = delivered.count { it.status == EntryStatus.INSERTED }
        val copied = delivered.filter { it.status == EntryStatus.COPIED }
        val copiedTop = copied.groupingBy { it.pkg }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

        val rawWords = delivered.sumOf { it.wordsRaw }
        val edits = delivered.sumOf { it.editDistanceWords }

        val languages = delivered
            .mapNotNull { if (it.mixedLanguage) MIXED else it.language }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associateTo(LinkedHashMap()) { it.key to it.value }

        val apps = delivered.groupingBy { it.pkg }.fold(0) { acc, r -> acc + r.wordsFinal }.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        val others = apps.drop(TOP_APPS)

        return DashboardStats(
            todayWords = todayWords,
            todaySpeakingMin = todaySpeakingMin,
            timeSavedMin = timeSaved?.takeIf { it > 0 },
            last7Days = last7,
            paceWpmMedian = if (paces.isEmpty()) null else median(paces.sorted()),
            readyMedianMs = if (ready.isEmpty()) null else Math.round(median(ready.map { it.toDouble() })),
            readyP90Ms = if (ready.isEmpty()) null else p90(ready),
            typedShare = (inserted + copied.size).takeIf { it > 0 }?.let { inserted.toDouble() / it },
            copiedOnlyCount = copied.size,
            copiedOnlyTopApp = copiedTop,
            changedShare = if (rawWords > 0) edits.toDouble() / rawWords else null,
            fillersRemoved = delivered.sumOf { it.fillersRemoved },
            fixes = delivered.sumOf { it.fixes },
            languages = languages,
            topApps = apps.take(TOP_APPS),
            otherAppsCount = others.size,
            otherAppsWords = others.sumOf { it.second },
        )
    }

    private fun speechMs(r: DictationMetrics): Long = if (r.speechMs > 0) r.speechMs else r.recordMs

    /** [sorted] ascending, not empty. */
    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** Nearest rank: the smallest value with at least 90 % of the values at or below it (integer rank, no rounding). */
    private fun p90(sorted: List<Long>): Long = sorted[(sorted.size * 9 + 9) / 10 - 1]

    companion object {
        const val WINDOW_DAYS = 7
        const val TOP_APPS = 5
        const val PACE_MIN_RECORD_MS = 3_000L
        /** [DashboardStats.languages] key for dictations with ≥ 2 tokens of the minority script. */
        const val MIXED = "mixed"
        private const val MS_PER_MIN = 60_000.0
    }
}
