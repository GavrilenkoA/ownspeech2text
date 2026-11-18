package app.murmur.core.engine

import app.murmur.core.audio.PcmChunk
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * One session interface for streaming and batch models (D4). The :asr process picks the adapter from
 * the catalog traits: native streaming (sherpa OnlineRecognizer) or VAD-segmented incremental decoding
 * (Silero VAD + Segmenter + OfflineRecognizer). Nothing in :main knows which one runs.
 */
interface AsrEngine {
    /** Load ASR + VAD (VAD only for batch models). May be called while audio already flows (session buffers). */
    suspend fun load(asr: LoadSpec, vad: LoadSpec?): Outcome<LoadInfo>
    /** Start a session; [accept] may be called before [load] completes: audio is buffered, never dropped. */
    fun startSession(options: AsrSessionOptions): AsrSession
    suspend fun unload()
}

@Serializable
data class AsrSessionOptions(
    /** Language hint for HINT models ("ru", "en"); null = auto. */
    val language: String? = null,
    val emitPartials: Boolean = true,
)

interface AsrSession {
    /** Called from the capture side with 16 kHz mono PCM16; never blocks (copies into a ring). */
    fun accept(chunk: PcmChunk)
    val events: Flow<AsrEvent>
    /** Stop tap: decode only the open tail (no whole-utterance re-decode, A6) and return the full text. */
    suspend fun finish(): Outcome<Transcript>
    fun cancel()
}

@Serializable
sealed interface AsrEvent {
    /** Replaceable hypothesis of the uncommitted tail (streaming models / preview). */
    @Serializable @kotlinx.serialization.SerialName("partial")
    data class Partial(val text: String) : AsrEvent
    /** Immutable segment text, in order. Times relative to the first captured sample. */
    @Serializable @kotlinx.serialization.SerialName("committed")
    data class Committed(val index: Int, val text: String, val startMs: Long, val endMs: Long, val language: String?) : AsrEvent
    /** VAD speech/silence edges (bubble "Didn't catch that" check, speaking minutes). */
    @Serializable @kotlinx.serialization.SerialName("speech")
    data class Speech(val speaking: Boolean, val atMs: Long) : AsrEvent
    @Serializable @kotlinx.serialization.SerialName("failure")
    data class Failure(val error: MurmurError) : AsrEvent
}

@Serializable
data class Transcript(
    val text: String,
    val language: String?,
    val segments: Int,
    val audioMs: Long,
    /** VAD speech time (0 for streaming models without VAD). */
    val speechMs: Long,
    /** Stop tap -> text, measured inside :asr. */
    val tailDecodeMs: Long,
    val unit: app.murmur.core.model.ComputeUnit,
)
