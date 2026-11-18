package app.murmur.data.models

import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.Origin
import app.murmur.core.model.ModelId
import app.murmur.data.db.CustomModelDao
import app.murmur.data.db.CustomModelRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Stored custom entries as JSON (Room `custom_model`, WP-12's DAO; in-memory in tests). */
interface CustomModelStore {
    val entries: Flow<List<String>>
    suspend fun put(id: String, json: String, addedMs: Long)
    suspend fun delete(id: String)
}

class RoomCustomModelStore(private val dao: CustomModelDao) : CustomModelStore {
    override val entries: Flow<List<String>> = dao.all().map { rows -> rows.map { it.json } }
    override suspend fun put(id: String, json: String, addedMs: Long) = dao.put(CustomModelRow(id, json, addedMs))
    override suspend fun delete(id: String) = dao.delete(id)
}

/**
 * The effective catalog = bundled + the user's custom entries (ARCHITECTURE 8.6, 8.7). A stored entry that no
 * longer parses or validates, or that collides with a bundled id, is left out (it never breaks the catalog).
 * [addCustom] upserts (WP-11 stores the entry again after Preparing measured its prefix,
 * GgufTemplates.withMeasuredPrefix).
 */
class DefaultCatalogRepository(
    private val bundled: Catalog = CatalogParser.bundled(),
    private val store: CustomModelStore,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : CatalogRepository {
    override val catalog: StateFlow<Catalog> = store.entries
        .map { rows -> merge(bundled, rows.mapNotNull(::decode)) }
        .stateIn(scope, SharingStarted.Eagerly, bundled)

    /** Throws IllegalArgumentException for an entry that is not a valid custom entry (resolver output always is). */
    override suspend fun addCustom(entry: ModelEntry) {
        require(entry.origin == Origin.CUSTOM) { "only custom entries are stored" }
        require(bundled.model(entry.id) == null) { "id collides with a bundled model" }
        val problems = CatalogValidator.validateEntry(entry)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        store.put(entry.id.value, CatalogParser.json.encodeToString(ModelEntry.serializer(), entry), now())
    }

    override suspend fun removeCustom(id: ModelId) = store.delete(id.value)

    private fun decode(json: String): ModelEntry? = try {
        CatalogParser.json.decodeFromString(ModelEntry.serializer(), json)
            .takeIf { it.origin == Origin.CUSTOM && CatalogValidator.validateEntry(it).isEmpty() }
    } catch (e: Exception) {
        null
    }

    companion object {
        fun merge(bundled: Catalog, custom: List<ModelEntry>): Catalog {
            val bundledIds = bundled.models.map { it.id }.toSet()
            val bundledArtifacts = bundled.models.flatMap { m -> m.artifacts.map { it.id } }.toSet()
            val extra = custom.filter { it.id !in bundledIds && it.artifacts.none { a -> a.id in bundledArtifacts } }
                .distinctBy { it.id }
            return if (extra.isEmpty()) bundled else bundled.copy(models = bundled.models + extra)
        }
    }
}
