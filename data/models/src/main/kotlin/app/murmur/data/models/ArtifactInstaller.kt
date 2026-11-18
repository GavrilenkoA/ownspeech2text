package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.Catalog
import app.murmur.core.dictation.EnginePreparer
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState

/**
 * Everything one download job does for one artifact (ARCHITECTURE 8.5), without Android types so that it runs in JVM
 * tests: free-space check, every file through the [Downloader] (resume, verify, rename), Verifying, extraction of
 * archives (then the archive is deleted), the layout check, installed.json, Preparing through the [EnginePreparer]
 * (a report's needsCpuArtifact is queued), Installed. Re-running it after a process death continues where the disk
 * says it stopped: finished files are kept, parts resume, an extracted archive is not fetched again, an installed
 * but unprepared artifact is only prepared.
 *
 * Errors set the state themselves: LowStorage / NoNetwork -> Paused (the job retries), anything else -> Failed.
 * Cancellation (pause, onStopJob) propagates; the caller sets the paused state.
 */
class ArtifactInstaller(
    private val storage: ModelStorage,
    private val downloader: Downloader,
    private val extractor: ArchiveExtractor,
    private val states: InstallStateStore,
    private val preparer: () -> EnginePreparer?,
    private val queue: suspend (ArtifactId) -> Unit,
    private val freeBytes: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: DlLog = DlLog.NONE,
    /** Bytes received over the network in this run (JobService.updateTransferredNetworkBytes). */
    private val onTransferred: (Long) -> Unit = {},
) {
    suspend fun install(catalog: Catalog, id: ArtifactId): Outcome<InstallState.Installed> {
        val artifact = catalog.artifact(id)?.second
            ?: return fail(id, MurmurError.CatalogInvalid("unknown artifact"))
        val marker = storage.readMarker(id)
        if (marker == null) {
            val r = fetchAndUnpack(catalog, artifact)
            if (r is Outcome.Err) return r
        }
        val current = storage.readMarker(id) ?: return fail(id, MurmurError.StorageFailed("marker unreadable"))
        if (!current.prepared) prepare(artifact, current)
        val installed = InstallState.Installed(storage.dir(id).absolutePath, current.bytesOnDisk)
        states.set(id, installed)
        log.event("install_done", "artifact" to id.value, "bytes" to current.bytesOnDisk)
        return Outcome.Ok(installed)
    }

    private suspend fun fetchAndUnpack(catalog: Catalog, a: Artifact): Outcome<Unit> {
        val id = a.id
        val total = a.downloadBytes
        storage.dir(id).mkdirs()
        val done0 = storage.bytesDone(a)
        val needed = peakInstallBytes(a) - done0 + RESERVE_BYTES
        val free = freeBytes()
        if (free < needed) {
            log.event("install_low_storage", "artifact" to id.value, "needed" to needed, "free" to free)
            states.set(id, InstallState.Paused(done0, total, MurmurError.LowStorage))
            return Outcome.Err(MurmurError.LowStorage)
        }
        val meter = Meter(now)
        var before = 0L
        var transferred = 0L   // network bytes of this run, all files
        var lastEmit = 0L
        for (f in a.files) {
            if (storage.file(id, f.name).isFile || storage.extractedStamp(id, f.name).isFile) { before += f.bytes; continue }
            val base = before
            var fileStart = -1L   // the downloader reports the resume offset first
            var fileLatest = 0L
            val r = downloader.fetch(f.url, storage.part(id, f.name), f.bytes, f.sha256) { onFile ->
                val done = base + onFile
                if (fileStart < 0) fileStart = onFile
                fileLatest = onFile
                onTransferred(transferred + (onFile - fileStart))
                val t = now()
                meter.add(t, done)
                if (t - lastEmit >= EMIT_EVERY_MS || onFile == f.bytes) {
                    lastEmit = t
                    states.set(id, InstallState.Downloading(done, total, meter.bytesPerSec()))
                }
            }
            if (r is Outcome.Err) {
                val doneNow = storage.bytesDone(a)
                when (r.error) {
                    MurmurError.NoNetwork -> states.set(id, InstallState.Paused(doneNow, total, MurmurError.NoNetwork))
                    else -> states.set(id, InstallState.Failed(r.error))
                }
                log.event("install_file_failed", "artifact" to id.value, "file" to f.name, "code" to r.error.code)
                return Outcome.Err(r.error)
            }
            if (fileStart >= 0) transferred += fileLatest - fileStart
            before += f.bytes
        }
        states.set(id, InstallState.Verifying)
        for (f in a.files) {
            val stamped = storage.extractedStamp(id, f.name).isFile
            val file = storage.file(id, f.name)
            if (!stamped && (!file.isFile || file.length() != f.bytes)) {
                file.delete()
                return fail(id, MurmurError.ChecksumMismatch)
            }
        }
        val archives = a.files.filter { it.archive != null && !storage.extractedStamp(id, it.name).isFile }
        if (archives.isNotEmpty()) states.set(id, InstallState.Extracting)
        for (f in archives) {
            val archive = storage.file(id, f.name)
            when (val r = extractor.extract(archive, storage.dir(id), f.archive!!)) {
                is Outcome.Err -> { archive.delete(); return fail(id, r.error) }
                is Outcome.Ok -> {
                    ModelStorage.writeAtomically(storage.extractedStamp(id, f.name), f.sha256)
                    archive.delete()
                }
            }
        }
        val missing = storage.missingLayout(a)
        if (missing.isNotEmpty()) {
            log.event("install_layout_missing", "artifact" to id.value, "roles" to missing.joinToString(","))
            return fail(id, MurmurError.CatalogInvalid("layout missing: ${missing.joinToString(",")}"))
        }
        storage.writeMarker(
            InstalledMarker(
                artifactId = id.value,
                catalogVersion = catalog.catalogVersion,
                files = a.files.associate { it.name to it.sha256 },
                bytesOnDisk = storage.sizeOnDisk(id),
                installedAtMs = now(),
            ),
        )
        return Outcome.Ok(Unit)
    }

    private suspend fun prepare(a: Artifact, marker: InstalledMarker) {
        val p = preparer() ?: return
        states.set(a.id, InstallState.Preparing(0f))
        when (val r = p.prepareArtifact(a.id) { states.set(a.id, InstallState.Preparing(it.coerceIn(0f, 1f))) }) {
            is Outcome.Ok -> {
                val report = r.value
                log.event(
                    "prepare_done", "artifact" to a.id.value, "proven" to report.proven.joinToString(","),
                    "failed" to report.failed.entries.joinToString(",") { "${it.key}:${it.value.code}" },
                    "needsCpu" to report.needsCpuArtifact?.value,
                )
                report.needsCpuArtifact?.let { cpu -> if (!storage.isInstalled(cpu)) queue(cpu) }
                storage.writeMarker(marker.copy(prepared = true))
            }
            // The artifact stays installed; unit health learns from the first real load (ARCHITECTURE 4.4).
            is Outcome.Err -> log.event("prepare_failed", "artifact" to a.id.value, "code" to r.error.code)
        }
    }

    private fun fail(id: ArtifactId, error: MurmurError): Outcome.Err {
        log.event("install_failed", "artifact" to id.value, "code" to error.code)
        states.set(id, InstallState.Failed(error))
        return Outcome.Err(error)
    }

    /** Download speed over the last few seconds. */
    private class Meter(private val now: () -> Long) {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        fun add(t: Long, bytes: Long) {
            samples.addLast(t to bytes)
            while (samples.size > 2 && t - samples.first().first > WINDOW_MS) samples.removeFirst()
        }
        fun bytesPerSec(): Long {
            if (samples.size < 2) return 0
            val (t0, b0) = samples.first()
            val (t1, b1) = samples.last()
            return if (t1 > t0) (b1 - b0) * 1000 / (t1 - t0) else 0
        }
    }

    companion object {
        /** Free space kept on top of the peak install bytes (ARCHITECTURE 8.5). */
        const val RESERVE_BYTES = 200L * 1024 * 1024
        private const val EMIT_EVERY_MS = 250L
        private const val WINDOW_MS = 3_000L
    }
}
