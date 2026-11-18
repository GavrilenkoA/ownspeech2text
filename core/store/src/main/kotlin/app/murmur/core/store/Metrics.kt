package app.murmur.core.store

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Numbers-only record of one dictation, written next to the history row and kept for the stats retention
 * period (90 days proposed, open question) even after the history text is gone (DESIGN 6.2). Every
 * Dashboard number is derivable from these rows (ARCHITECTURE.md section 12): no text, no context.
 */
data class DictationMetrics(
    val createdAt: Instant,
    val localDay: LocalDate,
    val pkg: String,
    val status: EntryStatus,
    val language: String?,
    /** True when >= 2 tokens of the minority language were present (Dashboard "Mixed"). */
    val mixedLanguage: Boolean,
    val wordsFinal: Int,
    val wordsRaw: Int,
    val recordMs: Long,
    val speechMs: Long,
    val stopToDoneMs: Long?,
    /** Word-level edit distance raw -> final, filler deletions, other substitutions (Dashboard "Changed by cleanup"). */
    val editDistanceWords: Int,
    val fillersRemoved: Int,
    val fixes: Int,
    val cleanupApplied: Boolean,
)

interface MetricsRepository {
    fun since(day: LocalDate): Flow<List<DictationMetrics>>
    suspend fun add(m: DictationMetrics)
    suspend fun deleteBefore(day: LocalDate): Int
}

/** Dashboard values (DESIGN 6.2) computed from metrics rows. Pure; WP-12 implements [StatsCalculator]. */
data class DashboardStats(
    val todayWords: Int,
    val todaySpeakingMin: Double,
    val timeSavedMin: Double?,
    val last7Days: List<Pair<LocalDate, Int>>,
    val paceWpmMedian: Double?,
    val readyMedianMs: Long?,
    val readyP90Ms: Long?,
    val typedShare: Double?,
    val copiedOnlyCount: Int,
    val copiedOnlyTopApp: String?,
    val changedShare: Double?,
    val fillersRemoved: Int,
    val fixes: Int,
    val languages: Map<String, Int>,
    val topApps: List<Pair<String, Int>>,
    val otherAppsCount: Int,
    val otherAppsWords: Int,
)

interface StatsCalculator {
    fun compute(rows: List<DictationMetrics>, today: LocalDate, typingWpm: Int): DashboardStats
}

/**
 * Numbers computed once at delivery by WP-11 (ARCHITECTURE 12), implemented and tested by WP-12. Pure. The
 * filler lexicon comes from OutputGuard.fillers (the prompts/guard.py FILLERS port, WP-04).
 */
interface DeliveryMetrics {
    fun wordCount(text: String): Int
    /** Word-level Levenshtein distance raw -> final (casefolded, punctuation stripped). */
    fun editDistanceWords(raw: String, final: String): Int
    /** Filler tokens of [raw] absent from [final]. */
    fun fillersRemoved(raw: String, final: String, fillers: Set<String>): Int
    /** Substitutions that are not filler deletions (= edit distance - fillers removed, floored at 0). */
    fun fixes(raw: String, final: String, fillers: Set<String>): Int
    /** True when >= 2 tokens of the minority script (Cyrillic vs Latin) are present. */
    fun mixedLanguage(text: String): Boolean
}

/** WP-12 replaces the bodies; the signatures are the contract WP-11 calls (tests use :testing SimpleDeliveryMetrics). */
class DefaultDeliveryMetrics : DeliveryMetrics {
    override fun wordCount(text: String): Int = TODO("WP-12")
    override fun editDistanceWords(raw: String, final: String): Int = TODO("WP-12")
    override fun fillersRemoved(raw: String, final: String, fillers: Set<String>): Int = TODO("WP-12")
    override fun fixes(raw: String, final: String, fillers: Set<String>): Int = TODO("WP-12")
    override fun mixedLanguage(text: String): Boolean = TODO("WP-12")
}
