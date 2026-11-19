package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.dictation.EnginePreparer
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.Stage
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.StorageInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * With a [scope], the states of artifacts that appear in or leave the catalog are rebuilt from the disk (custom
 * entries arrive after the store's first read, so a custom artifact's .part shows as Queued or Paused, not as
 * NotInstalled). [runJob] waits for [CatalogRepository.loaded] and never throws anything but cancellation.
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
    scope: CoroutineScope? = null,
    private val catalogWaitMs: Long = CATALOG_WAIT_MS,
) {
    val states = InstallStateStore(storage)
    private val downloader = FileDownloader(http, log)
    private val extractor = CommonsArchiveExtractor(log)
    private val running = ConcurrentHashMap<ArtifactId, Job>()
    /**
     * One writer per artifact directory: a job that is cancelled may still be inside a blocking connect when the next
     * job for the same artifact starts (delete + download again, a stop and a restart); without the lock both would
     * write the same .part.
     */
    private val locks = ConcurrentHashMap<ArtifactId, Mutex>()
    private fun lock(id: ArtifactId) = locks.computeIfAbsent(id) { Mutex() }

    /**
     * Guards the short "is the directory still there? then set the state" steps against [delete] (which deletes the
     * directory and sets NotInstalled under it): a late onStopJob can never resurrect a deleted artifact as Paused.
     * Never held across I/O of a running job.
     */
    private val stateGuards = ConcurrentHashMap<ArtifactId, Any>()
    private fun guard(id: ArtifactId) = stateGuards.computeIfAbsent(id) { Any() }

    init {
        refresh()
        scope?.launch {
            var known = artifactIds(catalog.catalog.value)
            catalog.catalog.collect { c ->
                val current = artifactIds(c)
                val changed = (current - known) + (known - current)
                known = current
                if (changed.isNotEmpty()) refresh(changed)
            }
        }
    }

    /** Re-reads the disk and the scheduler (process start). */
    fun refresh() = states.refresh(catalog.catalog.value, scheduler.pending(), running.keys.toSet())

    /** Re-reads [ids] only (they appeared in or left the catalog); other rows, e.g. a Failed one, keep their state. */
    private fun refresh(ids: Set<ArtifactId>) {
        val c = catalog.catalog.value
        val pending = scheduler.pending()
        for (id in ids) if (!running.containsKey(id)) synchronized(guard(id)) { states.set(id, states.fromDisk(c, id, pending[id])) }
    }

    private fun artifactIds(c: Catalog): Set<ArtifactId> = c.models.flatMap { m -> m.artifacts.map { it.id } }.toSet()

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

    /**
     * Body of one job run. Cancellation (stop, pause) propagates after [onStopped] set the state; every other failure
     * is a result. The job may start before the custom entries are read (a restart after process death), so it
     * waits for [CatalogRepository.loaded]: an artifact still unknown after that is gone from the catalog (Failed,
     * no retry); when the catalog does not load in time, the job asks to be retried
     * (Err([MurmurError.Timeout]) of [Stage.DOWNLOAD], state Queued).
     */
    suspend fun runJob(id: ArtifactId, wifiOnly: Boolean, onTransferred: (Long) -> Unit = {}): Outcome<InstallState.Installed> {
        val job = currentCoroutineContext()[Job]
        if (job != null) running[id] = job
        try {
            val c = withTimeoutOrNull(catalogWaitMs) { catalog.loaded() }
            if (c == null && catalog.catalog.value.artifact(id) == null) {
                log.event("job_catalog_not_loaded", "artifact" to id.value, "waitedMs" to catalogWaitMs)
                states.set(id, InstallState.Queued(wifiOnly))
                return Outcome.Err(MurmurError.Timeout(Stage.DOWNLOAD))
            }
            val effective = c ?: catalog.catalog.value
            return lock(id).withLock { install(effective, id, wifiOnly, onTransferred) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ArtifactInstaller maps its own failures; this is the last line before the job's process.
            log.event("job_exception", "artifact" to id.value, "err" to e.javaClass.simpleName)
            val error = MurmurError.Internal("job: ${e.javaClass.simpleName}")
            states.set(id, InstallState.Failed(error))
            return Outcome.Err(error)
        } finally {
            running.remove(id, job)
        }
    }

    private suspend fun install(c: Catalog, id: ArtifactId, wifiOnly: Boolean, onTransferred: (Long) -> Unit): Outcome<InstallState.Installed> {
        log.event("job_start", "artifact" to id.value, "partBytes" to c.artifact(id)?.second?.let { storage.bytesDone(it) })
        val installer = ArtifactInstaller(
            storage = storage, downloader = downloader, extractor = extractor, states = states, preparer = preparer,
            queue = { cpu ->
                log.event("queue_cpu_artifact", "artifact" to cpu.value, "after" to id.value)
                enqueue(cpu, wifiOnly)
            },
            freeBytes = freeBytes, now = now, log = log, onTransferred = onTransferred,
        )
        return installer.install(c, id)
    }

    /** The job was stopped by the system or the user; the .part files stay. */
    fun onStopped(id: ArtifactId, kind: StopKind, wifiOnly: Boolean) {
        val a = catalog.catalog.value.artifact(id)?.second ?: return
        synchronized(guard(id)) {
            if (!storage.dir(id).exists()) return   // deleted meanwhile
            if (storage.isInstalled(id) && storage.readMarker(id)?.prepared == true) return
            log.event("job_stopped", "artifact" to id.value, "kind" to kind.name, "partBytes" to storage.bytesDone(a))
            states.set(id, pausedState(a, kind, wifiOnly))
        }
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
        synchronized(guard(id)) {
            if (!storage.isInstalled(id)) states.set(id, pausedState(a, StopKind.USER, wifiOnly = true))
        }
    }

    /** Cancels any job, waits for it to let go of the files, deletes the directory (prefix snapshots included). */
    suspend fun delete(id: ArtifactId) {
        scheduler.cancel(id)
        running[id]?.let { it.cancel(CancellationException("deleted")); it.join() }
        lock(id).withLock {
            synchronized(guard(id)) {
                storage.delete(id)
                states.set(id, InstallState.NotInstalled)
            }
        }
        log.event("deleted", "artifact" to id.value)
    }

    /** Catalog display name of the artifact's model (notifications); the id when unknown. */
    fun displayName(id: ArtifactId): String = catalog.catalog.value.artifact(id)?.first?.displayName ?: id.value

    fun storageInfo(): StorageInfo = StorageInfo(modelsBytes = storage.totalBytes(), freeBytes = freeBytes())

    companion object {
        /** How long a job waits for the stored custom entries before it asks to be retried. */
        const val CATALOG_WAIT_MS = 10_000L
    }
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
