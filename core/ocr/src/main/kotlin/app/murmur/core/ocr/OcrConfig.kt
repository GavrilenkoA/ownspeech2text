package app.murmur.core.ocr

/**
 * Every constant of the OCR pipeline. Defaults MUST equal ml/ocr/config.py (PIPELINE_VERSION ocr.v2);
 * OcrConfigParityTest parses config.py and fails on drift. Changing a value is an ml/ocr change first
 * (it regenerates ml/ocr/golden), then a change here. ADR-013 (det_limit / rec_batch history).
 *
 * Model file names are not here: on Android they come from the catalog layout (LoadSpec.layout "model" / "dict").
 */
data class OcrConfig(
    val pipelineVersion: String = "ocr.v2",
    /** ORT intra-op threads. Output does not depend on it (ml/ocr README). */
    val threads: Int = 4,
    val detLimit: Int = 960,
    val detMultiple: Int = 32,
    val detTile: Int = 0,
    val detTileOverlap: Int = 96,
    /** Applied to the B, G, R channels in this order (PaddleOCR convention). */
    val detMean: List<Double> = listOf(0.485, 0.456, 0.406),
    val detStd: List<Double> = listOf(0.229, 0.224, 0.225),
    val dbThresh: Double = 0.2,
    val dbBoxThresh: Double = 0.4,
    val dbUnclipRatio: Double = 1.4,
    val dbMinSide: Double = 3.0,
    val dbMinSideUnclipped: Double = 5.0,
    val recHeight: Int = 48,
    val recBuckets: List<Int> = listOf(320, 480, 640, 800, 960, 1280, 1600, 1920, 2560, 3200),
    /** Lines per recogniser call. Output does not depend on it (each line is padded to its own bucket); speed does. */
    val recBatch: Int = 1,
    val minConf: Double = 0.5,
    val minChars: Int = 2,
    val excludeMinOverlap: Double = 0.5,
    val dedupIou: Double = 0.5,
    val rowOverlap: Double = 0.5,
    val paneMinAspect: Double = 0.75,
    val paneGutterRange: Pair<Double, Double> = 0.25 to 0.6,
    val paneMinLines: Int = 3,
    val paneMinSpan: Double = 0.4,
    /** Screen-level fallback script of the homoglyph repair needs this evidence ratio. */
    val screenScriptRatio: Double = 2.0,
    val confDigits: Int = 3,
) {
    init {
        require(detMean.size == 3 && detStd.size == 3) { "detMean/detStd need 3 values (B, G, R)" }
        require(recBuckets.isNotEmpty() && recBuckets.zipWithNext().all { (a, b) -> a < b }) { "recBuckets ascending" }
        require(recBatch >= 1 && threads >= 1 && detMultiple >= 1 && recHeight >= 1)
    }
}

/**
 * Homoglyph tables of ml/ocr/config.py (LAT2CYR_STRONG, LAT2CYR_WEAK, CYR2LAT_WEAK, NON_RU_TO_LAT, WEAK_WEIGHT),
 * kept as the same parallel strings so that OcrConfigParityTest can compare them with the Python source.
 */
object Homoglyphs {
    /** Latin letters visually identical to a Cyrillic letter ("strong" twins), and their twins. */
    const val LAT_STRONG = "aceopxyABCEHKMOPTX"
    const val CYR_STRONG = "асеорхуАВСЕНКМОРТХ"
    /** Latin letters the recogniser confuses with a Cyrillic letter in UI fonts ("weak" twins). */
    const val LAT_WEAK = "knurbmti"
    const val LAT_WEAK_TO_CYR = "кпигьтти"
    const val CYR_WEAK = "кпигьт"
    const val CYR_WEAK_TO_LAT = "knurbm"
    /** Cyrillic letters that do not occur in Russian: always a misread Latin letter on this user's screens. */
    const val NON_RU = "іїєјѕԁІЇЄЈЅӀӏ"
    const val NON_RU_TO_LAT = "iiejsdIIEJSll"
    /** Evidence of a weak twin for its own script (strong twins: 0, other letters: 1). */
    const val WEAK_WEIGHT = 0.25

    val lat2cyrStrong: Map<Int, Int> = zip(LAT_STRONG, CYR_STRONG)
    val lat2cyrWeak: Map<Int, Int> = zip(LAT_WEAK, LAT_WEAK_TO_CYR)
    val cyr2latStrong: Map<Int, Int> = lat2cyrStrong.entries.associate { (k, v) -> v to k }
    val cyr2latWeak: Map<Int, Int> = zip(CYR_WEAK, CYR_WEAK_TO_LAT)
    val nonRuToLat: Map<Int, Int> = zip(NON_RU, NON_RU_TO_LAT)

    private fun zip(a: String, b: String): Map<Int, Int> {
        val ka = a.codePoints().toArray()
        val kb = b.codePoints().toArray()
        require(ka.size == kb.size) { "homoglyph table sizes differ" }
        // LinkedHashMap with later keys winning, like Python's dict(zip(...)).
        val m = LinkedHashMap<Int, Int>()
        for (i in ka.indices) m[ka[i]] = kb[i]
        return m
    }
}
