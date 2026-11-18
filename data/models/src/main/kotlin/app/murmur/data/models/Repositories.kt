package app.murmur.data.models

import android.content.Context
import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.CustomModelRequest
import app.murmur.core.catalog.CustomModelResolver
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.dictation.ActiveModels
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ModelId
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.Settings
import app.murmur.core.store.StorageInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bundled catalog + custom entries from Room (WP-13). The bundled part already works. */
class DefaultCatalogRepository(bundled: Catalog = CatalogParser.bundled()) : CatalogRepository {
    private val _catalog = MutableStateFlow(bundled)
    override val catalog: StateFlow<Catalog> = _catalog.asStateFlow()
    override suspend fun addCustom(entry: ModelEntry) = TODO("WP-13: persist in CustomModelDao, merge")
    override suspend fun removeCustom(id: ModelId) = TODO("WP-13")
}

class DefaultInstalledModelsRepository(private val context: Context, private val storage: ModelStorage) : InstalledModelsRepository {
    override val states: StateFlow<Map<ArtifactId, InstallState>> get() = TODO("WP-13")
    override fun state(id: ArtifactId): Flow<InstallState> = TODO("WP-13")
    override suspend fun download(id: ArtifactId, wifiOnly: Boolean) = TODO("WP-13: schedule ModelDownloadJob (UIDT)")
    override suspend fun pause(id: ArtifactId) = TODO("WP-13")
    override suspend fun delete(id: ArtifactId) = TODO("WP-13")
    override suspend fun storage(): StorageInfo = TODO("WP-13")
}

/** Settings + catalog + install state + device profile + benchmarks -> LoadSpecs (ArtifactSelector). WP-13. */
class DefaultModelResolver(
    private val catalog: CatalogRepository,
    private val installed: InstalledModelsRepository,
    private val device: () -> DeviceProfile,
) : ModelResolver {
    override suspend fun resolve(settings: Settings): Outcome<ActiveModels> = TODO("WP-13")
}

/** HF API (/api/models/<repo>/revision, /paths-info) -> ModelEntry with origin CUSTOM (D3). WP-13. */
class HfCustomModelResolver : CustomModelResolver {
    override suspend fun resolve(request: CustomModelRequest): Outcome<ModelEntry> = TODO("WP-13")
}

object ModelsModule {
    fun storage(context: Context) = ModelStorage(java.io.File(context.noBackupFilesDir, "models"))
}
