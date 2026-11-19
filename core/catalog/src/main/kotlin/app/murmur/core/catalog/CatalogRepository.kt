package app.murmur.core.catalog

import kotlinx.coroutines.flow.StateFlow

/**
 * The effective catalog = bundled (or newer refreshed) + the user's custom entries. Implemented in
 * :data:models; the UI and the orchestrator read only this.
 */
interface CatalogRepository {
    /**
     * The catalog as known right now. Right after process start it can be the bundled catalog alone, before the
     * stored custom entries have been read; use [loaded] when a custom entry must be visible.
     */
    val catalog: StateFlow<Catalog>

    /**
     * The catalog once the stored custom entries have been read (suspends until then; at once afterwards). A store
     * that fails to load yields the bundled catalog, never an exception.
     */
    suspend fun loaded(): Catalog = catalog.value

    suspend fun addCustom(entry: ModelEntry)
    suspend fun removeCustom(id: app.murmur.core.model.ModelId)
}
