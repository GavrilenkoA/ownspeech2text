package app.murmur.data.db

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import app.murmur.core.store.AppRef
import app.murmur.core.store.AudioRef
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import app.murmur.core.store.CleanupUse
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.InsertMethod
import app.murmur.core.store.ModelUse
import app.murmur.core.store.Timings
import app.murmur.core.store.Words
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/** History search folding (DESIGN 6.3 search covers Final and Original, any case, Cyrillic included). */
internal object SearchText {
    /**
     * [Words.fold]: NFC, full case folding independent of the device locale ("Straße" = "STRASSE"), "ё" -> "е" (people
     * rarely type "ё" in a search). Stored text and query are folded the same way, so any form of either matches.
     */
    fun fold(s: String): String = Words.fold(s)

    /** Final and original joined by a separator no query contains, so a match never spans the two. */
    fun of(finalText: String?, rawText: String?): String = fold(listOfNotNull(finalText, rawText).joinToString("\u001F"))

    /** LIKE pattern for "contains [query]" with '%', '_' and '\' taken literally (ESCAPE '\'). */
    fun pattern(query: String): String {
        val q = fold(query.trim())
        val escaped = buildString { for (c in q) { if (c == '%' || c == '_' || c == '\\') append('\\'); append(c) } }
        return "%$escaped%"
    }
}

internal fun HistoryEntry.toRow(id: Long = this.id.value, pendingDelete: Boolean = false) = HistoryRow(
    id = id,
    createdAtMs = createdAt.toEpochMilli(),
    localDay = localDay.toString(),
    pkg = app.pkg,
    appLabel = app.label,
    conversation = app.conversation,
    status = status.name,
    finalText = finalText,
    rawText = rawText,
    language = language,
    asrModel = asr?.modelId?.value,
    asrArtifact = asr?.artifactId?.value,
    asrUnit = asr?.unit?.name,
    llmModel = cleanup?.model?.modelId?.value,
    llmArtifact = cleanup?.model?.artifactId?.value,
    llmUnit = cleanup?.model?.unit?.name,
    promptVersion = cleanup?.promptVersion,
    cleanupOutcome = cleanup?.outcome,
    tone = cleanup?.tone,
    audioPath = audio?.path,
    audioMs = audio?.durationMs,
    audioBytes = audio?.bytes,
    audioCodec = audio?.codec,
    recordMs = timings.recordMs,
    speechMs = timings.speechMs,
    stopToDoneMs = timings.stopToDoneMs,
    asrTailMs = timings.asrTailMs,
    llmMs = timings.llmMs,
    ocrMs = timings.ocrMs,
    insertMethod = insertMethod?.name,
    failureCode = failureCode,
    revision = revision,
    contextWords = contextWords,
    pendingDelete = pendingDelete,
    searchText = SearchText.of(finalText, rawText),
)

internal fun HistoryRow.toEntry() = HistoryEntry(
    id = EntryId(id),
    createdAt = Instant.ofEpochMilli(createdAtMs),
    localDay = LocalDate.parse(localDay),
    app = AppRef(pkg, appLabel, conversation),
    status = EntryStatus.valueOf(status),
    finalText = finalText,
    rawText = rawText,
    language = language,
    asr = modelUse(asrModel, asrArtifact, asrUnit),
    // A cleanup record always has an outcome ("accept", "fallback:…", "skipped:…"); no outcome = no cleanup step.
    cleanup = cleanupOutcome?.let { CleanupUse(modelUse(llmModel, llmArtifact, llmUnit), promptVersion.orEmpty(), it, tone) },
    audio = audioRef(),
    timings = Timings(recordMs, speechMs, stopToDoneMs, asrTailMs, llmMs, ocrMs),
    insertMethod = insertMethod?.let { InsertMethod.valueOf(it) },
    failureCode = failureCode,
    revision = revision,
    contextWords = contextWords,
)

internal fun HistoryRow.audioRef(): AudioRef? =
    audioPath?.let { AudioRef(it, audioMs ?: 0, audioBytes ?: 0, audioCodec.orEmpty()) }

private fun modelUse(model: String?, artifact: String?, unit: String?): ModelUse? =
    if (model == null || artifact == null || unit == null) null
    else ModelUse(ModelId(model), ArtifactId(artifact), ComputeUnit.valueOf(unit))

internal fun DictationMetrics.toRow() = MetricsRow(
    createdAtMs = createdAt.toEpochMilli(),
    localDay = localDay.toString(),
    pkg = pkg,
    status = status.name,
    language = language,
    mixedLanguage = mixedLanguage,
    wordsFinal = wordsFinal,
    wordsRaw = wordsRaw,
    recordMs = recordMs,
    speechMs = speechMs,
    stopToDoneMs = stopToDoneMs,
    editDistanceWords = editDistanceWords,
    fillersRemoved = fillersRemoved,
    fixes = fixes,
    cleanupApplied = cleanupApplied,
)

internal fun MetricsRow.toMetrics() = DictationMetrics(
    createdAt = Instant.ofEpochMilli(createdAtMs),
    localDay = LocalDate.parse(localDay),
    pkg = pkg,
    status = EntryStatus.valueOf(status),
    language = language,
    mixedLanguage = mixedLanguage,
    wordsFinal = wordsFinal,
    wordsRaw = wordsRaw,
    recordMs = recordMs,
    speechMs = speechMs,
    stopToDoneMs = stopToDoneMs,
    editDistanceWords = editDistanceWords,
    fillersRemoved = fillersRemoved,
    fixes = fixes,
    cleanupApplied = cleanupApplied,
)

/** Benchmark results as JSON in the row; unknown keys from a newer app version are ignored on read. */
internal val benchJson = Json { ignoreUnknownKeys = true }

internal fun BenchmarkRecord.toRow() = BenchmarkRow(
    artifact = artifactId.value,
    unit = unit.name,
    appVersion = appVersion,
    fingerprint = buildFingerprint,
    atMs = atEpochMs,
    valid = result.valid,
    invalidReason = result.invalidReason,
    result = benchJson.encodeToString(BenchmarkResultData.serializer(), result),
)

internal fun BenchmarkRow.toRecord() = BenchmarkRecord(
    artifactId = ArtifactId(artifact),
    unit = ComputeUnit.valueOf(unit),
    appVersion = appVersion,
    buildFingerprint = fingerprint,
    atEpochMs = atMs,
    result = benchJson.decodeFromString(BenchmarkResultData.serializer(), result),
)
