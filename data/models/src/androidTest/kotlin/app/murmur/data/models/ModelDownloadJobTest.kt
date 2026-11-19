package app.murmur.data.models

import android.app.Activity
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState
import java.io.RandomAccessFile
import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real UIDT job on the emulator with the network on (WP-13 acceptance): GitHub and Hugging Face downloads through
 * the redirect allowlist, Range resume, sha256, the state flow with a fake preparer, needsCpuArtifact, and a corrupted
 * part. The process kill + resume is KillResumeScenario (driven by data/models/tools/kill_resume.sh).
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadJobTest {
    private val app = TestModelsApp.app
    private val downloads = app.modelDownloads
    private val catalog = CatalogParser.bundled()
    private lateinit var activity: Activity

    private val vad = ArtifactId("silero-vad-v5@onnx")
    private val det = ArtifactId("pp-ocrv6-tiny-det@onnx")
    private val rec = ArtifactId("pp-ocrv5-cyrillic-mobile-rec@onnx")

    @Before
    fun setUp(): Unit = runBlocking {
        activity = VisibleActivity.launch()
        for (id in listOf(vad, det, rec)) downloads.delete(id)
        app.preparer.needsCpu.clear()
    }

    @After
    fun tearDown() = activity.finish()

    /** Collects every state of [ids] while [block] runs. */
    private fun recording(vararg ids: String, block: suspend () -> Unit): Map<String, List<String>> = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Pair<ArtifactId, InstallState>>())
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { downloads.states.transitions.collect { seen += it } }
        block()
        // The transitions collector runs on another thread: let it see the final states before it stops.
        withTimeoutOrNull(5_000) {
            while (ids.any { id -> seen.toList().none { it.first.value == id && (it.second is InstallState.Installed || it.second is InstallState.Failed) } }) delay(20)
        }
        job.cancel()
        ids.associateWith { id ->
            seen.filter { it.first.value == id }.map { it.second::class.simpleName!! }.fold(mutableListOf<String>()) { a, s -> if (a.lastOrNull() != s) a += s; a }
        }
    }

    private fun assertInstalledAndVerified(id: ArtifactId) {
        val a = catalog.artifact(id)!!.second
        val marker = downloads.storage.readMarker(id)!!
        assertTrue(marker.prepared)
        for (f in a.files) {
            val file = downloads.storage.file(id, f.name)
            assertEquals(f.bytes, file.length())
            assertEquals("${f.name} sha256", f.sha256, sha256Hex(file))
            assertFalse(downloads.storage.part(id, f.name).exists())
        }
    }

    @Test
    fun vadFromGithubAndOcrDetFromHfGoQueuedDownloadingVerifyingPreparingInstalled() {
        val flows = recording(vad.value, det.value) {
            assertEquals(Outcome.Ok(Unit), downloads.enqueue(vad, wifiOnly = true))
            assertEquals(Outcome.Ok(Unit), downloads.enqueue(det, wifiOnly = true))
            downloads.awaitState(vad, 180_000) { it is InstallState.Installed }
            downloads.awaitState(det, 180_000) { it is InstallState.Installed }
        }
        Log.i("MurmurModelsTest", "flows=$flows")
        for (id in listOf(vad, det)) {
            assertEquals(id.value, listOf("Queued", "Downloading", "Verifying", "Preparing", "Installed"), flows.getValue(id.value))
            assertInstalledAndVerified(id)
        }
        assertTrue(app.preparer.prepared.containsAll(listOf(vad, det)))
        // The redirect hops really left huggingface.co / github.com for their CDNs (allowlisted suffixes).
        val hosts = app.eventLog.readLines().filter { " dl_http " in it }.map { it.substringAfter("host=").substringBefore(' ') }
        Log.i("MurmurModelsTest", "final hosts=$hosts")
        assertTrue(hosts.toString(), hosts.isNotEmpty() && hosts.all { CatalogValidator.isAllowedHost(it) })
    }

    @Test
    fun preparerReportWithNeedsCpuArtifactQueuesIt() {
        // The mechanism is the same for every artifact; a small OCR pair stands in for the NPU/CPU ASR pair.
        app.preparer.needsCpu[det] = rec
        val flows = recording(det.value, rec.value) {
            downloads.enqueue(det, wifiOnly = false)
            downloads.awaitState(rec, 180_000) { it is InstallState.Installed }
        }
        assertEquals(listOf("Queued", "Downloading", "Verifying", "Preparing", "Installed"), flows.getValue(rec.value))
        assertTrue(app.eventLog.readLines().any { " queue_cpu_artifact " in it && "artifact=${rec.value}" in it })
        assertInstalledAndVerified(rec)
    }

    @Test
    fun corruptedPartFailsWithChecksumMismatchAndIsDeleted(): Unit = runBlocking {
        // Real bytes first, then truncate the file to a part of half its length and flip one byte.
        downloads.enqueue(rec, wifiOnly = false)
        downloads.awaitState(rec, 180_000) { it is InstallState.Installed }
        val spec = catalog.artifact(rec)!!.second.files.first { it.name == "rec.onnx" }
        val storage = downloads.storage
        storage.marker(rec).delete()
        val part = storage.part(rec, spec.name)
        storage.file(rec, spec.name).renameTo(part)
        RandomAccessFile(part, "rw").use { f ->
            f.setLength(spec.bytes / 2)
            f.seek(1_000); val b = f.read(); f.seek(1_000); f.write(b xor 0x5a)
        }
        downloads.refresh()
        downloads.enqueue(rec, wifiOnly = false)
        val end = downloads.awaitState(rec, 180_000) { it is InstallState.Failed || it is InstallState.Installed }
        assertEquals(InstallState.Failed(MurmurError.ChecksumMismatch), end)
        assertFalse(part.exists())
        assertFalse(storage.file(rec, spec.name).exists())
        val http = app.eventLog.readLines().last { " dl_http " in it && "file=rec.onnx" in it }
        assertTrue(http, "code=206" in http && "rangeFrom=${spec.bytes / 2}" in http)
    }
}
