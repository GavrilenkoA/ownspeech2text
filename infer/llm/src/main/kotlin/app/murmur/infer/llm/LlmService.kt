package app.murmur.infer.llm

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.SyntheticBench
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.infer.api.ILlmService
import app.murmur.infer.api.ILlmStreamCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.api.InferProcessLifecycle
import kotlinx.serialization.KSerializer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The :llm process (WP-10): llama.cpp b12520 + ggml-hexagon behind [ILlmService]. One single-thread engine executor
 * owns the native context ([LlmEngine]); every binder method returns at once and answers through its oneway
 * callback, except [ILlmService.probe] and [ILlmService.memory] (synchronous, cheap). [ILlmService.cancel] is the only
 * call that touches the engine from another thread. Unbind = unload + process exit, the only way to drop the FastRPC
 * session and rpcmem (llm spike D7).
 */
class LlmService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-engine") }
    private lateinit var engine: LlmEngine
    private val generating = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        InferProcessLifecycle.installQuietCrashHandler(TAG)
        engine = LlmEngine(LlmNative.loadOrNull(), applicationInfo.nativeLibraryDir)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // D5: stop any decode, release the model on the engine thread, then exit so that nothing stays mapped.
        engine.cancel()
        runCatching { executor.submit { engine.unload() }.get(UNLOAD_WAIT_MS, TimeUnit.MILLISECONDS) }
        InferProcessLifecycle.exitNow()
        return false
    }

    private val binder = object : ILlmService.Stub() {
        override fun apiVersion(): Int = InferApi.API_VERSION

        override fun probe(): String =
            InferApi.encode(ProcessProbe.serializer(), executor.submit<ProcessProbe> { engine.probe() }.get())

        override fun memory(): String = InferApi.encode(ProcessMemory.serializer(), processMemory())

        override fun load(specJson: String?, cb: IResultCallback?) {
            val spec = decodeOrReply(LoadSpec.serializer(), specJson, cb) ?: return
            executor.execute { reply(cb, LoadInfo.serializer(), engine.load(spec)) }
        }

        override fun setPrefix(prefixSpecJson: String?, cb: IResultCallback?) {
            val spec = decodeOrReply(PrefixSpec.serializer(), prefixSpecJson, cb) ?: return
            val ticket = engine.nextTicket()
            executor.execute { reply(cb, PrefixInfo.serializer(), engine.setPrefix(ticket, spec)) }
        }

        override fun generate(requestJson: String?, cb: ILlmStreamCallback?) {
            if (cb == null) return
            val request = runCatching { InferApi.decode(LlmRequest.serializer(), requestJson.orEmpty()) }.getOrNull()
                ?: return quietly { cb.onError(InferApi.encodeError(MurmurError.Internal("bad payload"))) }
            if (!generating.compareAndSet(false, true)) {
                return quietly { cb.onError(InferApi.encodeError(MurmurError.Internal("llm.busy"))) }
            }
            val ticket = engine.nextTicket()
            executor.execute {
                try {
                    when (val g = engine.generate(ticket, request) { piece -> deliver { cb.onPiece(piece) } }) {
                        is LlmEngine.Generation.Done ->
                            quietly { cb.onDone(g.text, InferApi.encode(LlmStats.serializer(), g.stats)) }
                        is LlmEngine.Generation.Failed -> quietly { cb.onError(InferApi.encodeError(g.error)) }
                    }
                } finally {
                    generating.set(false)
                }
            }
        }

        override fun bench(nPrompt: Int, nGen: Int, reps: Int, cb: IResultCallback?) {
            val ticket = engine.nextTicket()
            executor.execute { reply(cb, SyntheticBench.serializer(), engine.bench(ticket, nPrompt, nGen, reps)) }
        }

        override fun cancel() = engine.cancel()

        override fun unload() {
            executor.execute { engine.unload() }
        }
    }

    private fun processMemory(): ProcessMemory {
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun kb(key: String) = status.firstOrNull { it.startsWith(key) }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1L
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val am = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return ProcessMemory(vmHwmKb = kb("VmHWM"), vmRssKb = kb("VmRSS"), pssKb = mi.totalPss.toLong(), systemAvailMb = am.availMem / (1024 * 1024))
    }

    private fun <T> decodeOrReply(s: KSerializer<T>, json: String?, cb: IResultCallback?): T? {
        val v = runCatching { InferApi.decode(s, json.orEmpty()) }.getOrNull()
        if (v == null) quietly { cb?.onError(InferApi.encodeError(MurmurError.Internal("bad payload"))) }
        return v
    }

    private fun <T> reply(cb: IResultCallback?, s: KSerializer<T>, r: Outcome<T>) = quietly {
        when (r) {
            is Outcome.Ok -> cb?.onResult(InferApi.encode(s, r.value))
            is Outcome.Err -> cb?.onError(InferApi.encodeError(r.error))
        }
    }

    /** A client that died while we answered is not an error of this process (it will rebind or not). */
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            Log.w(TAG, "client gone (${e.javaClass.simpleName})")
        }
    }

    /** Streams one piece; false when the client is gone, which stops the decode. */
    private inline fun deliver(block: () -> Unit): Boolean = try {
        block(); true
    } catch (_: RemoteException) {
        false
    }

    private companion object {
        const val TAG = "murmur-llm"
        const val UNLOAD_WAIT_MS = 1_500L
    }
}
