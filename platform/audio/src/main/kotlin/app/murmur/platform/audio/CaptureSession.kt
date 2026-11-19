package app.murmur.platform.audio

import app.murmur.core.audio.LevelMeter
import app.murmur.core.audio.PcmChunk
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.PcmMath
import app.murmur.core.audio.PcmSessionBuffer
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.dictation.CaptureResult
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the samples of one capture go (ARCHITECTURE 4.3): every frame the source produces is (b) handed to the history
 * [writer] (null when keepRecordings is off: no file), (c) kept in the session buffer that [chunks] replays from
 * sample 0 (the `:asr` pipe is fed from it), (d) pushed through the [LevelMeter] whose 30 Hz ticks become [levels].
 * Shared by the microphone capture and the debug WAV capture; free of Android APIs so that it is unit-tested on the JVM.
 *
 * Threading: [onFrame] is called by exactly one producer thread and never suspends (the writer only queues).
 * [stop] / [cancel] may be called from any thread, once or many times.
 */
internal class CaptureSession(
    private val writer: AudioWriter?,
    private val source: Source,
    private val meter: LevelMeter = LevelMeter(),
    maxSamples: Long = PcmSessionBuffer.DEFAULT_MAX_SAMPLES,
) : CaptureHandle {

    /** The producer: stops producing on [halt] and completes [finished] once its last [onFrame] returned. */
    interface Source {
        fun halt()
        val finished: CompletableDeferred<Unit>
    }

    private val buffer = PcmSessionBuffer(maxSamples)
    private val _levels = MutableStateFlow(FloatArray(meter.weights.size))
    private val _signals = MutableSharedFlow<CaptureSignal>(replay = SIGNAL_KINDS, extraBufferCapacity = SIGNAL_KINDS)
    private val signalled = HashSet<Class<out CaptureSignal>>()
    private val ended = AtomicBoolean(false)
    private val result = CompletableDeferred<CaptureResult>()
    @Volatile private var peak = 0
    @Volatile private var silenced = false
    private var lastTick = 0L
    private val endCallbacks = ArrayList<() -> Unit>()

    override val chunks: Flow<PcmChunk> = buffer.chunks()
    override val levels: StateFlow<FloatArray> = _levels.asStateFlow()
    /** Each kind of signal at most once per session, replayed to a collector that attaches after it happened. */
    override val signals: SharedFlow<CaptureSignal> = _signals.asSharedFlow()

    val totalSamples: Long get() = buffer.totalSamples
    val peakAbs: Int get() = peak

    /** Runs once when the session ends (stop or cancel), after the source finished: stops the FGS, releases callbacks. */
    fun doOnEnd(block: () -> Unit) = synchronized(endCallbacks) { endCallbacks += block }

    /** Producer thread only. Frames after the end, or past the buffer bound, are dropped everywhere (one truth). */
    fun onFrame(samples: ShortArray, count: Int) {
        if (ended.get() || count == 0) return
        if (!buffer.append(samples, count)) return
        writer?.write(samples, count)
        val p = PcmMath.peakAbs(samples, count)
        if (p > peak) peak = p
        val bars = meter.push(samples, count)
        if (meter.ticks != lastTick) {
            lastTick = meter.ticks
            _levels.value = bars
        }
    }

    /** Any thread. Deduplicated per kind; ignored after the end. */
    fun signal(s: CaptureSignal) {
        if (ended.get()) return
        synchronized(signalled) { if (!signalled.add(s.javaClass)) return }
        if (s is CaptureSignal.Silenced) silenced = true
        _signals.tryEmit(s)
    }

    override suspend fun stop(): CaptureResult {
        // Non-cancellable: once the stop began, the file is finished and the FGS released even if the caller goes away.
        if (ended.compareAndSet(false, true)) withContext(NonCancellable) {
            source.halt()
            source.finished.await()
            buffer.close()
            val audio: AudioRef? = writer?.close()
            runEndCallbacks()
            result.complete(
                CaptureResult(
                    durationMs = PcmFormat.samplesToMs(buffer.totalSamples),
                    audio = audio,
                    peakAbs = peak,
                    silenced = silenced,
                ),
            )
        }
        return result.await()
    }

    override fun cancel() {
        if (!ended.compareAndSet(false, true)) return
        source.halt()
        buffer.close()
        // Discard after the producer is done so that no frame reaches the writer afterwards; the writer also ignores
        // writes after discard, so an early discard is harmless.
        writer?.discard()
        source.finished.invokeOnCompletion {
            writer?.discard()
            runEndCallbacks()
        }
        result.complete(CaptureResult(PcmFormat.samplesToMs(buffer.totalSamples), null, peak, silenced))
    }

    private fun runEndCallbacks() {
        val callbacks = synchronized(endCallbacks) { endCallbacks.toList().also { endCallbacks.clear() } }
        callbacks.forEach { runCatching(it) }
    }

    private companion object {
        /** Silenced, Lost, CallStarted. */
        const val SIGNAL_KINDS = 3
    }
}
