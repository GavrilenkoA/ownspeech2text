package app.murmur.data.db

import app.murmur.core.model.MurmurClock
import app.murmur.core.model.MurmurError
import app.murmur.core.store.AudioStore
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.RetentionPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Retention runner (ARCHITECTURE 12, DESIGN 6.3 "Retention mechanics"). Local-day semantics: an entry belongs to the
 * local day stored at its creation, and "today" is the clock's date in the zone current at the run, so changing the
 * zone never moves an entry to another day. Runs are serialised; every run is idempotent.
 */
class Retention(
    private val history: HistoryRepository,
    private val metrics: MetricsRepository,
    private val audio: AudioStore,
    private val clock: MurmurClock,
    /** Audio paths any history row refers to, pending deletes included (they can still be restored). */
    private val referencedAudioPaths: suspend () -> Set<String>,
) {
    private val lock = Mutex()

    /**
     * History by [retentionDays] (entries of a local day before [RetentionPolicy.firstKeptDay], with their audio files;
     * 0 = everything, today included), metrics by [RetentionPolicy.STATS_DAYS]. Returns the history entries deleted.
     * The function `SettingsDeps.retention` calls after the user shortened the period.
     */
    suspend fun runNow(retentionDays: Int): Int = lock.withLock { purge(retentionDays) }

    /**
     * App start: commits deletes an Undo snackbar left pending when the process died, runs [runNow], then deletes
     * orphan audio files. Returns the history entries deleted by the period.
     */
    suspend fun runAtStart(retentionDays: Int): Int = lock.withLock {
        history.commitDeletes()
        purge(retentionDays).also { sweepOrphans() }
    }

    /** The midnight job: like [runAtStart] but leaves pending deletes alone (an Undo snackbar may be on screen). */
    suspend fun runScheduled(retentionDays: Int): Int = lock.withLock { purge(retentionDays).also { sweepOrphans() } }

    /**
     * Deletes stored audio files no history row refers to. A file whose [app.murmur.core.model.AudioKey] time is within
     * [ORPHAN_GRACE_MS] of now is kept: its dictation may still be encoding or about to insert its row.
     * Returns the number of files deleted.
     */
    suspend fun deleteOrphanAudio(): Int = lock.withLock { sweepOrphans() }

    private suspend fun purge(retentionDays: Int): Int {
        val today = LocalDate.ofInstant(clock.now(), clock.zone())
        val deleted = history.deleteBefore(RetentionPolicy.firstKeptDay(today, retentionDays))
        metrics.deleteBefore(RetentionPolicy.firstKeptDay(today, RetentionPolicy.STATS_DAYS))
        return deleted
    }

    private suspend fun sweepOrphans(): Int {
        val referenced = referencedAudioPaths()
        val now = clock.now().toEpochMilli()
        var n = 0
        for (ref in audio.listAll()) {
            if (ref.path in referenced) continue
            val keyMs = audioKeyTime(ref.path)
            if (keyMs != null && abs(now - keyMs) < ORPHAN_GRACE_MS) continue
            try {
                audio.delete(ref)
                n++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DbLog.code(MurmurError.StorageFailed(null).code, "orphan_delete")
            }
        }
        return n
    }

    companion object {
        /** Well beyond the longest dictation plus its encoding (recordings are capped at 5 min, ARCHITECTURE 4.3). */
        const val ORPHAN_GRACE_MS = 60 * 60 * 1000L

        /** "…/<createdAtEpochMs>-<session>.ogg" -> createdAtEpochMs; null when the name is not an AudioKey. */
        internal fun audioKeyTime(path: String): Long? =
            path.substringAfterLast('/').substringBefore('.').substringBefore('-').toLongOrNull()
    }
}
