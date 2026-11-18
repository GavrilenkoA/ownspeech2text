package app.murmur.infer.api.fake

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.ErrnoException
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import app.murmur.infer.api.IAsrService
import app.murmur.infer.api.IAsrSession
import app.murmur.infer.api.IAsrSessionCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.api.InferProcessLifecycle
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlinx.serialization.builtins.ListSerializer

/**
 * Test-only :fakeasr process: the real [IAsrService] contract with canned answers (FakeControl keys in brackets).
 * - loadAsr: LoadInfo, or never answers ["loadAsr"=hang], or an uncaught exception on a worker thread ["loadAsr"=crash].
 * - startSession: a reader thread drains the pipe to EOF, CRC32 over every byte; a Committed event per second of
 *   audio; finish -> Transcript("bytes=<n> crc32=<hex>"); kills its own process after ["killAfterBytes"] bytes.
 * - recognizeScreen: maps the frame read-only, touches every page ["ocr"=touch, default] or CRC32s it ["ocr"=crc],
 *   checks that a writable mapping is refused, answers one line "crc32=<hex> ro=<bool>".
 * - apiVersion: ["apiVersion"] or the real one.
 */
class FakeAsrService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val unloads = AtomicInteger()
    private val sessions = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        InferProcessLifecycle.installQuietCrashHandler("fake-asr")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        InferProcessLifecycle.unloadAndExit { binder.unloadAll() }
        return false
    }

    private fun control(key: String) = FakeControl.get(this, FakeControl.ASR, key)

    private val binder = object : IAsrService.Stub() {
        override fun apiVersion(): Int = control("apiVersion")?.toInt() ?: InferApi.API_VERSION

        override fun probe(): String = InferApi.encode(
            ProcessProbe.serializer(),
            ProcessProbe(
                InferProcess.ASR, setOf("fake"), setOf(ComputeUnit.CPU),
                mapOf("pid" to Process.myPid().toString(), "unloads" to unloads.get().toString(),
                    "sessions" to sessions.get().toString()),
            ),
        )

        override fun memory(): String = InferApi.encode(ProcessMemory.serializer(), FakeControl.memory(this@FakeAsrService))

        override fun loadAsr(asrSpecJson: String?, vadSpecJson: String?, cb: IResultCallback) {
            val spec = InferApi.decode(LoadSpec.serializer(), requireNotNull(asrSpecJson))
            vadSpecJson?.let { InferApi.decode(LoadSpec.serializer(), it) }
            when (control("loadAsr")) {
                "hang" -> Unit
                "crash" -> Thread { throw IllegalStateException("fake runtime crash") }.start()
                else -> worker.execute { cb.onResult(InferApi.encode(LoadInfo.serializer(), LoadInfo(spec.unit, 1, "fake-cpu"))) }
            }
        }

        override fun loadOcr(detSpecJson: String?, recSpecJson: String?, cb: IResultCallback) {
            val det = InferApi.decode(LoadSpec.serializer(), requireNotNull(detSpecJson))
            InferApi.decode(LoadSpec.serializer(), requireNotNull(recSpecJson))
            worker.execute { cb.onResult(InferApi.encode(LoadInfo.serializer(), LoadInfo(det.unit, 1, "fake-cpu"))) }
        }

        override fun startSession(optionsJson: String?, pcmRead: ParcelFileDescriptor, cb: IAsrSessionCallback): IAsrSession {
            InferApi.decode(AsrSessionOptions.serializer(), requireNotNull(optionsJson))
            sessions.incrementAndGet()
            // Own the descriptor beyond this transaction.
            val pcm = pcmRead.dup().also { pcmRead.close() }
            return FakeSession(pcm, cb, control("killAfterBytes")?.toLong() ?: -1, control("finish") == "hang")
        }

        override fun recognizeScreen(pixels: SharedMemory, width: Int, height: Int, excludeJson: String?, cb: IResultCallback) {
            InferApi.decode(ListSerializer(Rect.serializer()), excludeJson ?: "[]")
            val mode = control("ocr") ?: "touch"
            val received = System.nanoTime()
            worker.execute {
                val readOnly = try {
                    SharedMemory.unmap(pixels.mapReadWrite()); false
                } catch (_: ErrnoException) {
                    true
                }
                val buf = pixels.mapReadOnly()
                val crc = CRC32()
                if (mode == "crc") {
                    crc.update(buf)
                } else {
                    // Every 4 KB page faulted in once: the cost of really reading the frame on the other side.
                    var sum = 0
                    var i = 0
                    while (i < buf.capacity()) { sum += buf.get(i); i += 4096 }
                    crc.update(sum)
                }
                val first = buf.order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                SharedMemory.unmap(buf)
                pixels.close()
                val svcUs = (System.nanoTime() - received) / 1000
                val line = OcrLine("crc32=%08x ro=%s first=%08x svc_us=%d".format(crc.value, readOnly, first, svcUs), 1.0, listOf(0, 0, width, height))
                cb.onResult(InferApi.encode(ScreenText.serializer(), ScreenText(listOf(width, height), listOf(line))))
            }
        }

        override fun unloadAll() {
            unloads.incrementAndGet()
        }
    }

    /** One session: the pipe reader runs from the start (audio is never left in the pipe), finish waits for EOF. */
    private inner class FakeSession(
        pcm: ParcelFileDescriptor,
        private val cb: IAsrSessionCallback,
        private val killAfterBytes: Long,
        private val hangFinish: Boolean,
    ) : IAsrSession.Stub() {
        private val crc = CRC32()
        @Volatile private var bytes = 0L
        @Volatile private var segments = 0
        private val input = ParcelFileDescriptor.AutoCloseInputStream(pcm)
        private val reader = Thread(::read, "fake-asr-reader").apply { start() }

        private fun read() {
            val buf = ByteArray(4096)
            while (true) {
                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                crc.update(buf, 0, n)
                bytes += n
                if (killAfterBytes in 0..bytes) Process.killProcess(Process.myPid())
                while (bytes >= (segments + 1) * BYTES_PER_SECOND) {
                    val i = segments++
                    val event = AsrEvent.Committed(i, "s$i", i * 1000L, (i + 1) * 1000L, null)
                    runCatching { cb.onEvent(InferApi.encode(AsrEvent.serializer(), event)) }
                }
            }
            runCatching { input.close() }
        }

        override fun finish(cb: IResultCallback) {
            if (hangFinish) return
            worker.execute {
                reader.join(TimeUnit.SECONDS.toMillis(30))
                val audioMs = bytes * 1000 / BYTES_PER_SECOND
                val t = Transcript("bytes=$bytes crc32=%08x".format(crc.value), null, segments, audioMs, audioMs, 0, ComputeUnit.CPU)
                cb.onResult(InferApi.encode(Transcript.serializer(), t))
            }
        }

        override fun cancel() {
            runCatching { input.close() }
        }
    }

    companion object {
        private const val BYTES_PER_SECOND = 32_000L
    }
}
