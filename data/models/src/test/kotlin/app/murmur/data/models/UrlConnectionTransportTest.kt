package app.murmur.data.models

import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The real HttpURLConnection transport against a minimal local HTTP/1.1 server: redirects are followed by hand, the
 * Range header is sent on every hop, and a hop to a host outside the policy is never requested.
 */
class UrlConnectionTransportTest {
    private val server = ServerSocket(0)
    private val port = server.localPort
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val body = bytesOf(5_000)

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                s.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    val path = requestLine.split(' ')[1]
                    val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
                    seen += "${sock.localAddress.hostAddress} $path range=$range"
                    val out = sock.getOutputStream()
                    fun send(status: String, extra: String, payload: ByteArray) {
                        out.write("HTTP/1.1 $status\r\n${extra}Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        out.write(payload)
                        out.flush()
                    }
                    when (path) {
                        "/start" -> send("302 Found", "Location: /cdn/file\r\n", ByteArray(0))
                        "/foreign" -> send("302 Found", "Location: http://localhost:$port/cdn/file\r\n", ByteArray(0))
                        "/cdn/file" -> {
                            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt()
                            if (from == null) send("200 OK", "", body)
                            else send("206 Partial Content", "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n", body.copyOfRange(from, body.size))
                        }
                        else -> send("404 Not Found", "", ByteArray(0))
                    }
                }
            }
        }
    }

    @After
    fun close() = server.close()

    private val transport = UrlConnectionTransport(HostPolicy(requireHttps = false, allowed = { it == "127.0.0.1" }))

    @Test
    fun followsAllowedRedirectsAndResendsRange() {
        transport.get("http://127.0.0.1:$port/start", mapOf("Range" to "bytes=1000-")).use { r ->
            assertEquals(206, r.code)
            assertEquals("/cdn/file", r.finalUrl.path)
            assertEquals(1, r.hops.size)
            assertEquals("bytes 1000-4999/5000", r.header("Content-Range"))
            assertTrue(body.copyOfRange(1000, 5000).contentEquals(r.body.readBytes()))
        }
        assertEquals(listOf("127.0.0.1 /start range=bytes=1000-", "127.0.0.1 /cdn/file range=bytes=1000-"), seen.toList())
    }

    @Test
    fun hopToAForeignHostIsNeverRequested() {
        try {
            transport.get("http://127.0.0.1:$port/foreign").close()
            fail("redirect to localhost must be refused")
        } catch (e: HostNotAllowed) {
            assertTrue(e.message!!.contains("localhost"))
        }
        assertEquals(listOf("127.0.0.1 /foreign range=null"), seen.toList())
    }

    @Test
    fun productionPolicyRefusesPlainHttpAndUnlistedHosts() {
        val strict = HostPolicy()
        assertThrows { strict.check(java.net.URL("http://huggingface.co/x")) }
        assertThrows { strict.check(java.net.URL("https://example.com/x")) }
        strict.check(java.net.URL("https://huggingface.co/x"))
        strict.check(java.net.URL("https://us.aws.cdn.hf.co/x"))
        strict.check(java.net.URL("https://release-assets.githubusercontent.com/x"))
    }

    private fun assertThrows(block: () -> Unit) {
        try { block(); fail("expected HostNotAllowed") } catch (e: HostNotAllowed) { /* expected */ }
    }
}
