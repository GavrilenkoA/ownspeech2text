package app.murmur.infer.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.infer.api.IAsrService
import app.murmur.infer.api.IAsrSessionCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val TAG = "murmur-asr-test"

val instrumentation get() = InstrumentationRegistry.getInstrumentation()
val context: Context get() = instrumentation.targetContext

/** Numbers for the WP-06 report: logcat and `am instrument -r` status lines. */
fun report(key: String, value: Any) {
    Log.i(TAG, "$key=$value")
    instrumentation.sendStatus(2, Bundle().apply { putString(key, value.toString()) })
}

fun shell(cmd: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText().trim()

/** The :asr process of this test APK (the library manifest declares android:process=":asr"). */
val asrProcessName: String get() = context.packageName + ":asr"

fun asrPid(): String = shell("pidof $asrProcessName")

/** A test clip of `assets/asr/` (16 kHz mono PCM16 WAV) with its reference text. */
class Clip(val file: String, val lang: String, val ref: String, val samples: ShortArray) {
    val seconds: Double get() = samples.size / 16_000.0

    companion object {
        fun load(file: String): Clip {
            val assets = instrumentation.context.assets
            val meta = Json.parseToJsonElement(assets.open("asr/clips.json").bufferedReader().readText()).jsonArray
                .map { it.jsonObject }.first { it["file"]!!.jsonPrimitive.content == file }
            val bytes = assets.open("asr/$file").readBytes()
            return Clip(file, meta["lang"]!!.jsonPrimitive.content, meta["ref"]!!.jsonPrimitive.content, pcmOfWav(bytes))
        }

        /** Walks the RIFF chunks to "data"; asserts the format is PCM16 mono 16 kHz. */
        private fun pcmOfWav(b: ByteArray): ShortArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            check(String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "not a WAV" }
            var pos = 12
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4)
                val len = buf.getInt(pos + 4)
                if (id == "fmt ") {
                    check(buf.getShort(pos + 8).toInt() == 1 && buf.getShort(pos + 10).toInt() == 1 &&
                        buf.getInt(pos + 12) == 16_000 && buf.getShort(pos + 22).toInt() == 16) { "need PCM16 mono 16 kHz" }
                }
                if (id == "data") {
                    val out = ShortArray(len / 2)
                    buf.position(pos + 8)
                    buf.asShortBuffer().get(out)
                    return out
                }
                pos += 8 + len + (len and 1)
            }
            error("no data chunk")
        }
    }
}

/**
 * Model files come from `tools/models/fetch` on the host and are copied into this APK's files dir with run-as
 * (infer/asr/tools/push-test-models.sh). The specs use the bundled catalog's layout, exactly as :main would build them.
 */
object Models {
    private val catalog by lazy { CatalogParser.bundled() }

    fun spec(artifactId: String, unit: ComputeUnit? = null, threads: Int = 4): LoadSpec {
        val model = catalog.models.first { m -> m.artifacts.any { it.id.value == artifactId } }
        val a = model.artifacts.first { it.id.value == artifactId }
        return LoadSpec(model.kind, model.id, a.id, a.runtime, unit ?: a.units.first(),
            File(context.filesDir, "models/$artifactId").absolutePath, a.layout, a.maxWindowSec,
            mapOf(LoadOptions.THREADS to threads.toString()))
    }

    /** Fails (not skips) with the command that installs the files. */
    fun requireInstalled(spec: LoadSpec) {
        val missing = SherpaConfigs.requiredFiles(spec).filterValues { !File(it).isFile }.keys
        if (missing.isNotEmpty()) fail("${spec.artifactId.value}: missing $missing; run infer/asr/tools/push-test-models.sh <serial>")
    }
}

/** A bound AsrService in its own :asr process. */
class AsrConnection : AutoCloseable {
    private val latch = CountDownLatch(1)
    @Volatile private var binder: IBinder? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; latch.countDown() }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }
    val service: IAsrService

    init {
        val ok = context.bindService(Intent(context, AsrService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        assertTrue("bindService", ok)
        assertTrue("connected within 10 s", latch.await(10, TimeUnit.SECONDS))
        service = IAsrService.Stub.asInterface(binder)
    }

    fun load(asr: LoadSpec, vad: LoadSpec?, timeoutSec: Long = 120): Result {
        val r = Result()
        service.loadAsr(InferApi.json.encodeToString(LoadSpec.serializer(), asr), vad?.let { InferApi.json.encodeToString(LoadSpec.serializer(), it) }, r)
        r.await(timeoutSec)
        return r
    }

    override fun close() = context.unbindService(conn)
}

/** One-shot binder result. */
class Result : IResultCallback.Stub() {
    private val latch = CountDownLatch(1)
    @Volatile var json: String? = null
    @Volatile var error: MurmurError? = null
    @Volatile var atMs = 0L

    override fun onResult(json: String?) { this.json = json; atMs = SystemClock.elapsedRealtime(); latch.countDown() }
    override fun onError(errorJson: String?) { error = InferApi.decodeError(errorJson.orEmpty()); atMs = SystemClock.elapsedRealtime(); latch.countDown() }

    fun await(timeoutSec: Long): Boolean = latch.await(timeoutSec, TimeUnit.SECONDS)
    fun requireOk(): String = json ?: throw AssertionError("expected a result, got ${error?.code ?: "no answer"}")
}

/** Session events with their arrival time. */
class Events : IAsrSessionCallback.Stub() {
    val list: MutableList<Pair<Long, AsrEvent>> = Collections.synchronizedList(ArrayList())
    override fun onEvent(asrEventJson: String?) {
        list += SystemClock.elapsedRealtime() to InferApi.json.decodeFromString(AsrEvent.serializer(), asrEventJson.orEmpty())
    }
    inline fun <reified T : AsrEvent> count(beforeMs: Long = Long.MAX_VALUE) = synchronized(list) { list.count { it.first <= beforeMs && it.second is T } }
}

/** Writes PCM16 LE to the session pipe in 20 ms chunks; real time or as fast as the pipe takes it. Returns the EOF time. */
fun writePcm(write: ParcelFileDescriptor, samples: ShortArray, realTime: Boolean, startDelayMs: Long = 0): Long {
    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
        val chunk = 320
        val bytes = ByteArray(chunk * 2)
        val t0 = SystemClock.elapsedRealtime() + startDelayMs
        var i = 0
        var n = 0
        while (i < samples.size) {
            if (realTime) {
                val due = t0 + n * 20L
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            val len = minOf(chunk, samples.size - i)
            for (k in 0 until len) {
                val s = samples[i + k].toInt()
                bytes[2 * k] = (s and 0xff).toByte()
                bytes[2 * k + 1] = (s shr 8).toByte()
            }
            out.write(bytes, 0, len * 2)
            i += len
            n++
        }
    }
    return SystemClock.elapsedRealtime()
}
