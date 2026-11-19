package app.murmur.data.models

import app.murmur.core.catalog.CatalogValidator
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Which hosts a request (and every redirect hop) may reach: ADR-016 and D9 (network only for model downloads). */
data class HostPolicy(
    val requireHttps: Boolean = true,
    val maxRedirects: Int = 10,
    val allowed: (String) -> Boolean = CatalogValidator::isAllowedHost,
) {
    fun check(url: URL) {
        if (requireHttps && url.protocol != "https") throw HostNotAllowed("scheme ${url.protocol}")
        if (!allowed(url.host)) throw HostNotAllowed(url.host)
    }
}

/** A request or a redirect went to a host outside the allowlist; nothing was fetched from it. */
class HostNotAllowed(what: String) : IOException("host not allowed: $what")

/** One response after redirects. [hops] = response headers of each redirect (lower-case names), in order. */
class HttpResponse(
    val code: Int,
    val finalUrl: URL,
    private val headers: Map<String, String>,
    val hops: List<Map<String, String>>,
    private val stream: () -> InputStream,
    private val onClose: () -> Unit,
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]
    val body: InputStream by lazy(stream)
    override fun close() = onClose()
}

interface HttpTransport {
    /** GET following redirects; each hop is checked against the policy before it is requested. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    /** POST (Hugging Face API); no redirects expected, any is checked like a GET hop. */
    fun post(url: String, body: ByteArray, contentType: String): HttpResponse
}

/**
 * HttpURLConnection with redirects followed by hand, so that every hop's host is checked (the platform would
 * follow them silently) and the Range header is sent again on each hop (the HF CDN URL is signed and expires after
 * about an hour, so every attempt starts again from the pinned huggingface.co URL: android-platform 7.4).
 */
class UrlConnectionTransport(
    private val policy: HostPolicy = HostPolicy(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse = request(url, "GET", headers, null, null)

    override fun post(url: String, body: ByteArray, contentType: String): HttpResponse =
        request(url, "POST", emptyMap(), body, contentType)

    private fun request(start: String, method: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpResponse {
        var url = URL(start)
        val hops = mutableListOf<Map<String, String>>()
        var currentMethod = method
        repeat(policy.maxRedirects + 1) {
            policy.check(url)
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = currentMethod
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")   // byte offsets must match the file (Range resume)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                if (currentMethod == "POST" && body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", contentType)
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.lastOrNull().orEmpty() }
                if (code in REDIRECTS) {
                    val location = h["location"] ?: throw IOException("redirect $code without Location")
                    hops += h
                    c.disconnect()
                    url = URL(url, location)
                    if (code == 303) currentMethod = "GET"
                    return@repeat
                }
                return HttpResponse(
                    code = code, finalUrl = url, headers = h, hops = hops,
                    stream = { if (code >= 400) c.errorStream ?: InputStream.nullInputStream() else c.inputStream },
                    onClose = { c.disconnect() },
                )
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
        }
        throw IOException("too many redirects")
    }

    companion object {
        const val USER_AGENT = "murmur-models/1"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
