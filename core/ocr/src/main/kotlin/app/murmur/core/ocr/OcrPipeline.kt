package app.murmur.core.ocr

/**
 * Pure-Kotlin parts of the OCR pipeline, a port of ml/ocr (README.md sections 2-6). The ONNX sessions are
 * behind [OcrModels] so this code runs on the host JVM in tests (parity with ml/ocr/golden) and inside
 * the :asr process on the phone (ORT CPU EP, ADR-013).
 *
 * WP-07 implements every function below; the skeleton throws NotImplementedError.
 */
interface OcrModels {
    /** Detector: NCHW float32 [1,3,h,w] (BGR, normalised) -> probability map [h*w]. */
    fun detect(input: FloatArray, h: Int, w: Int): FloatArray
    /** Recogniser: [n,3,48,bucketWidth] -> softmax [n, bucketWidth/8, classes]. */
    fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray
    val classes: Int
    /** Dictionary from inference.yml PostProcess.character_dict (850 entries); class 0 = blank, last = space. */
    val dictionary: List<String>
}

class OcrPipeline(private val config: OcrConfig = OcrConfig(), private val models: OcrModels) {
    /** Full pipeline: image -> ScreenText (det -> DB post -> exclusion -> rec -> CTC -> post-processing). */
    fun run(image: ScreenImage): ScreenText = TODO("WP-07: port ml/ocr/pipeline.py")

    /** README section 2: target size (round half up to a multiple of 32, never upscale). */
    fun detTargetSize(w: Int, h: Int): Pair<Int, Int> = TODO("WP-07")
}

/** ml/ocr/postprocess.py: text normalisation, filters, homoglyph repair, de-dup, reading order. */
object OcrPostProcess {
    /** Feed `stages.rec` of a golden file, get `ocr` back byte-identical (the strict half of the parity test). */
    fun process(lines: List<RecLine>, width: Int, height: Int, config: OcrConfig = OcrConfig()): List<OcrLine> =
        TODO("WP-07: port ml/ocr/postprocess.py")

    fun repairHomoglyphs(text: String): String = TODO("WP-07")
}

/** Engine-facing contract (implemented in :infer:asr, faked in :testing). */
interface OcrEngine {
    suspend fun recognize(image: ScreenImage): ScreenText
}
