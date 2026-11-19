package app.murmur.testing

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.AudioWriter
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.Settings
import app.murmur.core.store.SettingsRepository
import app.murmur.core.store.StorageInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** [writeDelayMs] = how long insert / update take (coroutine time: a pill tapped before its entry is written). */
class InMemoryHistoryRepository(var writeDelayMs: Long = 0) : HistoryRepository {
    private val rows = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val deleted = mutableSetOf<EntryId>()
    private var nextId = 1L

    private fun visible() = rows.map { l -> l.filter { it.id !in deleted }.sortedByDescending { it.createdAt } }
    override fun entries(): Flow<List<HistoryEntry>> = visible()
    override fun entry(id: EntryId): Flow<HistoryEntry?> = visible().map { l -> l.firstOrNull { it.id == id } }
    override fun search(query: String, pkg: String?): Flow<List<HistoryEntry>> = visible().map { l ->
        l.filter { e -> (pkg == null || e.app.pkg == pkg) &&
            listOfNotNull(e.finalText, e.rawText).any { it.contains(query, ignoreCase = true) } }
    }
    override suspend fun insert(entry: HistoryEntry): EntryId {
        if (writeDelayMs > 0) kotlinx.coroutines.delay(writeDelayMs)
        val id = EntryId(nextId++)
        rows.value = rows.value + entry.copy(id = id)
        return id
    }
    override suspend fun update(entry: HistoryEntry) {
        if (writeDelayMs > 0) kotlinx.coroutines.delay(writeDelayMs)
        rows.value = rows.value.map { if (it.id == entry.id) entry else it }
    }
    override suspend fun markDeleted(ids: Set<EntryId>) { deleted += ids; rows.value = rows.value.toList() }
    override suspend fun restore(ids: Set<EntryId>) { deleted -= ids; rows.value = rows.value.toList() }
    override suspend fun commitDeletes() { rows.value = rows.value.filter { it.id !in deleted }; deleted.clear() }
    override suspend fun deleteBefore(firstKeptDay: LocalDate): Int {
        val before = rows.value.size
        rows.value = rows.value.filter { !it.localDay.isBefore(firstKeptDay) }
        return before - rows.value.size
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    fun snapshot(): List<HistoryEntry> = rows.value
}

class InMemoryMetricsRepository : MetricsRepository {
    private val rows = MutableStateFlow<List<DictationMetrics>>(emptyList())
    override fun since(day: LocalDate): Flow<List<DictationMetrics>> = rows.map { l -> l.filter { !it.localDay.isBefore(day) } }
    override suspend fun add(m: DictationMetrics) { rows.value = rows.value + m }
    override suspend fun deleteBefore(day: LocalDate): Int {
        val n = rows.value.size; rows.value = rows.value.filter { !it.localDay.isBefore(day) }; return n - rows.value.size
    }
    fun snapshot() = rows.value
}

class InMemorySettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = _settings.asStateFlow()
    override suspend fun update(transform: (Settings) -> Settings) { _settings.value = transform(_settings.value) }
    /** Non-suspending [update] for test setup. */
    fun edit(transform: (Settings) -> Settings) { _settings.value = transform(_settings.value) }
}

/** Every artifact is "installed" under /fake/models/<id> unless listed in [missing]. */
class FakeInstalledModelsRepository(private val missing: MutableSet<ArtifactId> = mutableSetOf()) : InstalledModelsRepository {
    private val _states = MutableStateFlow<Map<ArtifactId, InstallState>>(emptyMap())
    override val states: StateFlow<Map<ArtifactId, InstallState>> = _states.asStateFlow()
    override fun state(id: ArtifactId): Flow<InstallState> = states.map { it[id] ?: default(id) }
    private fun default(id: ArtifactId) = if (id in missing) InstallState.NotInstalled else InstallState.Installed("/fake/models/$id", 1)
    override suspend fun download(id: ArtifactId, wifiOnly: Boolean) {
        missing -= id; _states.value = _states.value + (id to InstallState.Installed("/fake/models/$id", 1))
    }
    override suspend fun pause(id: ArtifactId) {}
    override suspend fun delete(id: ArtifactId) { missing += id; _states.value = _states.value + (id to InstallState.NotInstalled) }
    override suspend fun storage() = StorageInfo(modelsBytes = 2_400_000_000, freeBytes = 181_000_000_000)
    fun isInstalled(id: ArtifactId) = id !in missing
}

class InMemoryBenchmarkRepository : BenchmarkRepository {
    private val rows = MutableStateFlow<List<BenchmarkRecord>>(emptyList())
    /** Newest record per unit, newest first (the BenchmarkRepository contract, as the Room store does). */
    override fun latest(id: ArtifactId): Flow<List<BenchmarkRecord>> =
        rows.map { l -> l.filter { it.artifactId == id }.sortedByDescending { it.atEpochMs }.distinctBy { it.unit } }
    /** Searches every saved record, so an invalid newer run never hides an older valid one. */
    override suspend fun latestValid(id: ArtifactId, unit: ComputeUnit): BenchmarkRecord? =
        rows.value.filter { it.artifactId == id && it.unit == unit && it.result.valid }.maxByOrNull { it.atEpochMs }
    override suspend fun save(record: BenchmarkRecord) { rows.value = rows.value + record }
}

/** [readDelayMs] = how long readPcm takes (coroutine time; the real store decodes Ogg/Opus). */
class InMemoryAudioStore(var readDelayMs: Long = 0) : AudioStore {
    val files = mutableMapOf<String, ShortArray>()
    private val refs = mutableMapOf<String, AudioRef>()
    override fun newWriter(key: String): AudioWriter = object : AudioWriter {
        private val buf = ArrayList<Short>()
        override fun write(samples: ShortArray, count: Int) { for (i in 0 until count) buf += samples[i] }
        override suspend fun close(): AudioRef? {
            if (buf.isEmpty()) return null
            val path = "mem://audio/$key.ogg"
            files[path] = buf.toShortArray()
            return AudioRef(path, buf.size * 1000L / 16_000, buf.size * 2L / 10, "opus").also { refs[path] = it }
        }
        override fun discard() { buf.clear() }
    }
    override suspend fun delete(ref: AudioRef) { files.remove(ref.path); refs.remove(ref.path) }
    override suspend fun listAll(): List<AudioRef> = refs.values.toList()
    override suspend fun readPcm(ref: AudioRef): ShortArray? {
        if (readDelayMs > 0) kotlinx.coroutines.delay(readDelayMs)
        return files[ref.path]
    }
    override suspend fun exists(ref: AudioRef) = ref.path in files
}
