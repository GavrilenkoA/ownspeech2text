package app.murmur.data.models

import app.murmur.core.catalog.ArchiveType
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.Origin
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ModelId
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState
import app.murmur.testing.FakeCatalogRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Disk failures are results, never exceptions out of the job (it shares :main with dictation); a full disk is
 * Paused(LowStorage); a process death between the archive's stamp and its delete leaves no archive behind; the
 * network-bytes report is throttled; a job that starts before the custom entries are read still finds its artifact.
 */
class DownloadFailuresTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val net = FakeTransport()
    private val storage = ModelStorage(tempDir("models"))
    private val preparer = RecordingPreparer().also { it.storage = storage }
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val model = bytesOf(300_000, 1)
    private val tokens = "a 0\nb 1\n".encodeToByteArray()
    private val fModel = hfSpec("model.onnx", model)
    private val fTokens = hfSpec("tokens.txt", tokens)
    private val plain = testArtifact("plain@onnx", fModel, fTokens, layout = mapOf("model" to "model.onnx", "tokens" to "tokens.txt"))

    private val inner = bytesOf(120_000, 7)
    private val tarBytes = tar("pkg/encoder.bin" to inner, "pkg/tokens.txt" to tokens)
    private val fTar = hfSpec("pkg.tar.bz2", tarBytes, ArchiveType.TAR_BZ2)
    private val archived = testArtifact("archived@qnn", fTar, layout = mapOf("dir" to "pkg", "encoder" to "encoder.bin", "tokens" to "tokens.txt"))

    private val big = bytesOf(3_000_000, 5)
    private val fBig = hfSpec("big.onnx", big)
    private val bigArtifact = testArtifact("big@onnx", fBig)

    private val catalog = FakeCatalogRepository(catalogWith(plain, archived, bigArtifact))

    init {
        net.files[fModel.url] = model
        net.files[fTokens.url] = tokens
        net.files[fTar.url] = tarBytes
        net.files[fBig.url] = big
    }

    @After
    fun tearDown() {
        scope.cancel()
        storage.root.walkTopDown().forEach { it.setReadable(true); it.setWritable(true) }
    }

    private fun downloads(free: () -> Long = { 50L shl 30 }, now: () -> Long = System::currentTimeMillis) =
        ModelDownloads(storage, catalog, scheduler, net, { preparer }, free, now = now)

    private fun tar(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { t ->
            for ((n, d) in files) { t.putArchiveEntry(TarArchiveEntry(n).apply { size = d.size.toLong() }); t.write(d); t.closeArchiveEntry() }
        }
        return out.toByteArray()
    }

    @Test
    fun aFailedInstalledJsonWriteIsAStorageFailureNotAnException() = runBlocking {
        // installed.json cannot be replaced: a non-empty directory sits at its path.
        File(storage.marker(plain.id), "blocker").apply { parentFile!!.mkdirs(); writeText("x") }
        val d = downloads()
        val r = d.runJob(plain.id, wifiOnly = true)
        val err = (r as Outcome.Err).error
        assertTrue("got $err", err is MurmurError.StorageFailed)
        assertEquals(InstallState.Failed(err), d.states.get(plain.id))
    }

    @Test
    fun anUnreadablePartIsAStorageFailureNotAnException() = runBlocking {
        val part = storage.part(plain.id, "model.onnx").apply { parentFile!!.mkdirs(); writeBytes(model.copyOf(1000)) }
        part.setReadable(false)
        assumeTrue("needs a user that file permissions apply to", !part.canRead())
        val d = downloads()
        val r = d.runJob(plain.id, wifiOnly = true)
        val err = (r as Outcome.Err).error
        assertTrue("got $err", err is MurmurError.StorageFailed)
        assertEquals(InstallState.Failed(err), d.states.get(plain.id))
        assertTrue("the part is read before any request", net.requests.isEmpty())
    }

    @Test
    fun aDiskThatFillsUpDuringTheTransferIsPausedLowStorage() = runBlocking {
        // The part is /dev/full: every write fails with ENOSPC, exactly like a full data partition.
        val devFull = Paths.get("/dev/full")
        assumeTrue("needs /dev/full", Files.exists(devFull))
        val part = storage.part(plain.id, "model.onnx").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(part.toPath(), devFull)
        val d = downloads()
        assertEquals(Outcome.Err(MurmurError.LowStorage), d.runJob(plain.id, wifiOnly = true))
        val s = d.states.get(plain.id) as InstallState.Paused
        assertEquals(MurmurError.LowStorage, s.reason)
        assertEquals(plain.downloadBytes, s.bytesTotal)
    }

    @Test
    fun aWriteErrorWhileFreeSpaceIsUnderThePrecheckRuleIsLowStorageOtherwiseFailed() = runBlocking {
        // The artifact directory refuses writes (not ENOSPC): the free space decides between the two outcomes.
        val dir = storage.dir(plain.id).apply { mkdirs() }
        dir.setWritable(false)
        assumeTrue("needs a user that file permissions apply to", !File(dir, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })
        var calls = 0
        val nearlyFull = downloads(free = { if (calls++ == 0) 50L shl 30 else 10L shl 20 })
        assertEquals(Outcome.Err(MurmurError.LowStorage), nearlyFull.runJob(plain.id, wifiOnly = true))
        assertEquals(MurmurError.LowStorage, (nearlyFull.states.get(plain.id) as InstallState.Paused).reason)

        val roomy = downloads()
        val err = (roomy.runJob(plain.id, wifiOnly = true) as Outcome.Err).error
        assertTrue("got $err", err is MurmurError.StorageFailed)
        assertEquals(InstallState.Failed(err), roomy.states.get(plain.id))
    }

    @Test
    fun anArchiveLeftBetweenItsStampAndItsDeleteIsRemovedOnTheNextRun() = runBlocking {
        // The process died after the stamp was written and before the archive was deleted.
        val dir = storage.dir(archived.id)
        File(dir, "pkg/encoder.bin").apply { parentFile!!.mkdirs(); writeBytes(inner) }
        File(dir, "pkg/tokens.txt").writeBytes(tokens)
        storage.file(archived.id, fTar.name).writeBytes(tarBytes)
        ModelStorage.writeAtomically(storage.extractedStamp(archived.id, fTar.name), fTar.sha256)
        val d = downloads()
        assertTrue(d.runJob(archived.id, wifiOnly = true) is Outcome.Ok)
        assertFalse("the archive is gone", storage.file(archived.id, fTar.name).exists())
        assertTrue("nothing was fetched again", net.requests.isEmpty())
        val marker = storage.readMarker(archived.id)!!
        val stamp = storage.extractedStamp(archived.id, fTar.name).length()
        assertEquals("bytesOnDisk = the extracted files and the stamp, no archive", inner.size + tokens.size + stamp, marker.bytesOnDisk)
    }

    @Test
    fun transferredNetworkBytesAreReportedAboutOnceASecondNotPerBuffer() = runBlocking {
        var t = 10_000L
        val reports = mutableListOf<Long>()
        val d = downloads(now = { t++ })   // the clock moves 1 ms per reading: the whole file takes well under 1 s
        assertTrue(d.runJob(bigArtifact.id, wifiOnly = true) { reports += it } is Outcome.Ok)
        assertTrue("reports=$reports (a 3 MB file is 12 buffers)", reports.size <= 2)
        assertEquals(big.size.toLong(), reports.last())
    }

    // ---- custom entries at a cold start ----

    private val customBytes = bytesOf(200_000, 11)
    private val customFile = hfSpec("custom_vad.onnx", customBytes)
    private val customEntry: ModelEntry = CatalogParser.bundled().model(ModelId("silero-vad-v5"))!!.let { m ->
        m.copy(
            id = ModelId("custom-test-vad"), origin = Origin.CUSTOM,
            artifacts = listOf(m.artifacts.single().copy(id = ArtifactId("custom-test-vad@onnx"), files = listOf(customFile), layout = mapOf("model" to customFile.name))),
        )
    }
    private val customId = customEntry.artifacts.single().id

    /** Room's first read arrives after [ms]: a job restarted after process death runs before it. */
    private fun lateStore(ms: Long) = object : CustomModelStore {
        override val entries: Flow<List<String>> = flow {
            delay(ms)
            emit(listOf(CatalogParser.json.encodeToString(ModelEntry.serializer(), customEntry)))
        }
        override suspend fun put(id: String, json: String, addedMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private fun halfPart() {
        storage.part(customId, customFile.name).apply { parentFile!!.mkdirs(); writeBytes(customBytes.copyOf(100_000)) }
        net.files[customFile.url] = customBytes
        scheduler.scheduled[customId] = true
    }

    @Test
    fun aCustomArtifactJobThatStartsBeforeTheStoreIsReadResumesItsPart() = runBlocking {
        halfPart()
        val repo = DefaultCatalogRepository(CatalogParser.bundled(), lateStore(300), scope)
        val d = ModelDownloads(storage, repo, scheduler, net, { preparer }, { 50L shl 30 })
        val r = d.runJob(customId, wifiOnly = true)
        assertTrue("got $r", r is Outcome.Ok)
        assertEquals("bytes=100000-", net.requests.single().headers["Range"])
        assertEquals(sha256(customBytes), sha256Hex(storage.file(customId, customFile.name)))
    }

    @Test
    fun aCustomArtifactsPartShowsQueuedOnceTheStoreIsRead() = runBlocking {
        halfPart()
        val repo = DefaultCatalogRepository(CatalogParser.bundled(), lateStore(200), scope)
        val d = ModelDownloads(storage, repo, scheduler, net, { preparer }, { 50L shl 30 }, scope = scope)
        assertEquals("before the read the entry is unknown", InstallState.NotInstalled, d.states.get(customId))
        val s = withTimeout(5_000) { d.states.states.first { it[customId] != null } }
        assertEquals(InstallState.Queued(true), s[customId])
        // Without a pending job the same part is a paused download.
        scheduler.scheduled.clear()
        val d2 = ModelDownloads(storage, DefaultCatalogRepository(CatalogParser.bundled(), lateStore(100), scope), scheduler, net, { preparer }, { 50L shl 30 }, scope = scope)
        val p = withTimeout(5_000) { d2.states.states.first { it[customId] != null } }[customId] as InstallState.Paused
        assertEquals(100_000L to MurmurError.DownloadPaused, p.bytesDone to p.reason)
    }

    @Test
    fun aJobWhoseCatalogNeverLoadsAsksForARetryInsteadOfFailing() = runBlocking {
        halfPart()
        val never = object : CustomModelStore {
            override val entries: Flow<List<String>> = flow { delay(Long.MAX_VALUE) }
            override suspend fun put(id: String, json: String, addedMs: Long) = Unit
            override suspend fun delete(id: String) = Unit
        }
        val d = ModelDownloads(storage, DefaultCatalogRepository(CatalogParser.bundled(), never, scope), scheduler, net, { preparer }, { 50L shl 30 }, catalogWaitMs = 200)
        val r = d.runJob(customId, wifiOnly = true)
        assertEquals(Outcome.Err(MurmurError.Timeout(app.murmur.core.model.Stage.DOWNLOAD)), r)
        assertEquals(InstallState.Queued(true), d.states.get(customId))
        assertTrue("the part is kept", storage.part(customId, customFile.name).length() == 100_000L)
    }

    @Test
    fun aStoreThatFailsToLoadLeavesTheBundledCatalog() = runBlocking {
        val broken = object : CustomModelStore {
            override val entries: Flow<List<String>> = flow { throw IllegalStateException("database closed") }
            override suspend fun put(id: String, json: String, addedMs: Long) = Unit
            override suspend fun delete(id: String) = Unit
        }
        val repo = DefaultCatalogRepository(CatalogParser.bundled(), broken, scope)
        assertEquals(CatalogParser.bundled(), withTimeout(5_000) { repo.loaded() })
    }
}
