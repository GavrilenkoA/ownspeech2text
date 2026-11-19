package app.murmur.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * History row (DESIGN 6.3). Text + audio reference only: no screen text, no OCR, no context (D9).
 * local_day is the ISO date at creation in the then-current zone (retention is per calendar day).
 */
@Entity(tableName = "history", indices = [Index("local_day"), Index("created_at"), Index("pkg")])
data class HistoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
    @ColumnInfo(name = "local_day") val localDay: String,
    val pkg: String,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    val conversation: String?,
    val status: String,
    @ColumnInfo(name = "final_text") val finalText: String?,
    @ColumnInfo(name = "raw_text") val rawText: String?,
    val language: String?,
    @ColumnInfo(name = "asr_model") val asrModel: String?,
    @ColumnInfo(name = "asr_artifact") val asrArtifact: String?,
    @ColumnInfo(name = "asr_unit") val asrUnit: String?,
    @ColumnInfo(name = "llm_model") val llmModel: String?,
    @ColumnInfo(name = "llm_artifact") val llmArtifact: String?,
    @ColumnInfo(name = "llm_unit") val llmUnit: String?,
    @ColumnInfo(name = "prompt_version") val promptVersion: String?,
    @ColumnInfo(name = "cleanup_outcome") val cleanupOutcome: String?,
    val tone: String?,
    @ColumnInfo(name = "audio_path") val audioPath: String?,
    @ColumnInfo(name = "audio_ms") val audioMs: Long?,
    @ColumnInfo(name = "audio_bytes") val audioBytes: Long?,
    @ColumnInfo(name = "audio_codec") val audioCodec: String?,
    @ColumnInfo(name = "record_ms") val recordMs: Long,
    @ColumnInfo(name = "speech_ms") val speechMs: Long,
    @ColumnInfo(name = "stop_to_done_ms") val stopToDoneMs: Long?,
    @ColumnInfo(name = "asr_tail_ms") val asrTailMs: Long?,
    @ColumnInfo(name = "llm_ms") val llmMs: Long?,
    @ColumnInfo(name = "ocr_ms") val ocrMs: Long?,
    @ColumnInfo(name = "insert_method") val insertMethod: String?,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    val revision: Int = 1,
    /** Count only; screen text is never stored (ADR-043). */
    @ColumnInfo(name = "context_words") val contextWords: Int? = null,
    /** Soft delete for the Undo snackbar (DESIGN 6.3); committed when the snackbar times out. */
    @ColumnInfo(name = "pending_delete") val pendingDelete: Boolean = false,
    /**
     * Search shadow: final + raw text folded by [SearchText.fold] (NFC, full case folding, "ё" -> "е"). SQLite's LIKE only
     * folds ASCII, so "Привет" would not find "привет" in the text columns themselves.
     */
    @ColumnInfo(name = "search_text") val searchText: String = "",
)

/** Numbers-only metrics row (Dashboard), kept 90 days independent of history retention. */
@Entity(tableName = "metrics", indices = [Index("local_day")])
data class MetricsRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
    @ColumnInfo(name = "local_day") val localDay: String,
    val pkg: String,
    val status: String,
    val language: String?,
    @ColumnInfo(name = "mixed_language") val mixedLanguage: Boolean,
    @ColumnInfo(name = "words_final") val wordsFinal: Int,
    @ColumnInfo(name = "words_raw") val wordsRaw: Int,
    @ColumnInfo(name = "record_ms") val recordMs: Long,
    @ColumnInfo(name = "speech_ms") val speechMs: Long,
    @ColumnInfo(name = "stop_to_done_ms") val stopToDoneMs: Long?,
    @ColumnInfo(name = "edit_distance_words") val editDistanceWords: Int,
    @ColumnInfo(name = "fillers_removed") val fillersRemoved: Int,
    val fixes: Int,
    @ColumnInfo(name = "cleanup_applied") val cleanupApplied: Boolean,
)

/** One benchmark run of one (artifact, unit) (ARCHITECTURE 10). [valid] is copied out of [result] for the lookup query. */
@Entity(tableName = "benchmark", indices = [Index("artifact", "unit", "at_ms")])
data class BenchmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artifact: String,
    /** ComputeUnit name. */
    val unit: String,
    @ColumnInfo(name = "app_version") val appVersion: String,
    val fingerprint: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val valid: Boolean,
    @ColumnInfo(name = "invalid_reason") val invalidReason: String?,
    /** BenchmarkResultData JSON. */
    val result: String,
)

/** User-added Hugging Face models (D3): the full ModelEntry as JSON, origin = custom. */
@Entity(tableName = "custom_model")
data class CustomModelRow(@PrimaryKey val id: String, val json: String, @ColumnInfo(name = "added_ms") val addedMs: Long)
