package app.murmur.data.db

import android.content.Context
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.core.store.StatsCalculator
import kotlinx.coroutines.CoroutineScope

/**
 * Entry point of :data:db for AppContainer (ARCHITECTURE 2, rule 5): everything is returned as its core:store
 * interface. Wiring in :main:
 * ```
 * private val db by lazy { DbModule.database(context) }
 * val history by lazy { DbModule.history(db, audioStore) }
 * val metrics by lazy { DbModule.metrics(db) }
 * val benchmarks by lazy { DbModule.benchmarks(db) }
 * val settings by lazy { DbModule.settings(context, appScope) }
 * val retention by lazy { DbModule.retention(context, db, history, metrics, audioStore, settings, clock, appScope) }
 * init { retention.start() }          // app-start run + midnight job + shortening watcher
 * SettingsDeps(retention = retention::runNow, …); DashboardDeps(stats = DbModule.stats(), …)
 * ```
 */
object DbModule {
    @Volatile private var db: MurmurDatabase? = null

    /** The process's single Room database (two instances on one file would not see each other's changes). */
    fun database(context: Context): MurmurDatabase =
        db ?: synchronized(this) { db ?: MurmurDatabase.create(context).also { db = it } }

    fun history(db: MurmurDatabase, audio: AudioStore): HistoryRepository = RoomHistoryRepository(db, audio)

    fun metrics(db: MurmurDatabase): MetricsRepository = RoomMetricsRepository(db)

    fun benchmarks(db: MurmurDatabase): BenchmarkRepository = RoomBenchmarkRepository(db)

    /** User-added models (WP-13 stores validated ModelEntry JSON here). */
    fun customModels(db: MurmurDatabase): CustomModelDao = db.customModels()

    /**
     * Typed DataStore; its StateFlow is collected eagerly in [scope] (AppContainer.appScope). Every repository made here
     * or with `DataStoreSettingsRepository(context)` shares the process's one DataStore, so a second instance neither
     * fails nor sees different values; one per process is still the intent (one collector).
     */
    fun settings(context: Context, scope: CoroutineScope): SettingsRepository = DataStoreSettingsRepository(context, scope)

    fun stats(): StatsCalculator = DefaultStatsCalculator()

    fun retention(
        context: Context,
        db: MurmurDatabase,
        history: HistoryRepository,
        metrics: MetricsRepository,
        audio: AudioStore,
        settings: SettingsRepository,
        clock: MurmurClock,
        scope: CoroutineScope,
    ): RetentionScheduler {
        val dao = db.history()
        val retention = Retention(history, metrics, audio, clock) { dao.audioPaths().toSet() }
        return RetentionScheduler(context.applicationContext, retention, settings, clock, scope)
    }
}
