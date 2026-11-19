package app.murmur.core.audio

/**
 * VAD-segmented incremental decoding for batch ASR models (D4; asr-ocr spike A6, measured: pause cuts lose
 * no words, whole-clip decoding is worse, forced cuts lose words). Runs in :asr next to the VAD model.
 */
data class SegmenterConfig(
    /** Silero v5: 512-sample frames at 16 kHz (32 ms). */
    val frameSamples: Int = 512,
    val speechThreshold: Float = 0.5f,
    val silenceThreshold: Float = 0.35f,
    /** Cut after this much silence ... */
    val minSilenceMs: Int = 500,
    /** ... or after this much once the open segment is longer than [softAfterMs]. */
    val softSilenceMs: Int = 150,
    val softAfterMs: Int = 14_000,
    val preRollMs: Int = 300,
    val postRollMs: Int = 300,
    /** Forced cut (last resort) = model window - 0.5 s; prefer the lowest-VAD frame in the last 2 s. */
    val hardMaxMs: Int = 19_500,
    val forcedCutSearchMs: Int = 2_000,
    /** Segments with less speech than this are dropped (clicks, breaths). */
    val minSpeechMs: Int = 250,
) {
    companion object {
        /** Hard max from the artifact's fixed window (Artifact.maxWindowSec); null window -> 19.5 s anyway. */
        fun forWindow(maxWindowSec: Double?): SegmenterConfig =
            SegmenterConfig(hardMaxMs = ((maxWindowSec ?: 20.0) * 1000 - 500).toInt().coerceAtMost(29_500))
    }
}

/**
 * A closed speech segment in session samples (pre/post-roll included). [forced] = cut at the hard max instead of a
 * pause. [speechMs] = VAD speech inside it (frames with probability >= the speech threshold).
 */
data class Segment(val index: Int, val startSample: Long, val endSample: Long, val forced: Boolean, val speechMs: Long = 0)

interface VadModel {
    /** Speech probability of one frame of [SegmenterConfig.frameSamples] float samples in [-1, 1]. */
    fun probability(frame: FloatArray): Float
    fun reset()
}

/**
 * Pure state machine over per-frame VAD probabilities (no model inside, so it is unit-testable with
 * synthetic probability sequences). [PauseSegmenter] implements it; the cut rules above are the spec.
 */
interface Segmenter {
    /** Feed one frame probability; returns a segment when one closes. */
    fun onFrame(probability: Float, frameEndSample: Long): Segment?
    /** Stop tap: close the open segment (if it contains speech). */
    fun flush(endSample: Long): Segment?
    /** Total speech duration seen so far (Dashboard "speaking minutes", NoSpeech check). */
    val speechMs: Long
}
