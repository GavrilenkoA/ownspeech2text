package app.murmur.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a model runs. Order = default preference when several are available (D2: NPU first). */
@Serializable
enum class ComputeUnit {
    @SerialName("npu") NPU,
    @SerialName("gpu") GPU,
    @SerialName("cpu") CPU,
}

/** User choice per model in the hub (D2). [Auto] = best available by benchmark, else catalog order. */
@Serializable
sealed interface UnitPreference {
    @Serializable @SerialName("auto")
    data object Auto : UnitPreference

    @Serializable @SerialName("pinned")
    data class Pinned(val unit: ComputeUnit) : UnitPreference
}

/** Model role in the pipeline. Mirrors catalog "kind". */
@Serializable
enum class ModelKind {
    @SerialName("asr") ASR,
    @SerialName("llm") LLM,
    @SerialName("ocr-det") OCR_DET,
    @SerialName("ocr-rec") OCR_REC,
    @SerialName("vad") VAD,
}

/** Which inference process hosts a runtime (ADR-005). */
enum class InferProcess { ASR, LLM }
