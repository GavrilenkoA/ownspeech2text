package app.murmur.feature.history

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurError
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** What the list pane shows (DESIGN 6.3). */
enum class ListMode {
    LOADING,
    /** "Couldn't open history" + Retry. */
    ERROR,
    /** Retention 0 ("Don't keep history"): nothing is stored. */
    OFF,
    /** "Nothing here yet". */
    EMPTY,
    LIST,
}

/** DESIGN 6.3 list state. */
data class HistoryUiState(
    val mode: ListMode = ListMode.LOADING,
    val retentionDays: Int = 7,
    val today: LocalDate? = null,
    val days: List<DayGroup> = emptyList(),
    /** Non-null while the search header is open. */
    val search: SearchUiState? = null,
    /** Multi-select (long-press); empty = not selecting. */
    val selection: Set<EntryId> = emptySet(),
    /** The last delete, undoable until its snackbar times out. */
    val pendingDelete: PendingDelete? = null,
) {
    val loading: Boolean get() = mode == ListMode.LOADING
    val error: Boolean get() = mode == ListMode.ERROR
    val selecting: Boolean get() = selection.isNotEmpty()
}

enum class DayKind { TODAY, YESTERDAY, DATE }

/** One day header and its rows. [deletesTonight]: the oldest kept day (RetentionPolicy.deletesTonight). */
data class DayGroup(
    val day: LocalDate,
    val kind: DayKind,
    val deletesTonight: Boolean,
    val rows: List<HistoryRow>,
) {
    val entries: List<HistoryEntry> get() = rows.map { it.entry }
}

/** Where a search hit was found. */
enum class MatchIn { FINAL, ORIGINAL }

/** Why a Failed row has no text. */
enum class FailureKind { NO_SPEECH, OTHER }

/**
 * One list row. [text] is the text to show (final text, or the original transcript when the search hit is only
 * there; null for a Failed entry, which shows [failure]'s copy), already cut to an excerpt around the first match;
 * [highlights] index into [text].
 */
data class HistoryRow(
    val entry: HistoryEntry,
    val appName: String,
    val conversation: String?,
    val time: LocalDateTime,
    val text: String?,
    val failure: FailureKind?,
    val audioKept: Boolean,
    val highlights: List<IntRange> = emptyList(),
    val matchIn: MatchIn? = null,
) {
    val id: EntryId get() = entry.id
    val status: EntryStatus get() = entry.status
}

data class AppChip(val pkg: String, val label: String)

data class SearchUiState(
    val query: String = "",
    val pkg: String? = null,
    /** "All apps" + the apps of the kept history, most dictations first (ties: the most recent first). */
    val apps: List<AppChip> = emptyList(),
    /** null while the first results load. */
    val results: List<DayGroup>? = null,
) {
    /** True once there is something to search for (text or an app filter). */
    val active: Boolean get() = query.isNotBlank() || pkg != null
    val count: Int get() = results?.sumOf { it.rows.size } ?: 0
}

data class PendingDelete(val ids: Set<EntryId>, val serial: Long)

/** The entry detail (DESIGN 6.3 detail, `hist-detail`). */
data class DetailUiState(
    val id: EntryId,
    val loading: Boolean = true,
    /** The entry is gone (deleted, expired). */
    val missing: Boolean = false,
    val entry: HistoryEntry? = null,
    val appName: String = "",
    /** Conversation title when there is one, else the app name. */
    val title: String = "",
    val created: LocalDateTime? = null,
    val dayKind: DayKind = DayKind.DATE,
    val durationMs: Long? = null,
    val audio: AudioUi = AudioUi.Loading,
    val deletesAt: ZonedDateTime? = null,
    val diff: List<DiffSpan> = emptyList(),
    val models: List<ModelLine> = emptyList(),
    val busy: Busy? = null,
) {
    val failed: Boolean get() = entry?.status == EntryStatus.FAILED
    val failure: FailureKind? get() = if (failed) failureKind(entry?.failureCode) else null
    /** Try again needs the stored recording (DESIGN 6.3 error states). */
    val canRetry: Boolean get() = failed && audio is AudioUi.Ready && busy == null
    val canRepolish: Boolean get() = !entry?.rawText.isNullOrBlank() && busy == null
}

data class ModelLine(val name: String, val unit: ComputeUnit)

sealed interface AudioUi {
    data object Loading : AudioUi
    /** No recording (Keep recordings off, deleted file, unreadable). */
    data object Unavailable : AudioUi
    class Ready(val peaks: FloatArray, val pcm: ShortArray, val durationMs: Long) : AudioUi
}

enum class Busy { RETRY, REPOLISH }

/** One-shot results the screen shows as a snackbar. */
sealed interface HistoryEvent {
    data class RetryFailed(val id: EntryId, val error: MurmurError) : HistoryEvent
    data class RepolishFailed(val id: EntryId, val error: MurmurError) : HistoryEvent
}

internal fun failureKind(code: String?): FailureKind = if (code == MurmurError.NoSpeech.code) FailureKind.NO_SPEECH else FailureKind.OTHER
