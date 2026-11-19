package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.ArchiveType
import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.FileSource
import app.murmur.core.catalog.FileSpec
import app.murmur.core.dictation.EnginePreparer
import app.murmur.core.dictation.PrepareReport
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.Outcome
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

/**
 * In-memory "internet": URL -> content (Range honoured unless [ignoreRange]), redirects, failures. Records every
 * request with its headers.
 */
class FakeTransport : HttpTransport {
    data class Req(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

    val requests = mutableListOf<Req>()
    val files = mutableMapOf<String, ByteArray>()
    val redirects = mutableMapOf<String, Pair<String, Map<String, String>>>()
    val json = mutableMapOf<String, String>()
    val posts = mutableMapOf<String, (String) -> String>()
    var ignoreRange = false
    /** Fail the body stream after this many bytes (IOException, like a dropped connection). */
    var dropAfter: Long? = null
    var status: Int? = null
    var throwOnConnect: IOException? = null

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += Req("GET", url, headers)
        throwOnConnect?.let { throw it }
        var u = url
        val hops = mutableListOf<Map<String, String>>()
        while (u in redirects) {
            val (to, h) = redirects.getValue(u)
            hops += h.mapKeys { it.key.lowercase() }
            if (!app.murmur.core.catalog.CatalogValidator.isAllowedHost(URL(to).host)) throw HostNotAllowed(URL(to).host)
            requests += Req("GET", to, headers)
            u = to
        }
        status?.let { return resp(it, u, hops, ByteArray(0)) }
        json[u]?.let { return resp(200, u, hops, it.encodeToByteArray()) }
        val content = files[u] ?: return resp(404, u, hops, ByteArray(0))
        val range = headers["Range"]?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
        if (range != null && !ignoreRange) {
            val from = range.groupValues[1].toLong()
            val to = range.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(content.size - 1L) ?: (content.size - 1L)
            if (from >= content.size) return resp(416, u, hops, ByteArray(0))
            val slice = content.copyOfRange(from.toInt(), (to + 1).toInt())
            return resp(206, u, hops, slice, mapOf("content-range" to "bytes $from-$to/${content.size}"))
        }
        return resp(200, u, hops, content)
    }

    override fun post(url: String, body: ByteArray, contentType: String): HttpResponse {
        requests += Req("POST", url, emptyMap(), body.decodeToString())
        val handler = posts[url] ?: return resp(404, url, emptyList(), ByteArray(0))
        return resp(200, url, emptyList(), handler(body.decodeToString()).encodeToByteArray())
    }

    private fun resp(code: Int, url: String, hops: List<Map<String, String>>, body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse {
        val drop = dropAfter
        val stream: InputStream = if (drop == null) ByteArrayInputStream(body) else object : InputStream() {
            private val inner = ByteArrayInputStream(body)
            private var n = 0L
            override fun read(): Int {
                if (n >= drop) throw IOException("connection reset")
                n++
                return inner.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n >= drop) throw java.net.SocketException("connection reset")
                val r = inner.read(b, off, minOf(len.toLong(), drop - n).toInt())
                if (r > 0) n += r
                return r
            }
        }
        return HttpResponse(code, URL(url), headers, hops, { stream }, {})
    }
}

/** Deterministic pseudo-random bytes. */
fun bytesOf(n: Int, seed: Int = 1): ByteArray = java.util.Random(seed.toLong()).let { r -> ByteArray(n).also { r.nextBytes(it) } }

fun hfSpec(name: String, content: ByteArray, archive: ArchiveType? = null) = FileSpec(
    name = name,
    url = "https://huggingface.co/test/repo/resolve/0123456789012345678901234567890123456789/$name",
    bytes = content.size.toLong(), sha256 = sha256(content), source = FileSource.HUGGINGFACE,
    repo = "test/repo", revision = "0123456789012345678901234567890123456789", path = name, archive = archive,
)

/** The bundled catalog with one extra test model holding [artifact] (the validator does not run on this copy). */
fun catalogWith(vararg artifacts: Artifact): Catalog {
    val base = CatalogParser.bundled()
    val model = base.models.first { it.id.value == "silero-vad-v5" }
    return base.copy(models = base.models + artifacts.mapIndexed { i, a -> model.copy(id = app.murmur.core.model.ModelId("test-model-$i"), artifacts = listOf(a)) })
}

fun testArtifact(id: String, vararg files: FileSpec, layout: Map<String, String> = files.associate { it.name to it.name }) = Artifact(
    id = ArtifactId(id), runtime = "ort/silero-vad-v5", format = "onnx", units = listOf(ComputeUnit.CPU),
    files = files.toList(), layout = layout, memoryMb = mapOf(ComputeUnit.CPU to 10),
)

class RecordingPreparer(var report: (ArtifactId) -> Outcome<PrepareReport> = { Outcome.Ok(PrepareReport(it, setOf(ComputeUnit.CPU), emptyMap(), null)) }) : EnginePreparer {
    val prepared = mutableListOf<ArtifactId>()
    var markerPresentAtPrepare: Boolean? = null
    var storage: ModelStorage? = null
    override suspend fun prepareArtifact(artifact: ArtifactId, onProgress: (Float) -> Unit): Outcome<PrepareReport> {
        prepared += artifact
        markerPresentAtPrepare = storage?.isInstalled(artifact)
        onProgress(0.5f)
        onProgress(1f)
        return report(artifact)
    }
    override fun invalidatePrefix() {}
}

class FakeScheduler : DownloadScheduler {
    val scheduled = mutableMapOf<ArtifactId, Boolean>()
    val cancelled = mutableListOf<ArtifactId>()
    var refuse = false
    override fun schedule(id: ArtifactId, wifiOnly: Boolean, estimatedBytes: Long): Boolean {
        if (refuse) return false
        scheduled[id] = wifiOnly
        return true
    }
    override fun cancel(id: ArtifactId) { cancelled += id; scheduled -= id }
    override fun pending(): Map<ArtifactId, Boolean> = scheduled.toMap()
}
