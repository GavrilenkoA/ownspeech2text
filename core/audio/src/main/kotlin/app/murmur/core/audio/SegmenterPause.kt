package app.murmur.core.audio

/**
 * The [Segmenter] of the :asr process (asr-ocr spike A6, `scripts/seg_stream.py`, same cut points on the spike's
 * long clips: `SegmenterParityTest`). All arithmetic is in session samples at [sampleRate]; frames are consecutive and
 * [SegmenterConfig.frameSamples] long.
 *
 * Rules, per frame with probability p:
 *  - p >= speechThreshold is speech: it opens a segment if none is open (start = frame start - preRoll, never before
 *    sample 0 or the end of the previous segment), resets the silence run and moves the last speech end.
 *  - p < silenceThreshold while speaking adds the frame to the silence run; frames in between change nothing
 *    (hysteresis, as Silero's own iterator and the spike).
 *  - A silence run >= minSilence (>= softSilence once the open segment is >= softAfter long) closes the segment at
 *    min(last speech end + postRoll, now). A segment with less than minSpeech of speech is dropped instead.
 *  - A segment that reaches hardMax is cut at the centre of the lowest-probability frame of the last forcedCutSearch
 *    (earliest on ties); the next segment starts exactly there (no overlap: the spike's overlap + text merge lost or
 *    doubled words, and the timestamp merge for Parakeet is an open item, so forced cuts stay a last resort).
 *  - [flush] closes the open segment at the given end (stop tap), subject to the same minSpeech rule.
 *
 * [speechMs] counts speech frames of emitted segments plus the open one (a dropped segment's speech is removed).
 * Not thread-safe; one instance per session.
 */
class PauseSegmenter(
    val config: SegmenterConfig = SegmenterConfig(),
    private val sampleRate: Int = PcmFormat.SAMPLE_RATE,
) : Segmenter {
    private val frame = config.frameSamples.toLong()
    private val minSilence = samples(config.minSilenceMs)
    private val softSilence = samples(config.softSilenceMs)
    private val softAfter = samples(config.softAfterMs)
    private val preRoll = samples(config.preRollMs)
    private val postRoll = samples(config.postRollMs)
    private val hardMax = samples(config.hardMaxMs)
    private val search = samples(config.forcedCutSearchMs)
    private val minSpeech = samples(config.minSpeechMs)

    // Recent frames of the open segment, for the forced cut: probability and end sample, newest last.
    private val ringSize = (search / frame + 2).toInt()
    private val ringProb = FloatArray(ringSize)
    private val ringEnd = LongArray(ringSize)
    private val ringSpeech = BooleanArray(ringSize)
    private var ringCount = 0
    private var ringHead = 0

    private var index = 0
    private var prevEnd = 0L
    private var segStart = -1L
    private var lastSpeechEnd = 0L
    private var silenceRun = 0L
    private var openSpeech = 0L
    private var committedSpeech = 0L

    /** True from a segment's first speech frame until it closes (a forced cut keeps it true). */
    var speaking: Boolean = false
        private set

    /** Start sample of the open segment, or null. */
    val openStartSample: Long? get() = if (segStart >= 0) segStart else null

    /** End sample of the last speech frame seen. */
    val lastSpeechEndSample: Long get() = lastSpeechEnd

    override val speechMs: Long get() = toMs(committedSpeech + openSpeech)

    override fun onFrame(probability: Float, frameEndSample: Long): Segment? {
        val now = frameEndSample
        val isSpeech = probability >= config.speechThreshold
        if (isSpeech) {
            if (segStart < 0) {
                segStart = maxOf(0L, now - frame - preRoll, prevEnd)
                ringCount = 0
            }
            speaking = true
            silenceRun = 0
            lastSpeechEnd = now
            openSpeech += frame
        } else if (probability < config.silenceThreshold && speaking) {
            silenceRun += frame
        }
        if (segStart < 0) return null
        push(probability, now, isSpeech)

        val length = now - segStart
        val need = if (length >= softAfter) softSilence else minSilence
        if (speaking && silenceRun >= need) {
            return close(minOf(lastSpeechEnd + postRoll, now), forced = false)
        }
        if (length >= hardMax) return forcedCut(now)
        return null
    }

    override fun flush(endSample: Long): Segment? {
        if (segStart < 0) return null
        return close(maxOf(endSample, segStart), forced = false)
    }

    private fun close(end: Long, forced: Boolean): Segment? {
        val speech = openSpeech
        val seg = if (speech >= minSpeech) {
            committedSpeech += speech
            Segment(index++, segStart, end, forced, toMs(speech))
        } else null
        prevEnd = maxOf(prevEnd, end)
        segStart = -1
        speaking = false
        silenceRun = 0
        openSpeech = 0
        ringCount = 0
        return seg
    }

    private fun forcedCut(now: Long): Segment? {
        val from = maxOf(now - search, segStart)
        var best = -1
        var bestProb = Float.MAX_VALUE
        for (i in 0 until ringCount) {
            val slot = slot(i)
            if (ringEnd[slot] > from && ringProb[slot] < bestProb) {
                bestProb = ringProb[slot]
                best = i
            }
        }
        val cut = ringEnd[slot(best)] - frame / 2
        // Frames after the cut frame belong to the next segment (their speech, and the forced-cut search of later cuts).
        val tail = ringCount - best - 1
        val tailProb = FloatArray(tail) { ringProb[slot(best + 1 + it)] }
        val tailEnd = LongArray(tail) { ringEnd[slot(best + 1 + it)] }
        val tailSpeech = BooleanArray(tail) { ringSpeech[slot(best + 1 + it)] }
        val moved = tailSpeech.count { it } * frame
        val run = silenceRun
        openSpeech -= moved
        val seg = close(cut, forced = true)
        segStart = cut
        speaking = true
        silenceRun = run
        openSpeech = moved
        for (i in 0 until tail) push(tailProb[i], tailEnd[i], tailSpeech[i])
        return seg
    }

    private fun slot(i: Int): Int = Math.floorMod(ringHead - ringCount + i, ringSize)

    private fun push(p: Float, end: Long, speech: Boolean) {
        ringProb[ringHead] = p
        ringEnd[ringHead] = end
        ringSpeech[ringHead] = speech
        ringHead = (ringHead + 1) % ringSize
        if (ringCount < ringSize) ringCount++
    }

    private fun samples(ms: Int): Long = ms.toLong() * sampleRate / 1000
    private fun toMs(samples: Long): Long = samples * 1000 / sampleRate
}
