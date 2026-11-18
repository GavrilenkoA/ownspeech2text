package app.murmur.infer.api.fake

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.SyntheticBench
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.infer.api.BinderTimeouts
import app.murmur.infer.api.ILlmService
import app.murmur.infer.api.ILlmStreamCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.api.InferProcessLifecycle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only :fakellm process: the real [ILlmService] contract with canned answers (FakeControl keys in brackets).
 * - setPrefix answers after ["prefixDelayMs"] (a slow prefill), tokens = the :main estimate of the text.
 * - generate streams pieces "0,", "1,", ... ["pieces"] of them, one per ["pieceDelayMs"] on one engine thread;
 *   cancel() stops it with onDone(STOP_CANCELLED); the process kills itself after ["killAfterPieces"].
 * - bench answers ["ppTps"].
 */
class FakeLlmService : Service() {
    private val engine = Executors.newSingleThreadScheduledExecutor()
    private val cancelled = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val cancels = AtomicInteger()
    private val piecesSent = AtomicInteger()
    private val unloads = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        InferProcessLifecycle.installQuietCrashHandler("fake-llm")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        InferProcessLifecycle.unloadAndExit { binder.unload() }
        return false
    }

    private fun control(key: String) = FakeControl.get(this, FakeControl.LLM, key)

    private val binder = object : ILlmService.Stub() {
        override fun apiVersion(): Int = InferApi.API_VERSION

        override fun probe(): String = InferApi.encode(
            ProcessProbe.serializer(),
            ProcessProbe(
                InferProcess.LLM, setOf("fake"), setOf(ComputeUnit.CPU),
                mapOf("pid" to Process.myPid().toString(), "cancels" to cancels.get().toString(),
                    "pieces.sent" to piecesSent.get().toString(), "unloads" to unloads.get().toString()),
            ),
        )

        override fun memory(): String = InferApi.encode(ProcessMemory.serializer(), FakeControl.memory(this@FakeLlmService))

        override fun load(specJson: String?, cb: IResultCallback) {
            val spec = InferApi.decode(LoadSpec.serializer(), requireNotNull(specJson))
            engine.execute { cb.onResult(InferApi.encode(LoadInfo.serializer(), LoadInfo(spec.unit, 1, "fake-cpu"))) }
        }

        override fun setPrefix(prefixSpecJson: String?, cb: IResultCallback) {
            val spec = InferApi.decode(PrefixSpec.serializer(), requireNotNull(prefixSpecJson))
            val delayMs = control("prefixDelayMs")?.toLong() ?: 0
            engine.schedule({
                val info = PrefixInfo(BinderTimeouts.estimateTokens(spec.text), delayMs, restoredFromDisk = false, bytes = 0)
                cb.onResult(InferApi.encode(PrefixInfo.serializer(), info))
            }, delayMs, TimeUnit.MILLISECONDS)
        }

        override fun generate(requestJson: String?, cb: ILlmStreamCallback) {
            InferApi.decode(LlmRequest.serializer(), requireNotNull(requestJson))
            if (!busy.compareAndSet(false, true)) {
                cb.onError(InferApi.encodeError(MurmurError.Internal("busy")))
                return
            }
            cancelled.set(false)
            val pieces = control("pieces")?.toInt() ?: 200
            val delayMs = control("pieceDelayMs")?.toLong() ?: 5
            val killAfter = control("killAfterPieces")?.toInt() ?: -1
            engine.execute {
                val text = StringBuilder()
                var stop = LlmStats.STOP_EOG
                for (i in 0 until pieces) {
                    if (cancelled.get()) { stop = LlmStats.STOP_CANCELLED; break }
                    if (i == killAfter) Process.killProcess(Process.myPid())
                    val piece = "$i,"
                    text.append(piece)
                    cb.onPiece(piece)
                    piecesSent.incrementAndGet()
                    Thread.sleep(delayMs)
                }
                busy.set(false)
                val stats = LlmStats(10, piecesSent.get(), 0.0, 1.0, 1.0, 1.0, 2.0, stop, ComputeUnit.CPU)
                cb.onDone(text.toString(), InferApi.encode(LlmStats.serializer(), stats))
            }
        }

        override fun bench(nPrompt: Int, nGen: Int, reps: Int, cb: IResultCallback) {
            val pp = control("ppTps")?.toDouble() ?: 100.0
            engine.execute {
                cb.onResult(InferApi.encode(SyntheticBench.serializer(), SyntheticBench(pp, 10.0, nPrompt / pp * 1000, nGen / 10.0 * 1000)))
            }
        }

        override fun cancel() {
            cancels.incrementAndGet()
            cancelled.set(true)
        }

        override fun unload() {
            unloads.incrementAndGet()
        }
    }
}
