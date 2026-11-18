package app.murmur.data.models

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Event log of the download machinery: codes and numbers only (D9: never user text). */
fun interface DlLog {
    fun event(name: String, vararg fields: Pair<String, Any?>)

    companion object {
        val NONE = DlLog { _, _ -> }
    }
}

/**
 * [Downloader] over [HttpTransport] (platform spike g, lifted and hardened):
 *  - resume: the existing .part is hashed first, then `Range: bytes=<len>-`; a 206 must start exactly there,
 *    a 200 (server ignored the range) truncates and starts over;
 *  - SHA-256 streamed while writing; the part is fsynced whenever a session ends (so its length is a durable
 *    resume offset even after process death);
 *  - a Hugging Face X-Linked-ETag that is a sha256 different from the catalog aborts before the body is read;
 *  - mismatch (size or hash): the part is deleted and the result is [MurmurError.ChecksumMismatch];
 *  - success: atomic rename of `<name>.part` to `<name>`.
 * Cancellation (job stopped, user pause) keeps the part.
 */
class FileDownloader(
    private val http: HttpTransport,
    private val log: DlLog = DlLog.NONE,
    private val bufferBytes: Int = 256 * 1024,
) : Downloader {

    override suspend fun fetch(
        url: String,
        part: File,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Long) -> Unit,
    ): Outcome<File> = withContext(Dispatchers.IO) {
        val target = File(part.parentFile, part.name.removeSuffix(ModelStorage.PART))
        require(target.name != part.name) { "part file must end with ${ModelStorage.PART}" }
        part.parentFile?.mkdirs()
        if (part.length() > expectedBytes) {
            log.event("dl_part_oversized", "file" to target.name, "bytes" to part.length())
            part.delete()
        }
        var r = attempt(url, part, target, expectedBytes, expectedSha256, onProgress)
        if (r is Attempt.Restart) r = attempt(url, part, target, expectedBytes, expectedSha256, onProgress)
        when (r) {
            is Attempt.Done -> r.outcome
            is Attempt.Restart -> Outcome.Err(MurmurError.DownloadFailed(null, "range not honoured"))
        }
    }

    private sealed interface Attempt {
        data class Done(val outcome: Outcome<File>) : Attempt
        data object Restart : Attempt
    }

    private suspend fun attempt(
        url: String, part: File, target: File, expectedBytes: Long, expectedSha256: String, onProgress: (Long) -> Unit,
    ): Attempt {
        val md = MessageDigest.getInstance("SHA-256")
        val have = hashPrefix(part, md)
        onProgress(have)
        if (have == expectedBytes) return Attempt.Done(finish(part, target, md, expectedBytes, expectedSha256))
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
        val response = try {
            http.get(url, headers)
        } catch (e: IOException) {
            return Attempt.Done(Outcome.Err(networkError(e)))
        }
        response.use { res ->
            log.event(
                "dl_http", "file" to target.name, "code" to res.code, "rangeFrom" to have,
                "contentRange" to res.header("Content-Range"), "host" to res.finalUrl.host, "hops" to res.hops.size,
            )
            val linked = res.hops.firstNotNullOfOrNull { it["x-linked-etag"] }?.trim('"')?.lowercase()
            if (linked != null && SHA256.matches(linked) && linked != expectedSha256) {
                log.event("dl_upstream_changed", "file" to target.name)
                part.delete()
                return Attempt.Done(Outcome.Err(MurmurError.ChecksumMismatch))
            }
            val append = when (res.code) {
                206 -> {
                    val start = res.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    if (start != have) {
                        log.event("dl_range_mismatch", "file" to target.name, "expected" to have, "got" to start)
                        part.delete()
                        return Attempt.Restart
                    }
                    true
                }
                200 -> false
                416 -> { part.delete(); return Attempt.Restart }
                else -> return Attempt.Done(Outcome.Err(MurmurError.DownloadFailed(res.code, "http")))
            }
            if (!append && have > 0) {
                md.reset()   // server sent the whole file: start over
                RandomAccessFile(part, "rw").use { it.setLength(0) }
            }
            val written = try {
                copy(res, part, append, if (append) have else 0L, expectedBytes, md, onProgress)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                log.event("dl_io", "file" to target.name, "bytes" to part.length(), "err" to e.javaClass.simpleName)
                return Attempt.Done(Outcome.Err(networkError(e)))
            }
            if (written > expectedBytes) {
                part.delete()
                log.event("dl_checksum", "file" to target.name, "reason" to "oversized")
                return Attempt.Done(Outcome.Err(MurmurError.ChecksumMismatch))
            }
            if (written < expectedBytes) {
                // The stream ended early (connection dropped without an exception): keep the part, retry later.
                log.event("dl_short", "file" to target.name, "bytes" to written, "of" to expectedBytes)
                return Attempt.Done(Outcome.Err(MurmurError.NoNetwork))
            }
            return Attempt.Done(finish(part, target, md, expectedBytes, expectedSha256))
        }
    }

    /**
     * Streams the body into the part; returns the bytes the part holds (expected + 1 = longer than the catalog says).
     * A watcher closes the connection when the coroutine is cancelled (pause, onStopJob), so that a read blocked on
     * the socket returns at once; the part is flushed and fsynced either way.
     */
    private suspend fun copy(
        res: HttpResponse, part: File, append: Boolean, start: Long, expectedBytes: Long, md: MessageDigest, onProgress: (Long) -> Unit,
    ): Long = coroutineScope {
        val watcher = launch { try { awaitCancellation() } finally { res.close() } }
        try {
            var written = start
            res.body.use { input ->
                FileOutputStream(part, append).use { out ->
                    try {
                        val buf = ByteArray(bufferBytes)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (written + n > expectedBytes) { written = expectedBytes + 1; break }
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            written += n
                            onProgress(written)
                        }
                    } finally {
                        out.flush()
                        out.fd.sync()
                    }
                }
            }
            written
        } finally {
            watcher.cancel()
        }
    }

    private fun finish(part: File, target: File, md: MessageDigest, expectedBytes: Long, expectedSha256: String): Outcome<File> {
        val got = md.digest().toHex()
        if (part.length() != expectedBytes || got != expectedSha256) {
            log.event("dl_checksum", "file" to target.name, "bytes" to part.length(), "ok" to false)
            part.delete()
            return Outcome.Err(MurmurError.ChecksumMismatch)
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        log.event("dl_verified", "file" to target.name, "bytes" to expectedBytes, "sha256" to got)
        return Outcome.Ok(target)
    }

    /** Feeds the existing part into [md]; returns its length. */
    private fun hashPrefix(part: File, md: MessageDigest): Long {
        if (!part.isFile) return 0
        FileInputStream(part).use { input ->
            val buf = ByteArray(1 shl 20)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
            return total
        }
    }

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""")

        fun networkError(e: IOException): MurmurError = when (e) {
            is HostNotAllowed -> MurmurError.DownloadFailed(null, "host not allowed")
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException,
            is SocketException, is SSLException -> MurmurError.NoNetwork
            else -> MurmurError.DownloadFailed(null, e.javaClass.simpleName)
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().toHex()
}
