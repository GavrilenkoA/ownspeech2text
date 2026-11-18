package app.murmur.core.engine

import app.murmur.core.model.Outcome
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText

/** OCR in the :asr process (ORT CPU EP shared with sherpa, ADR-013). One call per dictation. */
interface OcrService {
    suspend fun loadOcr(det: LoadSpec, rec: LoadSpec): Outcome<LoadInfo>
    suspend fun recognize(image: ScreenImage): Outcome<ScreenText>
    suspend fun unloadOcr()
}

/** The engines the orchestrator talks to (real: binder clients in :infer:api; tests: :testing fakes). */
interface Engines {
    val asr: AsrEngine
    val llm: LlmEngine
    val ocr: OcrService
    val asrHost: InferenceHost
    val llmHost: InferenceHost
}
