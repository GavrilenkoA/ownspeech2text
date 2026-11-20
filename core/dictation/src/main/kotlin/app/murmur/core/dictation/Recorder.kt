package app.murmur.core.dictation

import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.MurmurError
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.store.AppRef
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.CleanupUse
import app.murmur.core.store.DeliveryMetrics
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.InsertMethod
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.ModelUse
import app.murmur.core.store.RetentionPolicy
import app.murmur.core.store.Timings
import java.time.LocalDate

/**
 * History row + metrics row of one dictation (ARCHITECTURE 11 step 6, 12). Text only, never screen text (D9);
 * `context_words` is a number (ADR-043). Retention 0 writes no history row and no audio (RetentionPolicy.keepsHistory);
 * the numbers-only metrics row is independent of history retention (ADR-035). IN_APP practice stores nothing.
 * A failing write never blocks delivery: the text is already out. A retry (SessionRun.retryOf) updates its entry in
 * place, keeps its audio and adds no metrics row: the dictation's numbers row was written when it first failed, and
 * metrics rows are append-only (ADR-035), so a second row would count one dictation and its recording time twice.
 */
internal class Recorder(
    private val history: HistoryRepository,
    private val metrics: MetricsRepository,
    private val audioStore: AudioStore,
    private val deliveryMetrics: DeliveryMetrics,
    private val guard: OutputGuard,
    private val clock: MurmurClock,
) {
    private fun storesNothing(run: SessionRun) = run.mode == StartMode.IN_APP

    private fun keepsHistory(run: SessionRun) = !storesNothing(run) && RetentionPolicy.keepsHistory(run.settings.retentionDays)

    /** What RecordFailure stored: the entry and whether it holds the recording (Retry can re-run it). */
    data class Recorded(val id: EntryId, val audioKept: Boolean)

    /** A delivered (or not delivered) text: Inserted / Copied / Failed(NotDelivered, NothingToInsert). */
    suspend fun text(
        run: SessionRun,
        status: EntryStatus,
        finalText: String?,
        rawText: String?,
        cleaned: Boolean,
        outcome: String,
        method: InsertMethod?,
        failure: MurmurError?,
    ): EntryId? {
        if (storesNothing(run)) return null
        val capture = run.captureStopped.await()
        val stopToDone = run.stopAtElapsedMs?.let { clock.elapsedMs() - it }
        run.retryEntry?.let { e ->
            val failureCode = failure?.code ?: Cleaner.failureCodeOf(outcome, null)
            return updateRetried(run, e, status, finalText, rawText, outcome, method, failureCode, stopToDone)
        }
        writeMetrics(run, status, finalText.orEmpty(), rawText.orEmpty(), capture, stopToDone, cleaned)
        if (!keepsHistory(run)) { capture?.audio?.let { deleteQuietly(it) }; return null }
        val failureCode = failure?.code ?: Cleaner.failureCodeOf(outcome, null)
        return insert(run, status, finalText, rawText, outcome, method, failureCode, capture?.audio, capture, stopToDone)
    }

    /** RecordFailure: a Failed entry, with the audio when [keepAudio] (I6: the only path that keeps audio without text). */
    suspend fun failure(run: SessionRun, error: MurmurError, keepAudio: Boolean): Recorded? {
        if (storesNothing(run)) return null
        val capture = run.captureStopped.await()
        val audio = capture?.audio
        val stopToDone = run.stopAtElapsedMs?.let { clock.elapsedMs() - it }
        run.retryEntry?.let { e ->
            val id = updateRetried(run, e, EntryStatus.FAILED, null, null, null, null, error.code, stopToDone) ?: return null
            return Recorded(id, audioKept = e.audio != null)
        }
        writeMetrics(run, EntryStatus.FAILED, "", "", capture, stopToDone, cleaned = false)
        if (!keepsHistory(run) || !keepAudio) audio?.let { deleteQuietly(it) }
        if (!keepsHistory(run)) return null
        val kept = audio?.takeIf { keepAudio }
        val id = insert(run, EntryStatus.FAILED, null, null, null, null, error.code, kept, capture, stopToDone) ?: return null
        return Recorded(id, audioKept = kept != null)
    }

    /** Password at delivery / cancel after the file was written: nothing may remain. A retry's audio is the entry's own. */
    suspend fun discardAudio(run: SessionRun) {
        if (run.retryOf != null) return
        run.captureStopped.await()?.audio?.let { deleteQuietly(it) }
    }

    /** The retried entry with this run's result; recording, app and creation time stay the dictation's own. */
    private suspend fun updateRetried(
        run: SessionRun, e: HistoryEntry, status: EntryStatus, finalText: String?, rawText: String?, outcome: String?,
        method: InsertMethod?, failureCode: String?, stopToDone: Long?,
    ): EntryId? {
        if (!keepsHistory(run)) return null
        val t = run.transcript
        val updated = e.copy(
            status = status,
            finalText = finalText,
            rawText = rawText ?: e.rawText,
            language = t?.language ?: e.language,
            asr = run.asrSpec?.use() ?: e.asr,
            cleanup = outcome?.let { cleanupUse(run, it, e.app.pkg) } ?: e.cleanup,
            insertMethod = method,
            failureCode = failureCode,
            timings = e.timings.copy(
                speechMs = t?.speechMs ?: e.timings.speechMs,
                stopToDoneMs = stopToDone,
                asrTailMs = run.asrTailMs,
                llmMs = run.llmMs,
                ocrMs = null,
            ),
            contextWords = null,
        )
        return try { history.update(updated); e.id } catch (x: Exception) { if (x is kotlinx.coroutines.CancellationException) throw x; null }
    }

    private fun cleanupUse(run: SessionRun, outcome: String, pkg: String) = CleanupUse(
        model = run.llmSpec?.takeIf { run.promptVersion != null }?.use(),
        promptVersion = run.promptVersion ?: run.models?.llm?.profile.orEmpty(),
        outcome = outcome,
        tone = (run.tone ?: run.settings.tonePerApp[pkg])?.name?.lowercase(),
    )

    private suspend fun insert(
        run: SessionRun, status: EntryStatus, finalText: String?, rawText: String?, outcome: String?, method: InsertMethod?,
        failureCode: String?, audio: AudioRef?, capture: CaptureResult?, stopToDone: Long?,
    ): EntryId? {
        val target = run.target ?: return null
        val t = run.transcript
        val entry = HistoryEntry(
            id = EntryId(0),
            createdAt = run.createdAt,
            localDay = LocalDate.ofInstant(run.createdAt, clock.zone()),
            app = AppRef(target.pkg, target.label, target.windowTitle),
            status = status,
            finalText = finalText,
            rawText = rawText,
            language = t?.language,
            asr = run.asrSpec?.use(),
            cleanup = outcome?.let { cleanupUse(run, it, target.pkg) },
            audio = audio,
            timings = Timings(
                recordMs = capture?.durationMs ?: 0,
                speechMs = t?.speechMs ?: 0,
                stopToDoneMs = stopToDone,
                asrTailMs = run.asrTailMs,
                llmMs = run.llmMs,
                ocrMs = run.ocrMs,
            ),
            insertMethod = method,
            failureCode = failureCode,
            contextWords = run.contextWords?.takeIf { run.cleanupUsedScreen },
        )
        return try { history.insert(entry) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
    }

    private suspend fun writeMetrics(run: SessionRun, status: EntryStatus, final: String, raw: String, capture: CaptureResult?, stopToDone: Long?, cleaned: Boolean) {
        val target = run.target ?: return
        val fillers = guard.fillers
        val m = DictationMetrics(
            createdAt = run.createdAt,
            localDay = LocalDate.ofInstant(run.createdAt, clock.zone()),
            pkg = target.pkg,
            status = status,
            language = run.transcript?.language,
            mixedLanguage = deliveryMetrics.mixedLanguage(final.ifEmpty { raw }),
            wordsFinal = deliveryMetrics.wordCount(final),
            wordsRaw = deliveryMetrics.wordCount(raw),
            recordMs = capture?.durationMs ?: 0,
            speechMs = run.transcript?.speechMs ?: 0,
            stopToDoneMs = stopToDone,
            editDistanceWords = if (raw.isEmpty() || final.isEmpty()) 0 else deliveryMetrics.editDistanceWords(raw, final),
            fillersRemoved = if (raw.isEmpty() || final.isEmpty()) 0 else deliveryMetrics.fillersRemoved(raw, final, fillers),
            fixes = if (raw.isEmpty() || final.isEmpty()) 0 else deliveryMetrics.fixes(raw, final, fillers),
            cleanupApplied = cleaned,
        )
        try { metrics.add(m) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }

    private suspend fun deleteQuietly(ref: AudioRef) {
        try { audioStore.delete(ref) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }

    private fun LoadSpec.use() = ModelUse(modelId, artifactId, unit)
}
