package app.murmur.infer.api

import android.app.ActivityManager
import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.OsConstants
import app.murmur.core.audio.PcmChunk
import app.murmur.core.engine.AsrEngine
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSession
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Engines
import app.murmur.core.engine.HostState
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.LlmEngine
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.OcrService
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.SyntheticBench
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer

/**
 * Binder plumbing shared by [AsrClient] and [LlmClient]: on-demand bind through [ServiceConnector], use counting
 * for the idle timer, every call bounded by its [BinderCall] timeout (× [BinderTimeouts.scale]) and mapped to
 * `Outcome` (no exception crosses this boundary). A timeout of a call whose [BinderCall.killOnTimeout] is set kills
 * that process and returns `Err(Timeout(stage))`; a death returns `Err(ProcessDied)`.
 */
abstract class BinderHost internal constructor(
    protected val bindContext: () -> Context,
    final override val process: InferProcess,
    protected val timeouts: () -> BinderTimeouts,
    serviceClassName: String,
    apiVersionOf: (IBinder) -> Int,
    unloadAll: (IBinder) -> Unit,
) : InferenceHost {
    internal val connector = ServiceConnector(bindContext, process, timeouts, apiVersionOf, unloadAll, serviceClassName)

    final override val state: StateFlow<HostState> get() = connector.state

    /** One emission per unexpected death (the crash count); [state] shows the same as [HostState.Crashed]. */
    val deaths: SharedFlow<Int> get() = connector.deaths

    final override suspend fun connect(): Boolean {
        connector.beginUse()
        try {
            return connector.connect() is Outcome.Ok
        } finally {
            connector.endUse()
        }
    }

    final override fun scheduleIdleExit(ms: Long) = connector.scheduleIdleExit(ms)

    final override suspend fun shutdown() = connector.shutdown()

    /** Unbind without waiting (the service unloads and exits by itself); see [ServiceConnector.disconnect]. */
    suspend fun disconnect() = connector.disconnect()

    protected abstract fun probeJson(binder: IBinder): String
    protected abstract fun memoryJson(binder: IBinder): String

    /** On failure: no runtimes, no units, [FACT_ERROR] = the error code (the device profile treats it as a failed probe). */
    final override suspend fun probe(): ProcessProbe {
        val r = sync(BinderCall.PROBE) { probeJson(it) }.decode(ProcessProbe.serializer())
        return when (r) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> ProcessProbe(process, emptySet(), emptySet(), mapOf(FACT_ERROR to r.error.code))
        }
    }

    /** On failure the process fields are [UNKNOWN]; [ProcessMemory.systemAvailMb] is measured here in :main. */
    final override suspend fun memory(): ProcessMemory {
        val r = sync(BinderCall.MEMORY) { memoryJson(it) }.decode(ProcessMemory.serializer())
        return when (r) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> ProcessMemory(UNKNOWN, UNKNOWN, UNKNOWN, systemAvailMb())
        }
    }

    private fun systemAvailMb(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        bindContext().getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.availMem / (1024 * 1024)
    }.getOrDefault(UNKNOWN)

    /** A synchronous binder call (returns at once on the service side), bounded by the call timeout. */
    protected suspend fun <T> sync(
        call: BinderCall,
        timeoutMs: Long = timeouts().let { it.scaled(it.callMs) },
        connectIfNeeded: Boolean = true,
        block: (IBinder) -> T,
    ): Outcome<T> {
        connector.beginUse()
        try {
            val (binding, binder) = if (connectIfNeeded) {
                when (val a = connector.acquire()) {
                    is Outcome.Ok -> a.value
                    is Outcome.Err -> return a
                }
            } else {
                connector.live() ?: return Outcome.Err(MurmurError.ProcessDied(process))
            }
            return settle(call, binding, connector.blocking(timeoutMs) { block(binder) })
        } finally {
            connector.endUse()
        }
    }

    /**
     * An asynchronous call: [start] hands an [IResultCallback] to the service and returns; the reply (or the death
     * of the process) completes it. The whole round trip is bounded by [timeoutMs].
     */
    protected suspend fun <T> result(
        call: BinderCall,
        timeoutMs: Long,
        decode: (String) -> T,
        start: (IBinder, IResultCallback) -> Unit,
    ): Outcome<T> {
        connector.beginUse()
        try {
            val (binding, binder) = when (val a = connector.acquire()) {
                is Outcome.Ok -> a.value
                is Outcome.Err -> return a
            }
            val reply = CompletableDeferred<Outcome<String>>()
            val watch = binding.watch { reply.complete(Outcome.Err(it)) }
            try {
                val cb = ResultCallback(reply)
                val r = withTimeoutOrNull(timeoutMs) {
                    connector.scope.launch(Dispatchers.IO) {
                        try {
                            start(binder, cb)
                        } catch (e: Exception) {
                            reply.complete(Outcome.Err(connector.binderError(e)))
                            if (e is android.os.DeadObjectException) connector.onDeadObject(binding)
                        }
                    }
                    reply.await()
                }
                return when (r) {
                    null -> timedOut(call, binding)
                    is Outcome.Ok -> r.decodeWith(decode)
                    is Outcome.Err -> r
                }
            } finally {
                binding.unwatch(watch)
            }
        } finally {
            connector.endUse()
        }
    }

    internal suspend fun <T> settle(call: BinderCall, binding: ServiceConnector.Binding, r: Outcome<T>): Outcome<T> =
        when {
            r is Outcome.Err && r.error === ServiceConnector.TIMEOUT_MARKER -> timedOut(call, binding)
            r is Outcome.Err && r.error is MurmurError.ProcessDied -> { connector.onDeadObject(binding); r }
            else -> r
        }

    /** Every other call or stream on [binding] fails with the same timeout (not `ProcessDied`) when it is killed. */
    internal suspend fun timedOut(call: BinderCall, binding: ServiceConnector.Binding): Outcome<Nothing> {
        val error = call.timeoutError()
        if (call.killOnTimeout) connector.kill(binding, error)
        return Outcome.Err(error)
    }

    protected fun <T> Outcome<String>.decode(s: kotlinx.serialization.KSerializer<T>): Outcome<T> =
        decodeWith { InferApi.decode(s, it) }

    protected fun <T> Outcome<String>.decodeWith(decode: (String) -> T): Outcome<T> = when (this) {
        is Outcome.Ok -> runCatching { decode(value) }.fold({ Outcome.Ok(it) }, { Outcome.Err(MurmurError.Internal("payload")) })
        is Outcome.Err -> this
    }

    companion object {
        /** [ProcessProbe.facts] key set when the probe itself failed (value = MurmurError.code). */
        const val FACT_ERROR = ProcessProbe.FACT_ERROR
        /** [ProcessMemory] field value when the process could not be asked. */
        const val UNKNOWN = -1L
    }
}

/** One-shot reply: the first of onResult / onError / death completes [reply]; later ones are ignored. */
internal class ResultCallback(private val reply: CompletableDeferred<Outcome<String>>) : IResultCallback.Stub() {
    override fun onResult(json: String?) { reply.complete(Outcome.Ok(json.orEmpty())) }
    override fun onError(errorJson: String?) { reply.complete(Outcome.Err(InferApi.decodeError(errorJson.orEmpty()))) }
}

/**
 * :main side of the :asr process (ARCHITECTURE.md 4.3): [AsrEngine] + [OcrService] + [InferenceHost] over
 * [IAsrService]. [bindContext] is read at every bind (A11yBridge.service ?: application, wired by :app).
 *
 * PCM: [startSession] creates a pipe, keeps the write end here and passes the read end to :asr; [AsrSession.accept]
 * never blocks (a lock-free ring drained by a writer thread, [PcmPump]). Replay after a crash or a unit fallback:
 * start a new session and `accept(PcmChunk(wholeSessionBuffer, 0))` once; any size is accepted, in order.
 * **One producer per session:** the replay and the live frames that follow it must be accepted from one thread (the
 * capture thread), or at least never at the same time: audio from two producers has no order. Overlapping calls are
 * detected and fail the session with `AsrFailed(`[PCM_CONCURRENT]`)` instead of feeding the recognizer garbage.
 *
 * [startSession] never throws except for cancellation, and a cancelled start leaks nothing (use count, pipe, writer
 * thread, a session already opened in :asr).
 *
 * Screenshots go through [SharedMemory] (never a parcel). :asr has a single `unloadAll()` (4.3), so [unload] and
 * [unloadOcr] both unload ASR, VAD and OCR together.
 */
class AsrClient(
    bindContext: () -> Context,
    timeouts: () -> BinderTimeouts,
    serviceClassName: String = InferApi.ASR_SERVICE,
) : BinderHost(
    bindContext, InferProcess.ASR, timeouts, serviceClassName,
    apiVersionOf = { IAsrService.Stub.asInterface(it).apiVersion() },
    unloadAll = { IAsrService.Stub.asInterface(it).unloadAll() },
), AsrEngine, OcrService {
    constructor(bindContext: () -> Context, timeouts: BinderTimeouts = BinderTimeouts()) : this(bindContext, { timeouts })

    private fun svc(b: IBinder): IAsrService = IAsrService.Stub.asInterface(b)
    override fun probeJson(binder: IBinder): String = svc(binder).probe()
    override fun memoryJson(binder: IBinder): String = svc(binder).memory()

    override suspend fun load(asr: LoadSpec, vad: LoadSpec?): Outcome<LoadInfo> {
        val t = timeouts()
        val asrJson = InferApi.encode(LoadSpec.serializer(), asr)
        val vadJson = vad?.let { InferApi.encode(LoadSpec.serializer(), it) }
        return result(BinderCall.ASR_LOAD, t.scaled(t.asrLoadMs), { InferApi.decode(LoadInfo.serializer(), it) }) { b, cb ->
            svc(b).loadAsr(asrJson, vadJson, cb)
        }
    }

    override suspend fun unload() {
        sync(BinderCall.UNLOAD, connectIfNeeded = false) { svc(it).unloadAll() }
    }

    override suspend fun loadOcr(det: LoadSpec, rec: LoadSpec): Outcome<LoadInfo> {
        val t = timeouts()
        val detJson = InferApi.encode(LoadSpec.serializer(), det)
        val recJson = InferApi.encode(LoadSpec.serializer(), rec)
        return result(BinderCall.OCR_LOAD, t.scaled(t.asrLoadMs), { InferApi.decode(LoadInfo.serializer(), it) }) { b, cb ->
            svc(b).loadOcr(detJson, recJson, cb)
        }
    }

    override suspend fun unloadOcr() = unload()

    override suspend fun recognize(image: ScreenImage): Outcome<ScreenText> {
        val t = timeouts()
        val exclude = InferApi.encode(ListSerializer(Rect.serializer()), image.exclude)
        val frame = frameOf(image)
            ?: return Outcome.Err(MurmurError.OcrFailed("shm"))
        try {
            return result(BinderCall.OCR_RECOGNIZE, t.scaled(t.ocrMs), { InferApi.decode(ScreenText.serializer(), it) }) { b, cb ->
                try {
                    svc(b).recognizeScreen(frame.shm, image.width, image.height, exclude, cb)
                } finally {
                    // Off the critical path: the service already reads its own mapping while ours goes away.
                    frame.unmap()
                }
            }
        } finally {
            // The service received its own descriptor; ours goes after the reply or the timeout.
            withContext(NonCancellable + Dispatchers.IO) { frame.close() }
        }
    }

    /** A frame in SharedMemory: read-only for every new mapping, plus the writable view it was filled through. */
    internal class Frame(val shm: SharedMemory, private var view: ByteBuffer?) : AutoCloseable {
        /** Drops our view (a 17 MB munmap is not free: it is done once the descriptor is on its way). */
        @Synchronized fun unmap() {
            view?.let { SharedMemory.unmap(it) }
            view = null
        }

        override fun close() {
            unmap()
            shm.close()
        }
    }

    /**
     * ARGB_8888 ints, little-endian. setProtect(PROT_READ) before it leaves :main: the service can only map it
     * read-only (existing mappings keep their rights, so ours stays valid until [Frame.unmap]).
     */
    internal suspend fun frameOf(image: ScreenImage): Frame? = withContext(Dispatchers.IO) {
        val shm = try {
            SharedMemory.create(FRAME_NAME, image.width * image.height * 4)
        } catch (_: ErrnoException) {
            return@withContext null
        }
        var view: ByteBuffer? = null
        try {
            val v = shm.mapReadWrite()
            view = v
            v.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(image.argb)
            if (!shm.setProtect(OsConstants.PROT_READ)) error("protect")
            Frame(shm, v)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            view?.let { SharedMemory.unmap(it) }
            shm.close()
            null
        }
    }

    override suspend fun startSession(options: AsrSessionOptions): AsrSession {
        val t = timeouts()
        connector.beginUse() // released by the session's end(), or here when no session is made
        var session: BinderAsrSession? = null
        var readEnd: ParcelFileDescriptor? = null
        try {
            val (binding, binder) = when (val a = connector.acquire()) {
                is Outcome.Ok -> a.value
                is Outcome.Err -> { connector.endUse(); return FailedAsrSession(a.error) }
            }
            val pipe = try {
                ParcelFileDescriptor.createPipe()
            } catch (_: IOException) {
                connector.endUse()
                return FailedAsrSession(MurmurError.AsrFailed("pipe"))
            }
            readEnd = pipe[0]
            val s = BinderAsrSession(binding, pipe[1])
            session = s // owns the use from here: its end() releases it
            s.open()
            val optionsJson = InferApi.encode(AsrSessionOptions.serializer(), options)
            // attach() inside the call: a reply that arrives after a timeout or a cancellation cancels the remote session.
            val r = connector.blocking(t.scaled(t.callMs)) {
                svc(binder).startSession(optionsJson, pipe[0], s.callback)?.also { s.attach(it) }
            }
            when (val x = settle(BinderCall.ASR_START_SESSION, binding, r)) {
                is Outcome.Ok -> if (x.value == null) s.fail(MurmurError.AsrFailed("no session"))
                is Outcome.Err -> s.fail(x.error)
            }
            return s
        } catch (e: Throwable) {
            // Cancelled (a press cancels Preparing, stop during Arming): the use, the write end and the writer thread
            // go with the session; a remote session that still arrives is cancelled by attach().
            if (session != null) session.cancel() else connector.endUse()
            throw e
        } finally {
            // :asr holds its own descriptor of the read end now (or the call failed): ours is not needed either way.
            readEnd?.let { runCatching { it.close() } }
        }
    }

    /** One dictation's session: pipe writer + events + finish/cancel; ends (and releases the host) exactly once. */
    internal inner class BinderAsrSession(
        private val binding: ServiceConnector.Binding,
        writeEnd: ParcelFileDescriptor,
    ) : AsrSession {
        private val channel = Channel<AsrEvent>(Channel.UNLIMITED)
        override val events: Flow<AsrEvent> = channel.receiveAsFlow()

        @Volatile private var remote: IAsrSession? = null
        @Volatile private var failure: MurmurError? = null
        @Volatile private var lastCommittedEndMs = 0L
        private val ended = AtomicBoolean(false)
        private val finishing = AtomicBoolean(false)
        private val reply = CompletableDeferred<Outcome<String>>()

        private val pump = PcmPump(
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd),
            onOverflow = { fail(MurmurError.AsrFailed(PCM_OVERFLOW)) },
            // EPIPE: the reader in :asr is gone (ARCHITECTURE 4.4).
            onWriteError = { fail(MurmurError.ProcessDied(InferProcess.ASR)) },
            onConcurrentOffer = { fail(MurmurError.AsrFailed(PCM_CONCURRENT)) },
            threadName = "murmur-pcm-asr",
        )
        /** Registered by [open], once the object exists: a dead binding runs the watcher at once. */
        @Volatile private var watch: ((MurmurError) -> Unit)? = null

        val callback = object : IAsrSessionCallback.Stub() {
            override fun onEvent(asrEventJson: String?) {
                val event = runCatching { InferApi.decode(AsrEvent.serializer(), asrEventJson.orEmpty()) }.getOrNull() ?: return
                if (event is AsrEvent.Committed) lastCommittedEndMs = maxOf(lastCommittedEndMs, event.endMs)
                channel.trySend(event)
            }
        }

        fun open() {
            val w = binding.watch { fail(it) }
            watch = w
            if (ended.get()) binding.unwatch(w)
        }

        /** The remote session; if this one already ended (cancelled while the start was in flight), it is cancelled. */
        fun attach(s: IAsrSession) {
            remote = s
            if (ended.get()) runCatching { s.cancel() }
        }

        /** The session cannot go on: events get one Failure, finish() returns [e]. First failure wins. */
        fun fail(e: MurmurError) {
            synchronized(this) {
                if (failure != null || ended.get()) return
                failure = e
            }
            channel.trySend(AsrEvent.Failure(e))
            pump.abort()
            reply.complete(Outcome.Err(e))
            if (!finishing.get()) end()
        }

        override fun accept(chunk: PcmChunk) {
            pump.offer(chunk.samples)
        }

        override suspend fun finish(): Outcome<Transcript> {
            if (!finishing.compareAndSet(false, true)) return Outcome.Err(MurmurError.Internal("finish twice"))
            try {
                failure?.let { return Outcome.Err(it) }
                val s = remote ?: return Outcome.Err(MurmurError.AsrFailed("no session"))
                val audioMs = pump.acceptedSamples * 1000 / PcmPump.SAMPLE_RATE
                val timeoutMs = timeouts().asrFinalMs(audioMs - lastCommittedEndMs)
                pump.close() // EOF after everything pending is written; :asr drains to EOF, then decodes the tail
                val r = withTimeoutOrNull(timeoutMs) {
                    connector.scope.launch(Dispatchers.IO) {
                        try {
                            s.finish(ResultCallback(reply))
                        } catch (e: Exception) {
                            reply.complete(Outcome.Err(connector.binderError(e)))
                            if (e is android.os.DeadObjectException) connector.onDeadObject(binding)
                        }
                    }
                    reply.await()
                }
                return when (r) {
                    null -> timedOut(BinderCall.ASR_FINISH, binding)
                    is Outcome.Ok -> r.decode(Transcript.serializer())
                    is Outcome.Err -> r
                }
            } finally {
                end()
            }
        }

        override fun cancel() {
            if (ended.get()) return
            pump.abort()
            end()
            // After end(): a remote attached from now on sees ended and cancels itself (attach), so none is missed.
            remote?.let { s -> connector.scope.launch(Dispatchers.IO) { runCatching { s.cancel() } } }
        }

        private fun end() {
            if (!ended.compareAndSet(false, true)) return
            watch?.let { binding.unwatch(it) }
            if (failure == null) pump.abort()
            channel.close()
            connector.endUse()
        }
    }

    /** A session that could not start: no audio is taken (WP-11 keeps the buffer), finish() returns the error. */
    private class FailedAsrSession(private val error: MurmurError) : AsrSession {
        override fun accept(chunk: PcmChunk) = Unit
        override val events: Flow<AsrEvent> = flowOf(AsrEvent.Failure(error))
        override suspend fun finish(): Outcome<Transcript> = Outcome.Err(error)
        override fun cancel() = Unit
    }

    companion object {
        const val FRAME_NAME = "murmur-frame"
        /** AsrFailed detail when more audio was pending than [PcmPump.DEFAULT_MAX_PENDING_SAMPLES]. */
        const val PCM_OVERFLOW = "pcm_overflow"
        /** AsrFailed detail when two threads called [AsrSession.accept] at the same time. */
        const val PCM_CONCURRENT = "pcm_concurrent"
    }
}

/**
 * :main side of the :llm process: [LlmEngine] + [InferenceHost] over [ILlmService].
 * [prefillTps] gives the benchmarked prefill speed of (artifact, unit) when the benchmark store has one; otherwise the
 * speed of the last [bench] of the loaded model is used, else [BinderTimeouts.prefillFloorTps].
 */
class LlmClient(
    bindContext: () -> Context,
    timeouts: () -> BinderTimeouts,
    private val prefillTps: (ArtifactId, ComputeUnit) -> Double? = { _, _ -> null },
    serviceClassName: String = InferApi.LLM_SERVICE,
) : BinderHost(
    bindContext, InferProcess.LLM, timeouts, serviceClassName,
    apiVersionOf = { ILlmService.Stub.asInterface(it).apiVersion() },
    unloadAll = { ILlmService.Stub.asInterface(it).unload() },
), LlmEngine {
    constructor(bindContext: () -> Context, timeouts: BinderTimeouts = BinderTimeouts()) : this(bindContext, { timeouts })

    @Volatile private var loaded: LoadSpec? = null
    private val benchPrefill = ConcurrentHashMap<Pair<ArtifactId, ComputeUnit>, Double>()

    private fun svc(b: IBinder): ILlmService = ILlmService.Stub.asInterface(b)
    override fun probeJson(binder: IBinder): String = svc(binder).probe()
    override fun memoryJson(binder: IBinder): String = svc(binder).memory()

    override suspend fun load(spec: LoadSpec): Outcome<LoadInfo> {
        val t = timeouts()
        val json = InferApi.encode(LoadSpec.serializer(), spec)
        val r = result(BinderCall.LLM_LOAD, t.scaled(t.llmLoadMs), { InferApi.decode(LoadInfo.serializer(), it) }) { b, cb ->
            svc(b).load(json, cb)
        }
        if (r is Outcome.Ok) loaded = spec
        return r
    }

    /** Timeout = [BinderTimeouts.prefixMs] of the estimated prefix tokens at the best known prefill speed. */
    override suspend fun setPrefix(prefix: PrefixSpec): Outcome<PrefixInfo> {
        val json = InferApi.encode(PrefixSpec.serializer(), prefix)
        val timeoutMs = timeouts().prefixMs(BinderTimeouts.estimateTokens(prefix.text), knownPrefillTps())
        return result(BinderCall.LLM_PREFIX, timeoutMs, { InferApi.decode(PrefixInfo.serializer(), it) }) { b, cb ->
            svc(b).setPrefix(json, cb)
        }
    }

    /** The prefill speed [setPrefix] scales its timeout with (null = the floor). */
    fun knownPrefillTps(): Double? = loaded?.let { s -> prefillTps(s.artifactId, s.unit) ?: benchPrefill[s.artifactId to s.unit] }

    /**
     * Cold flow: collecting it binds if needed and starts one generation. Pieces arrive in order; it completes after
     * [LlmEvent.Done] or [LlmEvent.Failed] (`ProcessDied` when :llm dies). Cancelling the collector cancels the
     * generation in :llm. No timeout here: the adaptive cleanup timer belongs to the orchestrator (5.3).
     */
    override fun generate(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        connector.beginUse()
        val over = AtomicBoolean(false)
        var binding: ServiceConnector.Binding? = null
        var watch: ((MurmurError) -> Unit)? = null
        fun finishWith(e: LlmEvent) {
            if (!over.compareAndSet(false, true)) return
            trySend(e)
            close()
        }
        try {
            when (val a = connector.acquire()) {
                is Outcome.Err -> finishWith(LlmEvent.Failed(a.error))
                is Outcome.Ok -> {
                    val (b, binder) = a.value
                    binding = b
                    watch = b.watch { finishWith(LlmEvent.Failed(it)) }
                    val cb = object : ILlmStreamCallback.Stub() {
                        override fun onPiece(text: String?) { if (!over.get()) trySend(LlmEvent.Piece(text.orEmpty())) }
                        override fun onDone(text: String?, statsJson: String?) {
                            val stats = runCatching { InferApi.decode(LlmStats.serializer(), statsJson.orEmpty()) }.getOrNull()
                            finishWith(stats?.let { LlmEvent.Done(text.orEmpty(), it) } ?: LlmEvent.Failed(MurmurError.Internal("payload")))
                        }
                        override fun onError(errorJson: String?) = finishWith(LlmEvent.Failed(InferApi.decodeError(errorJson.orEmpty())))
                    }
                    val json = InferApi.encode(LlmRequest.serializer(), request)
                    val t = timeouts()
                    val started = settle(BinderCall.LLM_GENERATE, b, connector.blocking(t.scaled(t.callMs)) { svc(binder).generate(json, cb) })
                    if (started is Outcome.Err) finishWith(LlmEvent.Failed(started.error))
                }
            }
            awaitClose {
                if (!over.get()) {
                    // The collector went away mid-generation: stop the decode in :llm.
                    binding?.binder?.let { bi -> connector.scope.launch(Dispatchers.IO) { runCatching { svc(bi).cancel() } } }
                }
            }
        } finally {
            watch?.let { w -> binding?.unwatch(w) }
            connector.endUse()
        }
    }.buffer(Channel.UNLIMITED)

    /** Any thread, never blocks: asks :llm to stop the running generation (it answers onDone with STOP_CANCELLED). */
    override fun cancel() {
        if (!connector.isConnected) return
        connector.scope.launch { sync(BinderCall.CANCEL, connectIfNeeded = false) { svc(it).cancel() } }
    }

    override suspend fun unload() {
        sync(BinderCall.UNLOAD, connectIfNeeded = false) { svc(it).unload() }
        loaded = null
    }

    override suspend fun bench(nPrompt: Int, nGen: Int, reps: Int): Outcome<SyntheticBench> {
        val timeoutMs = timeouts().benchMs(nPrompt, nGen, reps)
        val r = result(BinderCall.LLM_BENCH, timeoutMs, { InferApi.decode(SyntheticBench.serializer(), it) }) { b, cb ->
            svc(b).bench(nPrompt, nGen, reps, cb)
        }
        val spec = loaded
        if (r is Outcome.Ok && spec != null && r.value.ppTps > 0) benchPrefill[spec.artifactId to spec.unit] = r.value.ppTps
        return r
    }
}

/**
 * The real [Engines] of the :main process. [timeouts] is read at every call (Settings.debugTimeoutScale can change);
 * [prefillTps] = the benchmark store's prefill speed of (artifact, unit), when known.
 */
class BinderEngines(
    bindContext: () -> Context,
    timeouts: () -> BinderTimeouts,
    prefillTps: (ArtifactId, ComputeUnit) -> Double? = { _, _ -> null },
) : Engines {
    constructor(bindContext: () -> Context, timeouts: BinderTimeouts = BinderTimeouts()) : this(bindContext, { timeouts })

    val asrClient = AsrClient(bindContext, timeouts)
    val llmClient = LlmClient(bindContext, timeouts, prefillTps)
    override val asr: AsrEngine get() = asrClient
    override val ocr: OcrService get() = asrClient
    override val asrHost: InferenceHost get() = asrClient
    override val llm: LlmEngine get() = llmClient
    override val llmHost: InferenceHost get() = llmClient
}
