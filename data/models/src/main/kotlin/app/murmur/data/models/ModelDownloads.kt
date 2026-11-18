package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.dictation.EnginePreparer
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.StorageInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Schedules one download job per artifact (JobScheduler UIDT on Android, ModelDownloadJob). */
interface DownloadScheduler {
    /** false = the system refused the job. */
    fun schedule(id: ArtifactId, wifiOnly: Boolean, estimatedBytes: Long): Boolean
    fun cancel(id: ArtifactId)
    /** Artifact -> wifiOnly of every job that is scheduled or running. */
    fun pending(): Map<ArtifactId, Boolean>
}

/** Why a running job stopped before it finished (JobParameters.getStopReason, mapped). */
enum class StopKind {
    /** Pause in the hub (job cancelled by the app) or Stop in the task manager. */
    USER,
    /** Wi-Fi (or any network) lost: the job reruns when it is back. */
    NETWORK,
    /** Any other system stop (quota, device state); the job is rescheduled. */
    SYSTEM,
}

/**
 * The download machinery of the :main process, shared by [DefaultInstalledModelsRepository] (UI side) and
 * [ModelDownloadJob] (the job, found through [ModelJobHost]). One instance per process (AppContainer).
 */
class ModelDownloads(
    val storage: ModelStorage,
    private val catalog: CatalogRepository,
    private val scheduler: DownloadScheduler,
    http: HttpTransport,
    private val preparer: () -> EnginePreparer?,
    private val freeBytes: () -> Long,
    private val log: DlLog = DlLog.NONE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val states = InstallStateStore(storage)
    private val downloader = FileDownloader(http, log)
    private val extractor = CommonsArchiveExtractor(log)
    private val running = ConcurrentHashMap<ArtifactId, Job>()

    init {
        refresh()
    }

    /** Re-reads the disk and the scheduler (process start, or after the catalog changed). */
    fun refresh() = states.refresh(catalog.catalog.value, scheduler.pending(), running.keys.toSet())

    /**
     * Queues [id]: free-space pre-check (peak install bytes + 200 MB, else Paused(LowStorage)), state Queued, one job.
     * Already installed and prepared: no-op.
     */
    fun enqueue(id: ArtifactId, wifiOnly: Boolean): Outcome<Unit> {
        val a = catalog.catalog.value.artifact(id)?.second ?: return Outcome.Err(MurmurError.CatalogInvalid("unknown artifact"))
        storage.readMarker(id)?.let { m ->
            if (m.prepared) {
                states.set(id, InstallState.Installed(storage.dir(id).absolutePath, m.bytesOnDisk))
                return Outcome.Ok(Unit)
            }
        }
        if (running.containsKey(id)) return Outcome.Ok(Unit)
        val done = storage.bytesDone(a)
        val needed = peakInstallBytes(a) - done + ArtifactInstaller.RESERVE_BYTES
        if (freeBytes() < needed) {
            log.event("enqueue_low_storage", "artifact" to id.value, "needed" to needed)
            states.set(id, InstallState.Paused(done, a.downloadBytes, MurmurError.LowStorage))
            return Outcome.Err(MurmurError.LowStorage)
        }
        states.set(id, InstallState.Queued(wifiOnly))
        if (!scheduler.schedule(id, wifiOnly, (a.downloadBytes - done).coerceAtLeast(0))) {
            val e = MurmurError.DownloadFailed(null, "job refused")
            states.set(id, InstallState.Failed(e))
            return Outcome.Err(e)
        }
        log.event("enqueue", "artifact" to id.value, "wifiOnly" to wifiOnly, "bytes" to a.downloadBytes - done)
        return Outcome.Ok(Unit)
    }

    /** Body of one job run. Cancellation (stop, pause) propagates after [onStopped] set the state. */
    suspend fun runJob(id: ArtifactId, wifiOnly: Boolean, onTransferred: (Long) -> Unit = {}): Outcome<InstallState.Installed> {
        val job = currentCoroutineContext()[Job]
        if (job != null) running[id] = job
        log.event("job_start", "artifact" to id.value, "partBytes" to catalog.catalog.value.artifact(id)?.second?.let { storage.bytesDone(it) })
        try {
            val installer = ArtifactInstaller(
                storage = storage, downloader = downloader, extractor = extractor, states = states, preparer = preparer,
                queue = { cpu ->
                    log.event("queue_cpu_artifact", "artifact" to cpu.value, "after" to id.value)
                    enqueue(cpu, wifiOnly)
                },
                freeBytes = freeBytes, now = now, log = log, onTransferred = onTransferred,
            )
            return installer.install(catalog.catalog.value, id)
        } finally {
            running.remove(id, job)
        }
    }

    /** The job was stopped by the system or the user; the .part files stay. */
    fun onStopped(id: ArtifactId, kind: StopKind, wifiOnly: Boolean) {
        val a = catalog.catalog.value.artifact(id)?.second ?: return
        if (!storage.dir(id).exists()) return   // deleted meanwhile
        if (storage.isInstalled(id) && storage.readMarker(id)?.prepared == true) return
        log.event("job_stopped", "artifact" to id.value, "kind" to kind.name, "partBytes" to storage.bytesDone(a))
        states.set(id, pausedState(a, kind, wifiOnly))
    }

    private fun pausedState(a: Artifact, kind: StopKind, wifiOnly: Boolean): InstallState {
        val done = storage.bytesDone(a)
        return when (kind) {
            StopKind.USER -> InstallState.Paused(done, a.downloadBytes, MurmurError.DownloadPaused)
            StopKind.NETWORK -> InstallState.Paused(done, a.downloadBytes, MurmurError.NoNetwork)
            StopKind.SYSTEM -> InstallState.Queued(wifiOnly)
        }
    }

    /** User pause: the job is cancelled, the parts stay, the row shows Resume. */
    suspend fun pause(id: ArtifactId) {
        scheduler.cancel(id)
        running[id]?.let { it.cancel(CancellationException("paused")); it.join() }
        val a = catalog.catalog.value.artifact(id)?.second ?: return
        if (!storage.isInstalled(id)) states.set(id, pausedState(a, StopKind.USER, wifiOnly = true))
    }

    /** Cancels any job, waits for it to let go of the files, deletes the directory (prefix snapshots included). */
    suspend fun delete(id: ArtifactId) {
        scheduler.cancel(id)
        running[id]?.let { it.cancel(CancellationException("deleted")); it.join() }
        storage.delete(id)
        log.event("deleted", "artifact" to id.value)
        states.set(id, InstallState.NotInstalled)
    }

    /** Catalog display name of the artifact's model (notifications); the id when unknown. */
    fun displayName(id: ArtifactId): String = catalog.catalog.value.artifact(id)?.first?.displayName ?: id.value

    fun storageInfo(): StorageInfo = StorageInfo(modelsBytes = storage.totalBytes(), freeBytes = freeBytes())
}

/** [InstalledModelsRepository] over [ModelDownloads]: states from disk + running jobs (ARCHITECTURE 8.5). */
class DefaultInstalledModelsRepository(private val downloads: ModelDownloads) : InstalledModelsRepository {
    override val states: StateFlow<Map<ArtifactId, InstallState>> get() = downloads.states.states
    override fun state(id: ArtifactId): Flow<InstallState> =
        downloads.states.states.map { it[id] ?: InstallState.NotInstalled }.distinctUntilChanged()
    override suspend fun download(id: ArtifactId, wifiOnly: Boolean) { downloads.enqueue(id, wifiOnly) }
    override suspend fun pause(id: ArtifactId) = downloads.pause(id)
    override suspend fun delete(id: ArtifactId) = downloads.delete(id)
    override suspend fun storage(): StorageInfo = downloads.storageInfo()
}
