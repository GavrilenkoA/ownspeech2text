package app.murmur.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * The session's PCM from sample 0 on (ARCHITECTURE 4.3 (c)): one writer (the capture thread) appends 20 ms frames;
 * any number of collectors of [chunks] get every frame from the first one, also when they attach late (the ASR
 * session attaches after its model loaded; a re-bound `:asr` replays from 0). Bounded by [maxSamples]
 * (5 min = 9.6 MB of PCM16 by default); appends past the bound are refused and counted in [droppedSamples].
 */
class PcmSessionBuffer(val maxSamples: Long = DEFAULT_MAX_SAMPLES) {
    private data class Progress(val frames: Int, val closed: Boolean)

    private val lock = Any()
    private val frames = ArrayList<PcmChunk>()
    private val progress = MutableStateFlow(Progress(0, false))
    @Volatile private var samples = 0L

    /** Samples appended so far (the session clock). */
    val totalSamples: Long get() = samples
    @Volatile var droppedSamples: Long = 0
        private set
    val isClosed: Boolean get() = progress.value.closed

    /**
     * Copies [count] samples of [data] as one chunk starting at [totalSamples]. Returns false (and keeps nothing)
     * when the buffer is closed or the chunk would pass [maxSamples].
     */
    fun append(data: ShortArray, count: Int = data.size): Boolean {
        require(count in 0..data.size) { "count" }
        if (count == 0) return true
        synchronized(lock) {
            if (progress.value.closed) return false
            if (samples + count > maxSamples) {
                droppedSamples += count
                return false
            }
            frames += PcmChunk(data.copyOf(count), samples)
            samples += count
            progress.value = Progress(frames.size, false)
        }
        return true
    }

    /** No more frames: every collector finishes after the last one. Idempotent. */
    fun close() {
        synchronized(lock) { progress.value = progress.value.copy(closed = true) }
    }

    /** Every chunk from sample 0, then live ones as they arrive; completes after [close]. Cold, one cursor per collector. */
    fun chunks(): Flow<PcmChunk> = flow {
        var next = 0
        while (true) {
            val p = progress.first { it.frames > next || it.closed }
            while (next < p.frames) emit(frame(next++))
            if (p.closed && next >= progress.value.frames) break
        }
    }

    /** The whole session so far as one array (Retry from memory, tests). */
    fun toShortArray(): ShortArray = synchronized(lock) {
        val out = ShortArray(samples.toInt())
        for (f in frames) f.samples.copyInto(out, f.startSample.toInt())
        out
    }

    private fun frame(i: Int): PcmChunk = synchronized(lock) { frames[i] }

    companion object {
        /** 5 min (the MAX timer, ARCHITECTURE 5.3) plus 2 s for the stop to land. */
        val DEFAULT_MAX_SAMPLES: Long = PcmFormat.msToSamples(5 * 60_000L + 2_000L)
    }
}

/**
 * Cuts reads of any size into whole [frameSamples] frames (20 ms by default) so that every chunk on the pipe, in the
 * session buffer and in the encoder has the same size; the last partial frame is flushed at the end.
 */
class FrameAssembler(val frameSamples: Int = PcmFormat.FRAME_SAMPLES, private val onFrame: (ShortArray, Int) -> Unit) {
    private val frame = ShortArray(frameSamples)
    private var fill = 0

    fun push(data: ShortArray, offset: Int = 0, count: Int = data.size - offset) {
        require(offset >= 0 && count >= 0 && offset + count <= data.size) { "range" }
        var i = offset
        val end = offset + count
        while (i < end) {
            val n = minOf(frameSamples - fill, end - i)
            data.copyInto(frame, fill, i, i + n)
            fill += n
            i += n
            if (fill == frameSamples) {
                onFrame(frame, frameSamples)
                fill = 0
            }
        }
    }

    /** Emits the partial frame left over, if any. */
    fun flush() {
        if (fill > 0) onFrame(frame, fill)
        fill = 0
    }
}

/**
 * The start check of ARCHITECTURE 5.2 row 11 / android-platform 12 (ARMING): capture counts as started at the first
 * non-zero sample; a whole first second of exact digital zeros means the microphone is blocked (privacy toggle,
 * another app, a dead HAL) -> MicSilenced. Real microphones never deliver exact zeros for a second.
 */
class MicStartGate(val silentSamples: Long = PcmFormat.SAMPLE_RATE.toLong()) {
    enum class State { PENDING, STARTED, SILENT }

    var state: State = State.PENDING
        private set
    private var zeros = 0L

    fun push(data: ShortArray, count: Int = data.size): State {
        if (state != State.PENDING) return state
        for (i in 0 until count) {
            if (data[i].toInt() != 0) {
                state = State.STARTED
                return state
            }
        }
        zeros += count
        if (zeros >= silentSamples) state = State.SILENT
        return state
    }
}
