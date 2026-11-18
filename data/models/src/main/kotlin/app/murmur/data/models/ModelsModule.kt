package app.murmur.data.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.dictation.EnginePreparer
import app.murmur.core.engine.InferenceHost
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The device-profile probe on Android (ARCHITECTURE 8.4): SoC from Build.SOC_MODEL, ABI, total RAM; cache
 * noBackupFilesDir/device-profile.json per app version. See [DeviceProfileProber].
 */
class DefaultDeviceProfileRepository(
    private val context: Context,
    private val asrHost: InferenceHost,
    private val llmHost: InferenceHost,
    appVersion: String = ModelsModule.appVersion(context),
) : DeviceProfileRepository {
    private val prober = DeviceProfileProber(
        cacheFile = File(context.noBackupFilesDir, "device-profile.json"),
        appVersion = appVersion,
        asrHost = asrHost,
        llmHost = llmHost,
        base = {
            val mi = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            BaseDeviceInfo(
                soc = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                totalRamMb = (mi.totalMem / (1024 * 1024)).toInt(),
            )
        },
        log = AndroidDlLog,
    )
    override val profile: StateFlow<DeviceProfile?> = prober.profile
    override suspend fun ensure(): DeviceProfile = prober.ensure()
    override suspend fun reprobe(): DeviceProfile = prober.reprobe()
}

/** Construction helpers for AppContainer (WP-15/WP-20); only AppContainer builds implementations (ARCHITECTURE 2). */
object ModelsModule {
    fun storage(context: Context) = ModelStorage(File(context.noBackupFilesDir, "models"))

    /** versionName + versionCode: unit health and the device profile reset when it changes. */
    fun appVersion(context: Context): String {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${p.versionName}+${p.longVersionCode}"
    }

    /** Bytes the app may still write on the models volume (StorageManager.getAllocatableBytes, android-platform 7.4). */
    fun freeBytes(context: Context, storage: ModelStorage): () -> Long = {
        storage.root.mkdirs()
        try {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.getAllocatableBytes(sm.getUuidForPath(storage.root))
        } catch (e: Exception) {
            storage.root.usableSpace
        }
    }

    /** The process's download machinery; the Application exposes it through [ModelJobHost]. */
    fun downloads(context: Context, catalog: CatalogRepository, preparer: () -> EnginePreparer?): ModelDownloads {
        val storage = storage(context)
        return ModelDownloads(
            storage = storage,
            catalog = catalog,
            scheduler = JobSchedulerDownloads(context.applicationContext),
            http = UrlConnectionTransport(),
            preparer = preparer,
            freeBytes = freeBytes(context, storage),
            log = AndroidDlLog,
        )
    }
}
