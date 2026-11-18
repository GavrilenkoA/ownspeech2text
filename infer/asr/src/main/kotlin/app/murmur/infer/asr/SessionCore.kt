package app.murmur.infer.asr

import app.murmur.core.audio.PauseSegmenter
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.Segment
import app.murmur.core.audio.VadModel
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.util.concurrent.ConcurrentLinkedQueue

/** Text of one decode; [language] when the model reports it (Whisper), else null. */
data class Decoded(val text: String, val language: String?)

/** Batch recognizer: one call per closed segment (sherpa OfflineRecognizer; fakes in tests). */
fun interface SegmentDecoder {
    fun decode(samples: FloatArray, language: String?): Decoded
}

/** Native streaming recognizer for one session (sherpa OnlineRecognizer + OnlineStream; fakes in tests). */
interface StreamDecoder {
    /** Feed 16 kHz samples and decode what is ready; returns the current hypothesis. */
    fun accept(samples: FloatArray): String
    /** Tail padding + input finished + decode the rest; returns the final text. */
    fun finish(): String
    fun release()
}

/** What the session reports at the stop tap. */
data class FinalText(val text: String, val language: String?, val segments: Int, val speechMs: Long)

/** Turns the session's audio into events and the final text; created when the model is loaded. Engine thread only. */
interface SessionProcessor {
    val unit: ComputeUnit
    /** [samples] continue the session audio without gaps (16 kHz, [-1, 1]). */
    fun accept(samples: FloatArray)
    fun finish(): FinalText
    fun release()
}

/** Session audio kept for segment decoding: samples from [start] on, trimmed as segments close. */
class SampleBuffer(initialCapacity: Int = PcmFormat.SAMPLE_RATE * 4) {
    private var data = FloatArray(initialCapacity)
    private var size = 0
    /** Session sample index of data[0]. */
    var start = 0L
        private set
    val end: Long get() = start + size

    fun append(samples: FloatArray) {
        if (size + samples.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + samples.size))
        System.arraycopy(samples, 0, data, size, samples.size)
        size += samples.size
    }

    fun slice(from: Long, to: Long): FloatArray {
        require(from >= start && to <= end && from <= to) { "slice outside the buffer" }
        return data.copyOfRange((from - start).toInt(), (to - start).toInt())
    }

    /** Drops samples before [before] (never past the end). */
    fun trim(before: Long) {
        val drop = (minOf(before, end) - start).toInt()
        if (drop <= 0) return
        System.arraycopy(data, drop, data, 0, size - drop)
        size -= drop
        start += drop
    }
}

/**
 * Batch models (D4, A6): Silero VAD per 512-sample frame -> [PauseSegmenter] -> each closed segment decoded at once
 * -> [AsrEvent.Committed]. At the stop tap only the open tail is decoded (ADR-025: no whole-utterance re-decode).
 */
class SegmentingProcessor(
    override val unit: ComputeUnit,
    private val vad: VadModel,
    private val segmenter: PauseSegmenter,
    private val decoder: SegmentDecoder,
    private val language: String?,
    private val emit: (AsrEvent) -> Unit,
) : SessionProcessor {
    private val frameSize = segmenter.config.frameSamples
    private val preRoll = segmenter.config.preRollMs.toLong() * PcmFormat.SAMPLE_RATE / 1000
    private val audio = SampleBuffer()
    private val frame = FloatArray(frameSize)
    private var frameFill = 0
    private var framesEnd = 0L
    private var wasSpeaking = false
    private val texts = ArrayList<String>()
    private val languages = ArrayList<String>()
    private var segments = 0

    override fun accept(samples: FloatArray) {
        audio.append(samples)
        var i = 0
        while (i < samples.size) {
            val n = minOf(frameSize - frameFill, samples.size - i)
            System.arraycopy(samples, i, frame, frameFill, n)
            frameFill += n
            i += n
            if (frameFill == frameSize) {
                frameFill = 0
                framesEnd += frameSize
                onFrame(vad.probability(frame))
            }
        }
        // Keep the open segment, or the pre-roll that a segment opening on the next frame may reach back into.
        audio.trim(segmenter.openStartSample ?: (framesEnd - preRoll))
    }

    private fun onFrame(p: Float) {
        val closed = segmenter.onFrame(p, framesEnd)
        if (segmenter.speaking && !wasSpeaking) {
            emit(AsrEvent.Speech(true, PcmFormat.samplesToMs(framesEnd - frameSize)))
        }
        if (!segmenter.speaking && wasSpeaking) {
            emit(AsrEvent.Speech(false, PcmFormat.samplesToMs(segmenter.lastSpeechEndSample)))
        }
        wasSpeaking = segmenter.speaking
        if (closed != null) decode(closed)
    }

    private fun decode(seg: Segment) {
        val d = decoder.decode(audio.slice(maxOf(seg.startSample, audio.start), seg.endSample), language)
        segments++
        val text = d.text.trim()
        if (text.isEmpty()) return
        texts += text
        d.language?.let { languages += it }
        emit(AsrEvent.Committed(seg.index, text, PcmFormat.samplesToMs(seg.startSample), PcmFormat.samplesToMs(seg.endSample), d.language ?: language))
    }

    override fun finish(): FinalText {
        val tail = segmenter.flush(audio.end)
        if (wasSpeaking) emit(AsrEvent.Speech(false, PcmFormat.samplesToMs(segmenter.lastSpeechEndSample)))
        wasSpeaking = false
        if (tail != null) decode(tail)
        val lang = language ?: languages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return FinalText(texts.joinToString(" "), lang, segments, segmenter.speechMs)
    }

    override fun release() {
        vad.reset()
    }
}

/** Native streaming models (Nemotron, T-one): partial hypotheses while audio flows, the final text at the stop tap. */
class StreamingProcessor(
    override val unit: ComputeUnit,
    private val stream: StreamDecoder,
    private val language: String?,
    private val emitPartials: Boolean,
    private val emit: (AsrEvent) -> Unit,
) : SessionProcessor {
    private var last = ""

    override fun accept(samples: FloatArray) {
        val text = stream.accept(samples).trim()
        if (emitPartials && text != last && text.isNotEmpty()) emit(AsrEvent.Partial(text))
        last = text
    }

    override fun finish(): FinalText {
        val text = stream.finish().trim()
        return FinalText(text, language, if (text.isEmpty()) 0 else 1, 0)
    }

    override fun release() = stream.release()
}

/**
 * One dictation's session, independent of Android (the binder/pipe glue is [AsrSessionBinder]). Audio arrives from
 * the pipe reader thread through [offer]/[endOfAudio] and is queued; everything else runs on the engine thread. The
 * queue holds every sample from the first one on until a model is [attach]ed, so nothing is lost while loading (the
 * :main side also keeps the PCM for crash replay, ADR-024).
 */
class AsrSessionCore(
    private val options: AsrSessionOptions,
    private val emit: (AsrEvent) -> Unit,
    val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val inbox = ConcurrentLinkedQueue<ShortArray>()
    @Volatile private var eofAtMs = -1L
    @Volatile var samplesIn = 0L
        private set
    var samplesProcessed = 0L
        private set

    private var processor: SessionProcessor? = null
    private var failure: MurmurError? = null
    private var finishAtMs = -1L
    private var reply: ((Outcome<Transcript>) -> Unit)? = null
    var closed = false
        private set

    private var decodeFailure: MurmurError? = null

    val language: String? get() = options.language
    val emitPartials: Boolean get() = options.emitPartials
    val attached: Boolean get() = processor != null
    /** Events of this session's processor; nothing is sent once the session is closed. */
    val emitter: (AsrEvent) -> Unit = { e -> if (!closed) emit(e) }

    // --- pipe reader thread
    fun offer(chunk: ShortArray) {
        if (chunk.isEmpty()) return
        samplesIn += chunk.size
        inbox.add(chunk)
    }

    fun endOfAudio() {
        if (eofAtMs < 0) eofAtMs = nowMs()
    }

    // --- engine thread
    fun attach(p: SessionProcessor) {
        if (closed || processor != null || decodeFailure != null) { p.release(); return }
        processor = p
        failure = null
        pump()
    }

    /**
     * A load for this session failed. The session stays open with all its audio: a later successful load (the
     * executor's fallback artifact, ADR-050) attaches to it; a [requestFinish] before that answers [e].
     */
    fun loadFailed(e: MurmurError) {
        if (closed || processor != null) return
        failure = e
        emit(AsrEvent.Failure(e))
        tryComplete()
    }

    fun pump() {
        if (decodeFailure != null) return tryComplete()
        val p = processor ?: return tryComplete()
        try {
            while (true) {
                val chunk = inbox.poll() ?: break
                val f = FloatArray(chunk.size) { chunk[it] / 32768f }
                p.accept(f)
                samplesProcessed += chunk.size
            }
        } catch (e: RuntimeException) {
            failDecoding(e)
            return
        }
        tryComplete()
    }

    /** [atMs] = when the stop arrived (the binder call), not when the engine thread got to it. */
    fun requestFinish(atMs: Long = nowMs(), cb: (Outcome<Transcript>) -> Unit) {
        if (closed || reply != null) return
        reply = cb
        finishAtMs = atMs
        pump()
    }

    fun cancel() {
        if (closed) return
        closed = true
        inbox.clear()
        processor?.release()
        processor = null
    }

    private fun failDecoding(e: RuntimeException) {
        val err = MurmurError.AsrFailed("decode:${e.javaClass.simpleName}")
        decodeFailure = err
        emit(AsrEvent.Failure(err))
        processor?.release()
        processor = null
        tryComplete()
    }

    private fun tryComplete() {
        val cb = reply ?: return
        if (closed) return
        decodeFailure?.let { finishWith(cb, Outcome.Err(it)); return }
        val p = processor ?: run { failure?.let { finishWith(cb, Outcome.Err(it)) }; return }
        if (eofAtMs < 0 || inbox.isNotEmpty()) return
        val out = try {
            p.finish()
        } catch (e: RuntimeException) {
            finishWith(cb, Outcome.Err(MurmurError.AsrFailed("decode:${e.javaClass.simpleName}")))
            return
        }
        val tail = nowMs() - maxOf(finishAtMs, eofAtMs)
        finishWith(cb, Outcome.Ok(Transcript(out.text, out.language, out.segments, PcmFormat.samplesToMs(samplesProcessed), out.speechMs, tail, p.unit)))
    }

    private fun finishWith(cb: (Outcome<Transcript>) -> Unit, result: Outcome<Transcript>) {
        reply = null
        closed = true
        processor?.release()
        processor = null
        cb(result)
    }
}
