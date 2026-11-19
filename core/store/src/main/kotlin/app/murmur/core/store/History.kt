package app.murmur.core.store

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * One dictation in History (DESIGN 6.3). Text + compressed audio only; screen context is NEVER stored (D9).
 * Auto-deleted with its audio at local midnight once [localDay] < today - (retentionDays - 1).
 */
data class HistoryEntry(
    val id: EntryId,
    val createdAt: Instant,
    /** Local calendar day at creation (retention is per calendar day, DESIGN decision 4). */
    val localDay: LocalDate,
    val app: AppRef,
    val status: EntryStatus,
    /** Inserted/copied text (null for Failed). */
    val finalText: String?,
    /** Raw ASR transcript (null when nothing was recognised). */
    val rawText: String?,
    val language: String?,
    val asr: ModelUse?,
    val cleanup: CleanupUse?,
    val audio: AudioRef?,
    val timings: Timings,
    val insertMethod: InsertMethod?,
    /** MurmurError.code when status = FAILED or cleanup was skipped. */
    val failureCode: String?,
    /** Re-polish keeps [rawText] and replaces [finalText]; this counts the versions. */
    val revision: Int = 1,
    /** Words of screen text the cleanup saw (a number only: the text itself is never stored, D9 / ADR-043). */
    val contextWords: Int? = null,
)

/** App the dictation went to. [conversation] = window title when the app exposes it ("Telegram · Дима"). */
data class AppRef(val pkg: String, val label: String?, val conversation: String?)

enum class EntryStatus { INSERTED, COPIED, FAILED }

enum class InsertMethod { COMMIT_TEXT, PASTE, SET_TEXT, CLIPBOARD_ONLY }

data class ModelUse(val modelId: ModelId, val artifactId: ArtifactId, val unit: ComputeUnit)

data class CleanupUse(
    val model: ModelUse?,
    /** Prompt profile + "edited" flag, e.g. "default.v1" / "default.v1+edited" (README rule 1). */
    val promptVersion: String,
    /** prompts/guard.py action/reason, or "skipped:<code>" (DESIGN "Inserted, no cleanup"). */
    val outcome: String,
    val tone: String?,
)

data class AudioRef(val path: String, val durationMs: Long, val bytes: Long, val codec: String)

data class Timings(
    val recordMs: Long,
    val speechMs: Long,
    /** Stop tap -> text inserted or copied ("Ready after you stop"). */
    val stopToDoneMs: Long?,
    val asrTailMs: Long?,
    val llmMs: Long?,
    val ocrMs: Long?,
)

interface HistoryRepository {
    /** Newest first; the list screen groups by [HistoryEntry.localDay]. */
    fun entries(): Flow<List<HistoryEntry>>
    fun entry(id: EntryId): Flow<HistoryEntry?>
    /** Searches finalText and rawText (DESIGN 6.3 search). */
    fun search(query: String, pkg: String? = null): Flow<List<HistoryEntry>>
    suspend fun insert(entry: HistoryEntry): EntryId
    suspend fun update(entry: HistoryEntry)
    /** Soft-delete for the Undo snackbar; [commitDeletes] removes rows + audio files. */
    suspend fun markDeleted(ids: Set<EntryId>)
    suspend fun restore(ids: Set<EntryId>)
    suspend fun commitDeletes()
    /** Retention: delete every entry (and its audio) with localDay < [firstKeptDay]. Returns the count. */
    suspend fun deleteBefore(firstKeptDay: LocalDate): Int
    suspend fun deleteAll()
}
