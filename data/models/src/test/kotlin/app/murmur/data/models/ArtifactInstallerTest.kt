package app.murmur.data.models

import app.murmur.core.catalog.ArchiveType
import app.murmur.core.dictation.PrepareReport
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState
import app.murmur.testing.FakeCatalogRepository
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactInstallerTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val net = FakeTransport()
    private val storage = ModelStorage(tempDir("models"))
    private val preparer = RecordingPreparer().also { it.storage = storage }
    private val scheduler = FakeScheduler()
    private var free = 50L shl 30

    private val model = bytesOf(300_000, 1)
    private val tokens = "a 0\nb 1\n".encodeToByteArray()
    private val fModel = hfSpec("model.onnx", model)
    private val fTokens = hfSpec("tokens.txt", tokens)
    private val plain = testArtifact("plain@onnx", fModel, fTokens, layout = mapOf("model" to "model.onnx", "tokens" to "tokens.txt"))

    private val inner = bytesOf(120_000, 7)
    private val tarBytes = tar("pkg/encoder.bin" to inner, "pkg/tokens.txt" to tokens)
    private val fTar = hfSpec("pkg.tar.bz2", tarBytes, ArchiveType.TAR_BZ2)
    private val archived = testArtifact("archived@qnn", fTar, layout = mapOf("dir" to "pkg", "encoder" to "encoder.bin", "tokens" to "tokens.txt"))
    private val cpu = testArtifact("cpu@onnx", hfSpec("cpu.onnx", bytesOf(1000, 3)))

    private val catalog = FakeCatalogRepository(catalogWith(plain, archived, cpu))
    private val downloads = ModelDownloads(storage, catalog, scheduler, net, { preparer }, { free })

    init {
        for (f in listOf(fModel, fTokens, fTar, cpu.files.single())) net.files[f.url] = when (f) {
            fModel -> model; fTokens -> tokens; fTar -> tarBytes; else -> bytesOf(1000, 3)
        }
    }

    private fun tar(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { t ->
            for ((n, d) in files) { t.putArchiveEntry(TarArchiveEntry(n).apply { size = d.size.toLong() }); t.write(d); t.closeArchiveEntry() }
        }
        return out.toByteArray()
    }

    /** Runs the job for [id] and returns the sequence of state kinds it emitted. */
    private fun runJob(id: ArtifactId): Pair<Outcome<InstallState.Installed>, List<String>> = runBlocking {
        downloads.states.set(DONE, InstallState.NotInstalled)   // so that the end marker below is a change again
        val seen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            downloads.states.transitions.takeWhile { it.first != DONE }.toList().filter { it.first == id }.map { it.second::class.simpleName!! }
        }
        val r = downloads.runJob(id, wifiOnly = true)
        downloads.states.set(DONE, InstallState.Verifying)
        r to seen.await().fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
    }

    @Test
    fun queuedDownloadingVerifyingPreparingInstalledAndMarkerBeforePreparing() {
        assertEquals(Outcome.Ok(Unit), downloads.enqueue(plain.id, wifiOnly = true))
        assertEquals(InstallState.Queued(true), downloads.states.get(plain.id))
        assertEquals(true, scheduler.scheduled[plain.id])
        val (r, kinds) = runJob(plain.id)
        assertTrue(r is Outcome.Ok)
        assertEquals(listOf("Downloading", "Verifying", "Preparing", "Installed"), kinds)
        assertEquals(listOf(plain.id), preparer.prepared)
        assertEquals("installed.json is written before Preparing", true, preparer.markerPresentAtPrepare)
        val marker = storage.readMarker(plain.id)!!
        assertTrue(marker.prepared)
        assertEquals(mapOf("model.onnx" to fModel.sha256, "tokens.txt" to fTokens.sha256), marker.files)
        assertEquals(InstallState.Installed(storage.dir(plain.id).absolutePath, marker.bytesOnDisk), downloads.states.get(plain.id))
    }

    @Test
    fun archiveIsExtractedLayoutResolvedThroughDirAndArchiveDeleted() {
        val (r, kinds) = runJob(archived.id)
        assertTrue(r.toString(), r is Outcome.Ok)
        assertEquals(listOf("Downloading", "Verifying", "Extracting", "Preparing", "Installed"), kinds)
        assertFalse(storage.file(archived.id, fTar.name).exists())
        assertTrue(storage.extractedStamp(archived.id, fTar.name).isFile)
        assertTrue(File(storage.modelDir(archived), "encoder.bin").readBytes().contentEquals(inner))
        assertEquals(emptyList<String>(), storage.missingLayout(archived))
        // Re-running after the archive is gone does not fetch it again.
        net.requests.clear()
        storage.marker(archived.id).delete()
        assertTrue(runJob(archived.id).first is Outcome.Ok)
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun layoutPathMissingFromTheArchiveFails() {
        val wrong = archived.copy(id = ArtifactId("wrong@qnn"), layout = mapOf("dir" to "pkg", "joiner" to "joiner.bin"))
        val c = FakeCatalogRepository(catalogWith(wrong))
        val d = ModelDownloads(storage, c, scheduler, net, { preparer }, { free })
        val r = runBlocking { d.runJob(wrong.id, true) }
        assertEquals(Outcome.Err(MurmurError.CatalogInvalid("layout missing: joiner")), r)
        assertFalse(storage.isInstalled(wrong.id))
        assertTrue(d.states.get(wrong.id) is InstallState.Failed)
    }

    @Test
    fun resumeAfterProcessDeathContinuesFromTheDisk() {
        // A previous process finished tokens.txt and half of model.onnx, then died.
        storage.dir(plain.id).mkdirs()
        storage.file(plain.id, "tokens.txt").writeBytes(tokens)
        storage.part(plain.id, "model.onnx").writeBytes(model.copyOfRange(0, 150_000))
        val fresh = ModelDownloads(storage, catalog, scheduler, net, { preparer }, { free })
        assertEquals(InstallState.Paused(150_000L + tokens.size, plain.downloadBytes, MurmurError.DownloadPaused), fresh.states.get(plain.id))
        scheduler.scheduled[plain.id] = true
        fresh.refresh()
        assertEquals(InstallState.Queued(true), fresh.states.get(plain.id))
        val r = runBlocking { fresh.runJob(plain.id, true) }
        assertTrue(r is Outcome.Ok)
        assertEquals(listOf("GET ${fModel.url} bytes=150000-"), net.requests.map { "${it.method} ${it.url} ${it.headers["Range"]}" })
    }

    @Test
    fun lowStorageIsAPausedState() {
        free = plain.downloadBytes + ArtifactInstaller.RESERVE_BYTES - 1
        assertEquals(Outcome.Err(MurmurError.LowStorage), downloads.enqueue(plain.id, true))
        assertEquals(InstallState.Paused(0, plain.downloadBytes, MurmurError.LowStorage), downloads.states.get(plain.id))
        assertTrue(scheduler.scheduled.isEmpty())
        // Archives need twice their size during install.
        free = fTar.bytes + ArtifactInstaller.RESERVE_BYTES + 10
        assertEquals(Outcome.Err(MurmurError.LowStorage), downloads.enqueue(archived.id, true))
    }

    @Test
    fun corruptedDownloadFailsWithChecksumMismatchAndDeletesThePart() {
        net.files[fModel.url] = model.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        val r = runBlocking { downloads.runJob(plain.id, true) }
        assertEquals(Outcome.Err(MurmurError.ChecksumMismatch), r)
        assertEquals(InstallState.Failed(MurmurError.ChecksumMismatch), downloads.states.get(plain.id))
        assertFalse(storage.part(plain.id, "model.onnx").exists())
        assertFalse(storage.isInstalled(plain.id))
    }

    @Test
    fun preparerReportWithNeedsCpuArtifactQueuesIt() {
        preparer.report = { Outcome.Ok(PrepareReport(it, emptySet(), mapOf(ComputeUnit.NPU to MurmurError.EngineLoadFailed(app.murmur.core.model.ModelKind.ASR, "qnn")), cpu.id)) }
        assertTrue(runJob(plain.id).first is Outcome.Ok)
        assertEquals(InstallState.Queued(true), downloads.states.get(cpu.id))
        assertEquals(true, scheduler.scheduled[cpu.id])
    }

    @Test
    fun unpreparedInstallIsPreparedOnRerunWithoutDownloading() {
        preparer.report = { Outcome.Err(MurmurError.ProcessDied(app.murmur.core.model.InferProcess.ASR)) }
        assertTrue(runJob(plain.id).first is Outcome.Ok)
        assertFalse(storage.readMarker(plain.id)!!.prepared)
        preparer.report = { Outcome.Ok(PrepareReport(it, setOf(ComputeUnit.CPU), emptyMap(), null)) }
        net.requests.clear()
        val (r, kinds) = runJob(plain.id)
        assertTrue(r is Outcome.Ok)
        assertEquals(listOf("Preparing", "Installed"), kinds)
        assertTrue(net.requests.isEmpty())
        assertTrue(storage.readMarker(plain.id)!!.prepared)
        // Installed and prepared: enqueue is a no-op.
        assertEquals(Outcome.Ok(Unit), downloads.enqueue(plain.id, true))
        assertTrue(plain.id !in scheduler.scheduled)
    }

    @Test
    fun pauseKeepsPartsAndDeleteRemovesEverything() = runBlocking {
        net.dropAfter = 100_000
        assertEquals(Outcome.Err(MurmurError.NoNetwork), downloads.runJob(plain.id, true))
        val paused = downloads.states.get(plain.id) as InstallState.Paused
        assertEquals(MurmurError.NoNetwork, paused.reason)
        assertTrue(paused.bytesDone >= 100_000)
        downloads.pause(plain.id)
        assertEquals(MurmurError.DownloadPaused, (downloads.states.get(plain.id) as InstallState.Paused).reason)
        assertTrue(storage.part(plain.id, "model.onnx").length() > 0)
        downloads.delete(plain.id)
        assertFalse(storage.dir(plain.id).exists())
        assertEquals(InstallState.NotInstalled, downloads.states.get(plain.id))
        assertTrue(plain.id in scheduler.cancelled)
    }

    @Test
    fun repositoryStateFlowAndStorage() = runBlocking {
        val repo = DefaultInstalledModelsRepository(downloads)
        val states = mutableListOf<InstallState>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { repo.state(plain.id).collect { states += it } }
        repo.download(plain.id, wifiOnly = false)
        assertEquals(false, scheduler.scheduled[plain.id])
        downloads.runJob(plain.id, false)
        kotlinx.coroutines.delay(100)
        job.cancel()
        assertEquals(InstallState.NotInstalled, states.first())
        assertTrue(states.last() is InstallState.Installed)
        val info = repo.storage()
        assertTrue(info.modelsBytes >= plain.downloadBytes)
        assertEquals(free, info.freeBytes)
        assertNotNull(repo.states.value[plain.id])
    }

    @Test
    fun twoJobsForOneArtifactNeverWriteThePartAtTheSameTime() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger()
        var maxActive = 0
        val counting = object : HttpTransport by net {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                val r = net.get(url, headers)
                synchronized(this) { maxActive = maxOf(maxActive, active.incrementAndGet()) }
                Thread.sleep(50)
                return HttpResponse(r.code, r.finalUrl, mapOf("content-range" to r.header("Content-Range").orEmpty()), r.hops, { r.body }, { active.decrementAndGet() })
            }
        }
        val d = ModelDownloads(storage, catalog, scheduler, counting, { preparer }, { free })
        val a = async(Dispatchers.IO) { d.runJob(plain.id, true) }
        val b = async(Dispatchers.IO) { d.runJob(plain.id, true) }
        assertTrue(a.await() is Outcome.Ok && b.await() is Outcome.Ok)
        assertEquals(1, maxActive)
        assertEquals(sha256(model), sha256Hex(storage.file(plain.id, "model.onnx")))
        assertEquals("the second job found the artifact installed", 2, net.requests.size)
    }

    private companion object {
        val DONE = ArtifactId("__done__")
    }
}
