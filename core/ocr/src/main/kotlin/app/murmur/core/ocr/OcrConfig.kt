package app.murmur.core.ocr

/**
 * Every constant of the OCR pipeline. Defaults MUST equal ml/ocr/config.py (PIPELINE_VERSION ocr.v1);
 * OcrConfigParityTest parses config.py and fails on drift. Changing a value is an ml/ocr change first
 * (it regenerates ml/ocr/golden), then a change here. ADR-013 (det_limit / rec_batch history).
 */
data class OcrConfig(
    val pipelineVersion: String = "ocr.v1",
    val threads: Int = 4,
    val detLimit: Int = 960,
    val detMultiple: Int = 32,
    val detTile: Int = 0,
    val detTileOverlap: Int = 96,
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
    val confDigits: Int = 3,
)
