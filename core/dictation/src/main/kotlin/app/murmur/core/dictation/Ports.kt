package app.murmur.core.dictation

import app.murmur.core.audio.PcmChunk
import app.murmur.core.catalog.AsrTraits
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.store.AudioRef
import app.murmur.core.store.InsertMethod
import app.murmur.core.store.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** :platform:audio. Mic FGS + AudioRecord in :main (platform spike), PCM also encoded to the history file. */
interface AudioCapture {
    suspend fun start(session: SessionId, keepRecording: Boolean): Outcome<CaptureHandle>
}

interface CaptureHandle {
    /** Every sample from the first buffer on, replayed to late collectors (the ASR session may attach after load). */
    val chunks: Flow<PcmChunk>
    /** 7 bar heights 0..1 at ~30 Hz for the bubble (LevelMeter). */
    val levels: StateFlow<FloatArray>
    suspend fun stop(): CaptureResult
    fun cancel()
}

data class CaptureResult(val durationMs: Long, val audio: AudioRef?, val peakAbs: Int, val silenced: Boolean)

/** :platform:a11y. Reads the target at press time, captures the window screenshot, re-checks before delivery. */
interface TargetReader {
    suspend fun snapshot(): A11ySnapshot?
    /** takeScreenshotOfWindow(target) with the fallbacks of ARCHITECTURE.md 9.1; Err(SecureWindow) for FLAG_SECURE. */
    suspend fun screenshot(target: A11ySnapshot): Outcome<ScreenImage>
    /** Current focus right before insertion (focus may have moved or become a password field). */
    suspend fun current(): A11ySnapshot?
}

/** :platform:a11y. Insertion ladder commitText -> ACTION_PASTE -> ACTION_SET_TEXT, verified by getSurroundingText. */
interface Inserter {
    suspend fun insert(target: A11ySnapshot, text: String): InsertResult
    /** ClipData with EXTRA_IS_SENSITIVE=false (D: always copied as well, BRIEF flow 7). */
    fun copyToClipboard(text: String): Boolean
}

sealed interface InsertResult {
    data class Inserted(val method: InsertMethod) : InsertResult
    data class Failed(val error: MurmurError) : InsertResult
}

fun interface Feedback {
    fun haptic(kind: HapticKind)
}

/**
 * Resolves the selected models into load specs (catalog + install state + unit choice + benchmark).
 * Implemented in :data:models.
 */
interface ModelResolver {
    suspend fun resolve(settings: Settings): Outcome<ActiveModels>
}

data class ActiveModels(
    val asr: LoadSpec,
    val asrTraits: AsrTraits,
    val vad: LoadSpec?,
    val ocrDet: LoadSpec?,
    val ocrRec: LoadSpec?,
    val llm: ActiveLlm?,
)

data class ActiveLlm(
    val spec: LoadSpec,
    /** render.py TEMPLATES key and prompt profile from the catalog. */
    val template: String,
    val profile: String,
    val memoryMb: Int,
    /** Expected prefill+decode ms for a typical dictation from the benchmark, for the adaptive timeout. */
    val expectedMsPer100Words: Long?,
)

/** ActivityManager.MemoryInfo in :main; decides whether the LLM can load next to the ASR (ARCHITECTURE.md 7). */
fun interface MemoryGate {
    fun availableMb(): Long
}

/**
 * How dictated text joins the text before the cursor (WP-11, pure, unit-tested): a leading space when
 * [beforeCursor] ends with a letter, digit or closing punctuation and [text] starts with a letter or digit;
 * nothing at the start of a field or after whitespace/opening brackets. Lowercases the first letter only when
 * the field context is mid-sentence (before ends without . ! ? or newline) AND the guard accepted the text.
 */
interface JoinPolicy {
    fun join(beforeCursor: String?, text: String): String
}
