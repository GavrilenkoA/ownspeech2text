package app.murmur.data.models

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDownloaderTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val url = "https://huggingface.co/o/r/resolve/0123456789012345678901234567890123456789/model.onnx"
    private val content = bytesOf(1_000_000)
    private val sha = sha256(content)
    private val net = FakeTransport().also { it.files[url] = content }
    private val events = mutableListOf<String>()
    private val dl = FileDownloader(net, DlLog { name, fields -> events += name + fields.joinToString(" ", " ") { "${it.first}=${it.second}" } }, bufferBytes = 64 * 1024)
    private val dir = tempDir("dl")
    private val part = File(dir, "model.onnx.part")
    private val target = File(dir, "model.onnx")

    private fun fetch(progress: MutableList<Long> = mutableListOf()) = runBlocking { dl.fetch(url, part, content.size.toLong(), sha) { progress += it } }

    @Test
    fun freshDownloadIsVerifiedAndRenamedAtomically() {
        val progress = mutableListOf<Long>()
        val r = fetch(progress)
        assertEquals(Outcome.Ok(target), r)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
        assertEquals(content.size.toLong(), progress.last())
        assertTrue(events.any { it.startsWith("dl_verified") })
        assertEquals(null, net.requests.single().headers["Range"])
    }

    @Test
    fun resumesFromThePartWithARangeRequestAnd206() {
        part.writeBytes(content.copyOfRange(0, 400_000))
        val progress = mutableListOf<Long>()
        val r = fetch(progress)
        assertEquals(Outcome.Ok(target), r)
        assertEquals("bytes=400000-", net.requests.single().headers["Range"])
        assertEquals(400_000L, progress.first())
        assertTrue(events.toString(), events.any { it.startsWith("dl_http") && "code=206" in it && "rangeFrom=400000" in it })
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun serverIgnoringTheRangeRestartsFromZero() {
        net.ignoreRange = true
        part.writeBytes(content.copyOfRange(0, 300_000))
        assertEquals(Outcome.Ok(target), fetch())
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun corruptedPartIsDeletedWithChecksumMismatch() {
        // Truncate + flip a byte: the resumed file hashes wrong.
        part.writeBytes(content.copyOfRange(0, 500_000))
        RandomAccessFile(part, "rw").use { it.setLength(250_000); it.seek(1_234); val b = it.read(); it.seek(1_234); it.write(b xor 0xff) }
        val r = fetch()
        assertEquals(Outcome.Err(MurmurError.ChecksumMismatch), r)
        assertEquals("bytes=250000-", net.requests.single().headers["Range"])
        assertFalse(part.exists())
        assertFalse(target.exists())
    }

    @Test
    fun longerBodyThanTheCatalogIsDamaged() {
        net.files[url] = content + bytesOf(10, seed = 9)
        assertEquals(Outcome.Err(MurmurError.ChecksumMismatch), fetch())
        assertFalse(part.exists())
    }

    @Test
    fun droppedConnectionKeepsThePartAndReportsNoNetwork() {
        net.dropAfter = 300_000
        val r = fetch()
        assertEquals(Outcome.Err(MurmurError.NoNetwork), r)
        assertTrue(part.length() in 1..300_000)
        net.dropAfter = null
        assertEquals(Outcome.Ok(target), fetch())
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun httpErrorsAndOfflineAreTyped() {
        net.status = 404
        assertEquals(Outcome.Err(MurmurError.DownloadFailed(404, "http")), fetch())
        net.status = null
        net.throwOnConnect = java.net.UnknownHostException("huggingface.co")
        assertEquals(Outcome.Err(MurmurError.NoNetwork), fetch())
    }

    @Test
    fun redirectToAForeignHostIsRefused() {
        net.files.remove(url)
        net.redirects[url] = "https://evil.example.com/x" to emptyMap()
        val r = fetch()
        assertEquals(Outcome.Err(MurmurError.DownloadFailed(null, "host not allowed")), r)
        assertFalse(target.exists())
    }

    @Test
    fun upstreamFileChangedAbortsBeforeTheBody() {
        val cdn = "https://cas-bridge.xethub.hf.co/x"
        net.files.remove(url)
        net.files[cdn] = content
        net.redirects[url] = cdn to mapOf("X-Linked-ETag" to "\"${"a".repeat(64)}\"")
        assertEquals(Outcome.Err(MurmurError.ChecksumMismatch), fetch())
        net.redirects[url] = cdn to mapOf("X-Linked-ETag" to "\"$sha\"")
        assertEquals(Outcome.Ok(target), fetch())
    }

    @Test
    fun alreadyCompletePartIsVerifiedWithoutNetwork() {
        part.writeBytes(content)
        assertEquals(Outcome.Ok(target), fetch())
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun cancellationKeepsTheFlushedPart() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val slow = object : HttpTransport by net {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                val r = net.get(url, headers)
                return HttpResponse(r.code, r.finalUrl, emptyMap(), r.hops, {
                    object : java.io.InputStream() {
                        val inner = r.body
                        override fun read(): Int = inner.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            started.complete(Unit)
                            Thread.sleep(20)
                            return inner.read(b, off, minOf(len, 10_000))
                        }
                    }
                }, {})
            }
        }
        val d = async(kotlinx.coroutines.Dispatchers.IO) { FileDownloader(slow).fetch(url, part, content.size.toLong(), sha) {} }
        started.await()
        Thread.sleep(100)
        d.cancel()
        withTimeout(5_000) { runCatching { d.await() } }
        assertTrue(part.length() in 1 until content.size)
        assertFalse(target.exists())
    }
}
