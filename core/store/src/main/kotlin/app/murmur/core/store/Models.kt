package app.murmur.core.store

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Install state of one catalog artifact (Model hub row states, DESIGN 6.4). */
sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Queued(val wifiOnly: Boolean) : InstallState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long, val bytesPerSec: Long) : InstallState
    data class Paused(val bytesDone: Long, val bytesTotal: Long, val reason: MurmurError) : InstallState
    data object Verifying : InstallState
    data object Extracting : InstallState
    /**
     * After extraction: EnginePreparer proves a real load on each usable unit (+ a 1 s decode for ASR) and builds the
     * LLM prefix snapshot (ARCHITECTURE 8.5). DESIGN "Preparing for NPU".
     */
    data class Preparing(val progress: Float) : InstallState
    data class Installed(val dir: String, val bytesOnDisk: Long) : InstallState
    data class Failed(val error: MurmurError) : InstallState
}

interface InstalledModelsRepository {
    val states: StateFlow<Map<ArtifactId, InstallState>>
    fun state(id: ArtifactId): Flow<InstallState>
    suspend fun download(id: ArtifactId, wifiOnly: Boolean)
    suspend fun pause(id: ArtifactId)
    suspend fun delete(id: ArtifactId)
    suspend fun storage(): StorageInfo
}

data class StorageInfo(val modelsBytes: Long, val freeBytes: Long)

/** Benchmark results (stored per model, artifact, unit, app version and build fingerprint, DESIGN 6.4). */
@Serializable
data class BenchmarkRecord(
    val artifactId: ArtifactId,
    val unit: ComputeUnit,
    val appVersion: String,
    val buildFingerprint: String,
    val atEpochMs: Long,
    val result: BenchmarkResultData,
    /**
     * Measurement context (ARCHITECTURE 10 "record notes"), numbers and codes only: thermal status before/after,
     * the raw memory readings behind peakMemMb, wall time of the unit, LLM synthetic pp512/tg128 speeds and prefix
     * prefill, per-page OCR times. Keys: `BenchNotes` in :core:benchmark.
     */
    val notes: Map<String, String> = emptyMap(),
)

/**
 * [valid] = the correctness gate of ARCHITECTURE 10 passed (ASR: WER <= 20 % and <= CPU WER + 5 pp when a CPU record
 * exists; LLM: guard ACCEPT, non-empty, expected script, stop reason EOG). Invalid records are stored and shown
 * ("Results look wrong on NPU") but never used for unit selection (BenchLookup returns null).
 */
@Serializable
sealed interface BenchmarkResultData {
    val valid: Boolean
    /** Machine code when invalid ("wer_high", "empty", "early_stop", "guard_fallback", "wrong_script"). */
    val invalidReason: String?

    @Serializable @kotlinx.serialization.SerialName("asr")
    data class Asr(
        val rtfx: Double,            // audio ms / sum of decode ms; clips pushed as fast as accept() allows (not real time)
        val tailMs: Long,            // finish() latency of the last segment (what the user waits for)
        val loadMsCold: Long,
        val loadMsWarm: Long?,
        val peakMemMb: Long,
        val ranOn: String,           // unit actually used (may differ from the badge)
        val perClip: List<ClipResult>,
        override val valid: Boolean = true,
        override val invalidReason: String? = null,
    ) : BenchmarkResultData

    @Serializable @kotlinx.serialization.SerialName("llm")
    data class Llm(
        val decodeTps: Double,
        val prefillTps: Double,
        val ttftMs: Double,
        val e2eMs: Double,           // fixed cleanup case end to end
        val loadMsCold: Long,
        val peakMemMb: Long,
        val ranOn: String,
        val unitShare: Double?,
        /** Fixed-case guard action ("accept"/"fallback") and LlmStats.stopReason, inputs of the validity gate. */
        val guardAction: String = "accept",
        val stopReason: Int = 0,
        override val valid: Boolean = true,
        override val invalidReason: String? = null,
    ) : BenchmarkResultData

    @Serializable @kotlinx.serialization.SerialName("ocr")
    data class Ocr(
        val msPerScreenshot: Long, val boxes: Int, val loadMsCold: Long, val peakMemMb: Long, val ranOn: String,
        override val valid: Boolean = true,
        override val invalidReason: String? = null,
    ) : BenchmarkResultData
}

@Serializable
data class ClipResult(val clip: String, val audioMs: Long, val processMs: Long, val wer: Double?)

interface BenchmarkRepository {
    fun latest(id: ArtifactId): Flow<List<BenchmarkRecord>>
    suspend fun save(record: BenchmarkRecord)
}
