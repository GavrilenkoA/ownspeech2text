package app.murmur.core.catalog

import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelKind

/**
 * Runtime keys used in [Artifact.runtime]. A key names "which code loads these files". Each inference
 * process reports the keys it implements ([app.murmur.core.catalog.DeviceProfile.supportedRuntimes]);
 * an artifact with an unimplemented key is shown in the hub as "Not supported in this version".
 */
object RuntimeKeys {
    // sherpa-onnx 2.0.1 (source build with QNN, ADR-008); provider = cpu or qnn from the chosen unit.
    const val SHERPA_OFFLINE_TRANSDUCER = "sherpa/offline-transducer"   // Parakeet TDT, GigaAM RNN-T
    const val SHERPA_OFFLINE_CTC = "sherpa/offline-ctc"                 // GigaAM CTC
    const val SHERPA_OFFLINE_WHISPER = "sherpa/offline-whisper"
    const val SHERPA_OFFLINE_QWEN3_ASR = "sherpa/offline-qwen3-asr"
    const val SHERPA_ONLINE_TRANSDUCER = "sherpa/online-transducer"     // Nemotron streaming
    const val SHERPA_ONLINE_TONE = "sherpa/online-t-one"                // T-one streaming CTC
    // llama.cpp b12520 + ggml-hexagon (ADR-009)
    const val LLAMA_GGUF = "llama/gguf"
    // ONNX Runtime 1.32 (shared with sherpa)
    const val ORT_PPOCR_DET = "ort/ppocr-det"
    const val ORT_PPOCR_REC = "ort/ppocr-rec"
    const val ORT_SILERO_VAD = "ort/silero-vad-v5"
    // Listed in the catalog, not implemented (no runner / runtime not shipped): hub shows "Not supported".
    const val LITERT_NPU = "litert/npu"
    const val LITERT_LM_GPU = "litert-lm/gpu"
    const val QNN_AIHUB_WHISPER = "qnn/aihub-whisper"

    val known: Set<String> = setOf(
        SHERPA_OFFLINE_TRANSDUCER, SHERPA_OFFLINE_CTC, SHERPA_OFFLINE_WHISPER, SHERPA_OFFLINE_QWEN3_ASR,
        SHERPA_ONLINE_TRANSDUCER, SHERPA_ONLINE_TONE, LLAMA_GGUF, ORT_PPOCR_DET, ORT_PPOCR_REC, ORT_SILERO_VAD,
        LITERT_NPU, LITERT_LM_GPU, QNN_AIHUB_WHISPER,
    )

    /** Which inference process hosts a runtime (ADR-005: QNN users in :asr, ggml-hexagon alone in :llm). */
    fun process(runtime: String): InferProcess? = when {
        runtime.startsWith("sherpa/") || runtime.startsWith("ort/") -> InferProcess.ASR
        runtime == LLAMA_GGUF -> InferProcess.LLM
        else -> null
    }

    fun kindOf(runtime: String): ModelKind? = when (runtime) {
        LLAMA_GGUF -> ModelKind.LLM
        ORT_PPOCR_DET -> ModelKind.OCR_DET
        ORT_PPOCR_REC -> ModelKind.OCR_REC
        ORT_SILERO_VAD -> ModelKind.VAD
        LITERT_LM_GPU, LITERT_NPU, QNN_AIHUB_WHISPER -> ModelKind.ASR
        else -> if (runtime.startsWith("sherpa/")) ModelKind.ASR else null
    }
}
