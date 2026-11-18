package app.murmur.core.dictation

/** Every timeout of the dictation flow (ARCHITECTURE.md 5.3). Defaults from DESIGN 5.3/5.5 and the spikes. */
data class DictationConfig(
    /** Tap -> first audio buffer; spike: 8-90 ms FGS + 67-209 ms first buffer. */
    val micStartTimeoutMs: Long = 2_000,
    /** D11 / BRIEF flow 2: screenshot 0.5-1 s after start. Must be >= 340 ms after any other capture (rate limit). */
    val screenshotDelayMs: Long = 600,
    /** OCR result later than this is ignored (cleanup runs without screen). */
    val ocrBudgetMs: Long = 4_000,
    /** DESIGN 5.5: warn at 4:30, auto-stop at 5:00. */
    val warnAtMs: Long = 270_000,
    val maxDurationMs: Long = 300_000,
    /** DESIGN 5.3: Transcribing appears only if text is not ready within 300 ms of the stop. */
    val transcribingLabelMs: Long = 300,
    /** ASR tail: base + factor x tail audio (NPU 20 s window = one encoder call; CPU fallback slower). */
    val asrFinalBaseMs: Long = 4_000,
    val asrFinalPerAudioSec: Double = 0.5,
    /** "Didn't catch that" below this much VAD speech (DESIGN 5.5). */
    val minSpeechMs: Long = 500,
    /** ADR-015: adaptive cleanup timeout instead of DESIGN's fixed 6 s (a 60 s dictation decodes ~300 tokens). */
    val llmBaseMs: Long = 4_000,
    val llmMaxMs: Long = 20_000,
    val llmSafety: Double = 1.5,
    val deliveryTimeoutMs: Long = 2_000,
    /** Result pill durations (DESIGN 5.3). */
    val insertedShowMs: Long = 1_200,
    val copiedShowMs: Long = 4_000,
    val errorShowMs: Long = 6_000,
    /** D5 idle timeout default (Settings.idleUnloadSec overrides). */
    val idleUnloadMs: Long = 60_000,
    /** Spare memory to keep when loading the LLM next to ASR (ARCHITECTURE.md 7). */
    val llmMemoryMarginMb: Int = 600,
) {
    fun asrFinalTimeoutMs(tailAudioMs: Long): Long = asrFinalBaseMs + (tailAudioMs / 1000.0 * asrFinalPerAudioSec * 1000).toLong()

    /** expected = prefill + decode from the latest benchmark of the active artifact/unit (null = no data). */
    fun llmTimeoutMs(expectedMs: Long?): Long =
        if (expectedMs == null) llmMaxMs else (llmBaseMs + expectedMs * llmSafety).toLong().coerceAtMost(llmMaxMs)
}
