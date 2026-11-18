package app.murmur.core.catalog

import kotlinx.coroutines.flow.StateFlow

/**
 * The effective catalog = bundled (or newer refreshed) + the user's custom entries. Implemented in
 * :data:models; the UI and the orchestrator read only this.
 */
interface CatalogRepository {
    val catalog: StateFlow<Catalog>
    suspend fun addCustom(entry: ModelEntry)
    suspend fun removeCustom(id: app.murmur.core.model.ModelId)
}
