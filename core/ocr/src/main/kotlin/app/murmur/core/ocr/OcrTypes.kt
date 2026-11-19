package app.murmur.core.ocr

import kotlinx.serialization.Serializable

/**
 * Output of the OCR pipeline = CONTEXT_V2 `screen` (docs/ml/CONTEXT_V2.md): filtered, repaired,
 * de-duplicated lines in reading order. [box] is [x0, y0, x1, y1] in screenshot pixels.
 * Lives in memory only (D9/D11): never persisted, never logged.
 */
@Serializable
data class OcrLine(val text: String, val conf: Double, val box: List<Int>) {
    init { require(box.size == 4) { "box must be [x0,y0,x1,y1]" } }
}

/**
 * [stats] is the timing side channel of the pipeline (numbers only, D9): filled by [OcrPipeline.run], read by the
 * benchmark (WP-14) from the `recognizeScreen` reply. It is not part of CONTEXT_V2 `screen`: ctx assembly uses
 * [size] and [lines] only. Null (and absent from the JSON) when nothing was measured.
 */
@Serializable
data class ScreenText(val size: List<Int>, val lines: List<OcrLine>, val stats: OcrStats? = null)

/**
 * Timings and counts of one [OcrPipeline.run]. [detMs] = detector pre-processing + inference + DB post-processing;
 * [recMs] = crops + recogniser calls + CTC for [recLines] lines; [totalMs] includes the text post-processing.
 */
@Serializable
data class OcrStats(
    /** Detector input [w, h] after the resize rule (README section 2). */
    val detInput: List<Int>,
    val detMs: Double,
    /** Boxes after DB post-processing, before exclusion rectangles. */
    val boxes: Int,
    /** Boxes dropped by exclusion rectangles. */
    val excluded: Int,
    /** Lines sent to the recogniser (= boxes - excluded). */
    val recLines: Int,
    val recMs: Double,
    /** Recogniser calls (one per batch). */
    val recCalls: Int,
    val totalMs: Double,
) {
    /** Recogniser cost per line, 0 when nothing was recognised. */
    val recMsPerLine: Double get() = if (recLines == 0) 0.0 else recMs / recLines
}

/** Axis-aligned rectangle in screenshot pixels, half-open [x0, x1) x [y0, y1). */
@Serializable
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val area get() = width.toLong() * height
}

/** Which screenshot path produced the pixels (ARCHITECTURE 9.1), when the capturer knows it. */
enum class CaptureKind {
    /** takeScreenshotOfWindow of the target window: pixel (0, 0) is the window's top-left. */
    WINDOW,
    /** takeScreenshot of the display (fallback after error 5): pixel (0, 0) is the display's top-left. */
    DISPLAY,
}

/**
 * A screenshot handed to the OCR engine. ARGB_8888 pixels (Android Bitmap layout, little-endian
 * 0xAARRGGBB per Int) at the captured resolution; [exclude] = OCR exclusion rectangles (status bar,
 * IME window, Murmur's own overlay) when the display capture fallback was used (window capture needs none).
 * [kind] = the capture path as the capturer (:platform:a11y) reports it; it stays in :main (the IPC frame carries
 * pixels and [exclude] only) and lets the context assembler place field.box (CaptureFrame). null = unknown.
 */
class ScreenImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val exclude: List<Rect> = emptyList(),
    val kind: CaptureKind? = null,
) {
    init { require(argb.size == width * height) { "pixel count" } }

    fun withKind(kind: CaptureKind): ScreenImage = ScreenImage(width, height, argb, exclude, kind)
}

/** Intermediate stages, exposed for the parity test against ml/ocr/golden (stage-level comparison). */
data class DetBox(val rect: Rect, val score: Double)

/** A recognised line before post-processing ([text] raw CTC output, [conf] unrounded). */
data class RecLine(val rect: Rect, val text: String, val conf: Double)

/** Script of a token or of the whole screen (homoglyph repair, ml/ocr/postprocess.py "cyr" / "lat"). */
enum class Script { CYR, LAT }
