package app.murmur.data.db

import android.content.Context
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.Settings
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** Room-backed repositories (WP-12). Row <-> domain mapping lives in Mappers.kt (WP-12). */
class RoomHistoryRepository(private val db: MurmurDatabase, private val audio: AudioStore) : HistoryRepository {
    override fun entries(): Flow<List<HistoryEntry>> = TODO("WP-12")
    override fun entry(id: EntryId): Flow<HistoryEntry?> = TODO("WP-12")
    override fun search(query: String, pkg: String?): Flow<List<HistoryEntry>> = TODO("WP-12")
    override suspend fun insert(entry: HistoryEntry): EntryId = TODO("WP-12")
    override suspend fun update(entry: HistoryEntry) = TODO("WP-12")
    override suspend fun markDeleted(ids: Set<EntryId>) = TODO("WP-12")
    override suspend fun restore(ids: Set<EntryId>) = TODO("WP-12")
    override suspend fun commitDeletes() = TODO("WP-12: delete audio files first, then rows")
    override suspend fun deleteBefore(firstKeptDay: LocalDate): Int = TODO("WP-12")
    override suspend fun deleteAll() = TODO("WP-12")
}

class RoomMetricsRepository(private val db: MurmurDatabase) : MetricsRepository {
    override fun since(day: LocalDate): Flow<List<DictationMetrics>> = TODO("WP-12")
    override suspend fun add(m: DictationMetrics) = TODO("WP-12")
    override suspend fun deleteBefore(day: LocalDate): Int = TODO("WP-12")
}

class RoomBenchmarkRepository(private val db: MurmurDatabase) : BenchmarkRepository {
    override fun latest(id: ArtifactId): Flow<List<BenchmarkRecord>> = TODO("WP-12")
    override suspend fun save(record: BenchmarkRecord) = TODO("WP-12")
}

/** Typed DataStore with a kotlinx.serialization JSON serializer of [Settings] (ADR-012). */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    override val settings: StateFlow<Settings> get() = TODO("WP-12: DataStoreFactory.create(SettingsSerializer) + stateIn")
    override suspend fun update(transform: (Settings) -> Settings) = TODO("WP-12")
}

/** Retention runner: history by Settings.retentionDays, metrics by RetentionPolicy.STATS_DAYS (WP-12). */
class Retention(private val history: HistoryRepository, private val metrics: MetricsRepository, private val clock: MurmurClock) {
    suspend fun runNow(retentionDays: Int): Int = TODO("WP-12")
}

/** Module entry point used by AppContainer; WP-12 wires real classes here without touching :app. */
object DbModule {
    fun database(context: Context): MurmurDatabase = MurmurDatabase.create(context)
}
