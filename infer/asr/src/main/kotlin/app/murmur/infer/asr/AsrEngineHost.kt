package app.murmur.infer.asr

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import app.murmur.core.audio.PauseSegmenter
import app.murmur.core.audio.SegmenterConfig
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.infer.asr.qnnep.GigaAmCtcModel
import app.murmur.infer.asr.qnnep.QnnEp
import app.murmur.infer.asr.qnnep.QnnEpAndroid
import app.murmur.infer.asr.qnnep.QnnEpException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The ASR side of the :asr process: one engine thread owns the recognizers, the VAD and every session's processing
 * (ARCHITECTURE 14); each session has its own pipe reader thread that only queues PCM. Binder threads post here.
 */
class AsrEngineHost(
    private val nativeLibraryDir: String,
    /** Qualcomm's QNN plugin EP of this process, for the `ort-qnn/gigaam-ctc` NPU port (WP-21). */
    private val qnnEp: () -> QnnEp = { QnnEpAndroid.ep(nativeLibraryDir) },
) {
    private val engine: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-engine") }

    // Engine thread only.
    private var model: SherpaModel? = null
    /** The ORT QNN EP batch model (GigaAM CTC on the HTP); at most one of [model] and [ctc] is loaded. */
    private var ctc: GigaAmCtcModel? = null
    private var vad: SileroVadModel? = null
    /** The last successful load asked for a VAD: streaming sessions then run it too (speech edges, speechMs). */
    private var vadRequested = false
    private val sessions = LinkedHashSet<AsrSessionCore>()

    fun post(block: () -> Unit) = engine.execute(block)

    // ---------------------------------------------------------------- load

    /** Loads (or keeps) the ASR model and the VAD (required for batch models, optional for streaming ones). Engine thread. */
    fun load(asr: LoadSpec, vadSpec: LoadSpec?): Outcome<LoadInfo> {
        val t0 = SystemClock.elapsedRealtime()
        val result = loadInner(asr, vadSpec)
        when (result) {
            is Outcome.Ok -> { vadRequested = vadSpec != null; attachWaiting() }
            // only the sessions waiting now learn of it; a session started later never inherits an older load's error
            is Outcome.Err -> sessions.forEach { it.loadFailed(result.error) }
        }
        Log.i(TAG, "loadAsr ${asr.runtime} unit=${asr.unit} -> ${(result as? Outcome.Err)?.error?.code ?: "ok"} in ${SystemClock.elapsedRealtime() - t0} ms")
        return when (result) {
            is Outcome.Ok -> Outcome.Ok(result.value.copy(loadMs = SystemClock.elapsedRealtime() - t0))
            is Outcome.Err -> result
        }
    }

    private fun loadInner(asr: LoadSpec, vadSpec: LoadSpec?): Outcome<LoadInfo> {
        val qnnCtc = asr.runtime == RuntimeKeys.ORT_QNN_GIGAAM_CTC
        if (asr.kind != ModelKind.ASR || (asr.runtime !in SherpaConfigs.SHERPA && !qnnCtc)) return Outcome.Err(MurmurError.EngineRuntimeMissing(asr.runtime))
        val batch = asr.runtime in SherpaConfigs.OFFLINE || qnnCtc
        if (batch && vadSpec == null) return Outcome.Err(MurmurError.NoVadModel)
        if (vadSpec != null && vadSpec.runtime != RuntimeKeys.ORT_SILERO_VAD) return Outcome.Err(MurmurError.EngineRuntimeMissing(vadSpec.runtime))
        if (asr.unit == ComputeUnit.GPU) return Outcome.Err(MurmurError.UnitUnavailable(ComputeUnit.GPU))
        if (qnnCtc && asr.unit != ComputeUnit.NPU) return Outcome.Err(MurmurError.UnitUnavailable(asr.unit))
        val ep = if (qnnCtc) qnnEp().takeIf { it.status.available } ?: return Outcome.Err(MurmurError.UnitUnavailable(ComputeUnit.NPU)) else null
        if (!qnnCtc && !Sherpa.loaded) return Outcome.Err(MurmurError.EngineRuntimeMissing(asr.runtime))
        val qnn = if (asr.unit == ComputeUnit.NPU && !qnnCtc) {
            val s = QnnProbe.status(nativeLibraryDir)
            if (!s.available) return Outcome.Err(MurmurError.UnitUnavailable(ComputeUnit.NPU))
            s
        } else null

        // VAD first (small): a batch model is useless without it; a streaming model uses it for speechMs.
        if (vadSpec != null) {
            val v = vadSpec
            if (vad?.spec != v) {
                if (vad != null) sessions.filter { it.attached }.forEach { it.abort(MurmurError.AsrFailed("model_replaced")); sessions -= it }
                vad?.close(); vad = null
                SherpaConfigs.missingRole(v)?.let { return Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.VAD, "missing:$it")) }
                vad = try {
                    SileroVadModel(v)
                } catch (e: Exception) {
                    return Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.VAD, "ort:${e.javaClass.simpleName}"))
                }
            }
        }

        val current = model
        if (current != null && current.spec == asr) return Outcome.Ok(info(current, qnn))
        ctc?.takeIf { it.spec == asr }?.let { return Outcome.Ok(LoadInfo(it.unit, 0, QNN_EP_DEVICE)) }
        if (current != null || ctc != null) {
            // sessions decoding with the old recognizer must not touch it after close()
            sessions.filter { it.attached }.forEach { it.abort(MurmurError.AsrFailed("model_replaced")); sessions -= it }
            current?.close()
            model = null
            ctc?.close()
            ctc = null
        }
        if (ep != null) return loadCtc(asr, ep)
        try {
            SherpaConfigs.missingRole(asr)?.let { return Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.ASR, "missing:$it")) }
            val m = if (batch) SherpaModel.Offline(asr, SherpaConfigs.offline(asr, qnn?.libs))
            else SherpaModel.Online(asr, SherpaConfigs.online(asr, qnn?.libs))
            model = m
            return Outcome.Ok(info(m, qnn))
        } catch (e: SpecException) {
            return Outcome.Err(
                if (e.reason == "unit.npu") MurmurError.UnitUnavailable(ComputeUnit.NPU)
                else MurmurError.EngineLoadFailed(ModelKind.ASR, e.reason),
            )
        } catch (e: IllegalArgumentException) {
            // sherpa's require(ptr != 0): the config did not validate (wrong files for this runtime)
            return Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.ASR, "config"))
        } catch (e: OutOfMemoryError) {
            return Outcome.Err(MurmurError.OutOfMemory(ModelKind.ASR))
        }
    }

    /**
     * GigaAM CTC on the QNN EP. A context that does not open judges the NPU (EngineLoadFailed, the CPU artifact is the
     * fallback); OutOfMemory stays OutOfMemory (ADR-066/084).
     */
    private fun loadCtc(asr: LoadSpec, ep: QnnEp): Outcome<LoadInfo> = try {
        val m = GigaAmCtcModel.open(asr, ep)
        ctc = m
        Outcome.Ok(LoadInfo(m.unit, 0, QNN_EP_DEVICE))
    } catch (e: QnnEpException) {
        Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.ASR, "qnn:${e.stage}"))
    } catch (e: IllegalArgumentException) {
        Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.ASR, "config"))
    } catch (e: IllegalStateException) {
        Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.ASR, "config"))
    } catch (e: java.io.IOException) {
        Outcome.Err(MurmurError.EngineLoadFailed(ModelKind.ASR, "io"))
    } catch (e: OutOfMemoryError) {
        Outcome.Err(MurmurError.OutOfMemory(ModelKind.ASR))
    }

    /** deviceName = sherpa's provider ("qnn" | "cpu"), what DEVICE_CHECKLIST DC8 asserts; the HTP arch is a probe fact. */
    private fun info(m: SherpaModel, qnn: QnnStatus?): LoadInfo =
        LoadInfo(unit = m.unit, loadMs = 0, deviceName = if (qnn != null) "qnn" else "cpu")

    fun unloadAll() {
        sessions.toList().forEach { it.abort(MurmurError.AsrFailed("unloaded")) }
        sessions.clear()
        model?.close(); model = null
        ctc?.close(); ctc = null
        vad?.close(); vad = null
        vadRequested = false
    }

    /** Runs [unloadAll] on the engine thread and waits for it at most [timeoutMs]. */
    fun unloadAllBlocking(timeoutMs: Long) {
        val done = CountDownLatch(1)
        post { try { unloadAll() } finally { done.countDown() } }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ---------------------------------------------------------------- sessions

    /**
     * Starts a session on [pcm] (PCM16 LE mono 16 kHz, EOF = end of audio). It reads from the first byte on; audio
     * waits in the session queue until a model is loaded. [emit] receives every AsrEvent (from the engine thread).
     */
    fun startSession(options: AsrSessionOptions, pcm: ParcelFileDescriptor, emit: (AsrEvent) -> Unit): SessionHandle {
        val core = AsrSessionCore(options, emit)
        val reader = PcmPipeReader(ParcelFileDescriptor.AutoCloseInputStream(pcm), core) { post { core.pump() } }
        post {
            sessions += core
            processorFor(core)?.let { core.attach(it) }
        }
        reader.start()
        return SessionHandle(core, reader)
    }

    inner class SessionHandle(private val core: AsrSessionCore, private val reader: PcmPipeReader) {
        fun finish(reply: (Outcome<Transcript>) -> Unit) {
            val at = core.nowMs()
            post { core.requestFinish(at) { r -> sessions -= core; reply(r) } }
        }

        fun cancel() {
            reader.close()
            post { core.cancel(); sessions -= core }
        }
    }

    private fun attachWaiting() {
        for (s in sessions) if (!s.attached && !s.closed) processorFor(s)?.let { s.attach(it) }
    }

    private fun processorFor(core: AsrSessionCore): SessionProcessor? {
        ctc?.let { c ->
            val v = vad ?: return null
            // GigaAM is Russian only and takes no language hint (catalog languages.mode fixed)
            return SegmentingProcessor(c.unit, v.newState(), PauseSegmenter(SegmenterConfig.forWindow(c.spec.maxWindowSec)), c.decoder(), null, core.emitter)
        }
        val m = model ?: return null
        return when (m) {
            is SherpaModel.Offline -> {
                val v = vad ?: return null
                SegmentingProcessor(
                    m.unit, v.newState(), PauseSegmenter(SegmenterConfig.forWindow(m.spec.maxWindowSec)),
                    m.decoder(), core.language.takeIf { SherpaConfigs.takesHint(m.spec.runtime) }, core.emitter,
                )
            }
            is SherpaModel.Online -> StreamingProcessor(
                m.unit, m.stream(core.language), core.language.takeIf { SherpaConfigs.takesHint(m.spec.runtime) },
                core.emitPartials,
                vad?.takeIf { vadRequested }?.let { SpeechTracker(it.newState(), PauseSegmenter(), core.emitter) },
                core.emitter,
            )
        }
    }

    companion object {
        private const val TAG = "murmur-asr"
        /** LoadInfo.deviceName of the ORT QNN EP path (the sherpa path says "qnn"). */
        const val QNN_EP_DEVICE = "qnn-ep"
    }
}

/** Reads PCM16 LE from the session pipe on its own thread and queues it; EOF or an I/O error ends the audio. */
class PcmPipeReader(
    private val input: InputStream,
    private val core: AsrSessionCore,
    private val onData: () -> Unit,
) : Thread("asr-pcm") {
    @Volatile private var stopped = false

    init { isDaemon = true }

    override fun run() {
        val buf = ByteArray(6_400) // 200 ms
        var carry = -1 // a dangling low byte between reads
        try {
            while (!stopped) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                var i = 0
                val count = (n + (if (carry >= 0) 1 else 0)) / 2
                val out = ShortArray(count)
                var k = 0
                if (carry >= 0) {
                    out[k++] = ((buf[0].toInt() shl 8) or carry).toShort()
                    i = 1
                    carry = -1
                }
                while (i + 1 < n) {
                    out[k++] = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()
                    i += 2
                }
                if (i < n) carry = buf[i].toInt() and 0xff
                core.offer(out)
                onData()
            }
        } catch (e: IOException) {
            // the writer side died or the session was cancelled: the audio ends here
        } finally {
            runCatching { input.close() }
            core.endOfAudio()
            onData()
        }
    }

    fun close() {
        stopped = true
        runCatching { input.close() }
    }
}
