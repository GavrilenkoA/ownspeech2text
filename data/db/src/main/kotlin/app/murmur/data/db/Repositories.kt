package app.murmur.data.db

import androidx.room.withTransaction
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurError
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.MetricsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * History rows + their audio files (DESIGN 6.3). Every deletion removes the audio files first and the rows second, so a
 * crash in between leaves at worst an orphan file (swept at the next start by [Retention.runAtStart]), never a
 * row that points at a missing recording.
 */
class RoomHistoryRepository(
    private val db: MurmurDatabase,
    private val audio: AudioStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HistoryRepository {
    private val dao = db.history()
    /** Serialises the read-delete sequences so that a restore never races a commit of the same rows. */
    private val deletes = Mutex()

    override fun entries(): Flow<List<HistoryEntry>> = dao.visible().map { rows -> rows.map { it.toEntry() } }

    override fun entry(id: EntryId): Flow<HistoryEntry?> = dao.visibleById(id.value).map { it?.toEntry() }.distinctUntilChanged()

    override fun search(query: String, pkg: String?): Flow<List<HistoryEntry>> =
        dao.search(SearchText.pattern(query), pkg).map { rows -> rows.map { it.toEntry() } }

    /** The id of [entry] is ignored: Room assigns it. */
    override suspend fun insert(entry: HistoryEntry): EntryId = withContext(io) { EntryId(dao.insert(entry.toRow(id = 0))) }

    /** Re-polish and late fields; keeps the row's pending-delete flag (an entry deleted meanwhile stays deleted). */
    override suspend fun update(entry: HistoryEntry) = withContext(io) {
        db.withTransaction {
            val current = dao.row(entry.id.value) ?: return@withTransaction
            dao.update(entry.toRow(pendingDelete = current.pendingDelete))
        }
    }

    override suspend fun markDeleted(ids: Set<EntryId>) = withContext(io) {
        deletes.withLock { ids.chunked(CHUNK).forEach { c -> dao.setPendingDelete(c.map { it.value }, true) } }
    }

    override suspend fun restore(ids: Set<EntryId>) = withContext(io) {
        deletes.withLock { ids.chunked(CHUNK).forEach { c -> dao.setPendingDelete(c.map { it.value }, false) } }
    }

    override suspend fun commitDeletes() = withContext(io) { deletes.withLock { remove(dao.pending()) }; Unit }

    override suspend fun deleteBefore(firstKeptDay: LocalDate): Int =
        withContext(io) { deletes.withLock { remove(dao.before(firstKeptDay.toString())) } }

    override suspend fun deleteAll() = withContext(io) { deletes.withLock { remove(dao.allRows()) }; Unit }

    /** Audio files first, then exactly these rows. Returns the number of rows deleted. */
    private suspend fun remove(rows: List<HistoryRow>): Int {
        if (rows.isEmpty()) return 0
        for (row in rows) row.audioRef()?.let { deleteAudio(it) }
        return rows.map { it.id }.chunked(CHUNK).sumOf { dao.deleteIds(it) }
    }

    /** A file that cannot be deleted now is left for the orphan sweep; the row still goes (the user asked for it). */
    private suspend fun deleteAudio(ref: AudioRef) {
        try {
            audio.delete(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DbLog.code(MurmurError.StorageFailed(null).code, "audio_delete")
        }
    }

    private companion object {
        /** Below SQLite's bound-variable limit on every API level. */
        const val CHUNK = 500
    }
}

/** Numbers-only rows (ADR-035: kept [app.murmur.core.store.RetentionPolicy.STATS_DAYS] days, independent of history). */
class RoomMetricsRepository(
    db: MurmurDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MetricsRepository {
    private val dao = db.metrics()

    override fun since(day: LocalDate): Flow<List<DictationMetrics>> = dao.since(day.toString()).map { rows -> rows.map { it.toMetrics() } }

    override suspend fun add(m: DictationMetrics) = withContext(io) { dao.insert(m.toRow()) }

    override suspend fun deleteBefore(day: LocalDate): Int = withContext(io) { dao.deleteBefore(day.toString()) }
}

/** Every run is kept (valid or not, ARCHITECTURE 10); [latest] shows the newest per unit, [latestValid] feeds selection. */
class RoomBenchmarkRepository(
    db: MurmurDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BenchmarkRepository {
    private val dao = db.benchmarks()

    override fun latest(id: ArtifactId): Flow<List<BenchmarkRecord>> =
        dao.forArtifact(id.value).map { rows -> rows.distinctBy { it.unit }.map { it.toRecord() } }

    override suspend fun save(record: BenchmarkRecord) = withContext(io) { dao.insert(record.toRow()) }

    override suspend fun latestValid(id: ArtifactId, unit: ComputeUnit): BenchmarkRecord? =
        withContext(io) { dao.latestValid(id.value, unit.name)?.toRecord() }
}
