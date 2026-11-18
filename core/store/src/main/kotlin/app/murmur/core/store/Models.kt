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
    /** First load after download: QNN context / prefix snapshot preparation ("Preparing for NPU"). */
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
)

@Serializable
sealed interface BenchmarkResultData {
    @Serializable @kotlinx.serialization.SerialName("asr")
    data class Asr(
        val rtfx: Double,            // audio s / processing s ("14x faster than real time")
        val loadMsCold: Long,
        val loadMsWarm: Long?,
        val peakMemMb: Long,
        val ranOn: String,           // unit actually used (may differ from the badge)
        val perClip: List<ClipResult>,
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
    ) : BenchmarkResultData

    @Serializable @kotlinx.serialization.SerialName("ocr")
    data class Ocr(val msPerScreenshot: Long, val boxes: Int, val loadMsCold: Long, val peakMemMb: Long, val ranOn: String) : BenchmarkResultData
}

@Serializable
data class ClipResult(val clip: String, val audioMs: Long, val processMs: Long, val wer: Double?)

interface BenchmarkRepository {
    fun latest(id: ArtifactId): Flow<List<BenchmarkRecord>>
    suspend fun save(record: BenchmarkRecord)
}
