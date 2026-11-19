package app.murmur.testing.contrib

import app.murmur.core.catalog.Catalog
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.StorageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WP-15: downloads that walk through every install state like the real job (ARCHITECTURE 8.5), one artifact at a time:
 * Queued -> Downloading (in [steps] steps of [stepMs], at [bytesPerSec]) -> Verifying -> Preparing (0.5, then 1.0) ->
 * Installed. [stopAt] makes an artifact stop half way with that reason (NoNetwork, LowStorage, ...) once; a retry then
 * completes it. [pause] stops the running or queued artifact with DownloadPaused. Used by the onboarding flow test and
 * the debug fake container; sizes come from the catalog.
 */
class Wp15StagedDownloads(
    private val catalog: () -> Catalog,
    private val scope: CoroutineScope,
    var stepMs: Long = 60,
    var steps: Int = 10,
    var bytesPerSec: Long = 18_400_000,
    val stopAt: MutableMap<ArtifactId, MurmurError> = mutableMapOf(),
    installed: Set<ArtifactId> = emptySet(),
    var freeBytes: Long = 181_000_000_000,
) : InstalledModelsRepository {
    private val _states = MutableStateFlow<Map<ArtifactId, InstallState>>(installed.associateWith { InstallState.Installed("/fake/models/$it", 1) })
    override val states: StateFlow<Map<ArtifactId, InstallState>> = _states.asStateFlow()
    private val queue = Channel<Pair<ArtifactId, Boolean>>(Channel.UNLIMITED)
    private val lock = Mutex()
    private var current: Pair<ArtifactId, Job>? = null
    /** Every download() call, in order. */
    val requests = mutableListOf<ArtifactId>()

    init {
        scope.launch {
            for ((id, wifi) in queue) {
                if (_states.value[id] !is InstallState.Queued) continue
                val job = scope.launch { run(id, wifi) }
                lock.withLock { current = id to job }
                job.join()
                lock.withLock { current = null }
            }
        }
    }

    private fun set(id: ArtifactId, s: InstallState) { _states.value = _states.value + (id to s) }

    /** Puts [id] into [state] directly (screenshot seeds: a row mid-download, paused, preparing). */
    fun seed(id: ArtifactId, state: InstallState) = set(id, state)

    private suspend fun run(id: ArtifactId, @Suppress("UNUSED_PARAMETER") wifi: Boolean) {
        val total = catalog().artifact(id)?.second?.downloadBytes ?: 1L
        val start = (_states.value[id] as? InstallState.Paused)?.bytesDone ?: 0L
        val stop = stopAt[id]
        for (i in 1..steps) {
            delay(stepMs)
            val done = start + (total - start) * i / steps
            if (stop != null && i > steps / 2) {
                stopAt.remove(id)
                set(id, InstallState.Paused(done, total, stop))
                return
            }
            set(id, InstallState.Downloading(done, total, bytesPerSec))
        }
        set(id, InstallState.Verifying); delay(stepMs)
        set(id, InstallState.Preparing(0.5f)); delay(stepMs)
        set(id, InstallState.Preparing(1f)); delay(stepMs)
        set(id, InstallState.Installed("/fake/models/$id", total))
    }

    override fun state(id: ArtifactId): Flow<InstallState> = states.map { it[id] ?: InstallState.NotInstalled }

    override suspend fun download(id: ArtifactId, wifiOnly: Boolean) {
        requests += id
        if (_states.value[id] is InstallState.Installed) return
        set(id, InstallState.Queued(wifiOnly))
        queue.send(id to wifiOnly)
    }

    override suspend fun pause(id: ArtifactId) {
        val running = lock.withLock { current?.takeIf { it.first == id }?.second }
        running?.cancel()
        running?.join()
        val st = _states.value[id]
        val total = catalog().artifact(id)?.second?.downloadBytes ?: 1L
        val done = (st as? InstallState.Downloading)?.bytesDone ?: (st as? InstallState.Paused)?.bytesDone ?: 0L
        if (st !is InstallState.Installed) set(id, InstallState.Paused(done, total, MurmurError.DownloadPaused))
    }

    override suspend fun delete(id: ArtifactId) {
        pause(id)
        set(id, InstallState.NotInstalled)
    }

    override suspend fun storage(): StorageInfo = StorageInfo(
        modelsBytes = _states.value.values.filterIsInstance<InstallState.Installed>().sumOf { it.bytesOnDisk },
        freeBytes = freeBytes,
    )
}
