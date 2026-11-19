package app.murmur.data.models

import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.catalog.DownloadPlanner
import app.murmur.core.catalog.PlannedDownload
import app.murmur.core.model.ModelId
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.Settings
import kotlinx.coroutines.flow.first

/**
 * What onboarding and the hub call to download (ADR-050, ARCHITECTURE 8.5): a pack, one model, or the OCR pair when
 * "Use what's on screen" is turned on. The rules live in [DownloadPlanner]; this class adds the device profile, the
 * per-model unit preference, skips what is already installed or on its way, and queues the rest.
 */
class ModelDownloadRules(
    private val catalog: CatalogRepository,
    private val device: DeviceProfileRepository,
    private val installed: InstalledModelsRepository,
) {
    /** Onboarding pack rows: what [downloadPack] would fetch (sizes are the artifacts', not placeholders). */
    suspend fun planPack(packId: String, settings: Settings): List<PlannedDownload> {
        val c = catalog.catalog.value
        val pack = c.packs.firstOrNull { it.id == packId } ?: return emptyList()
        return DownloadPlanner.forPack(c, pack, device.ensure(), settings.useScreen, prefOf(settings))
    }

    suspend fun downloadPack(packId: String, settings: Settings): List<PlannedDownload> =
        queue(planPack(packId, settings), settings)

    /** "Download" / "Use this model" in the hub; a pinned unit whose artifact is missing downloads that artifact. */
    suspend fun downloadModel(model: ModelId, settings: Settings): List<PlannedDownload> {
        val c = catalog.catalog.value
        return queue(DownloadPlanner.forModels(c, listOf(model), device.ensure(), useScreen = false, prefOf(settings)), settings)
    }

    /** Settings: "Use what's on screen" turned on. */
    suspend fun onUseScreenEnabled(settings: Settings): List<PlannedDownload> =
        queue(DownloadPlanner.ocrPair(catalog.catalog.value, device.ensure()), settings)

    private fun prefOf(settings: Settings): (ModelId) -> UnitPreference = { settings.unitPreference[it.value] ?: UnitPreference.Auto }

    private suspend fun queue(plan: List<PlannedDownload>, settings: Settings): List<PlannedDownload> {
        val queued = plan.filter { p ->
            when (installed.state(p.artifact.id).first()) {
                is InstallState.Installed, is InstallState.Queued, is InstallState.Downloading, InstallState.Verifying,
                InstallState.Extracting, is InstallState.Preparing -> false
                else -> true
            }
        }
        for (p in queued) installed.download(p.artifact.id, settings.wifiOnlyDownloads)
        return queued
    }
}
