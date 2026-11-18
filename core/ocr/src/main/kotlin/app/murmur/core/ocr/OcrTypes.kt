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

@Serializable
data class ScreenText(val size: List<Int>, val lines: List<OcrLine>)

/** Axis-aligned rectangle in screenshot pixels, half-open [x0, x1) x [y0, y1). */
@Serializable
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val area get() = width.toLong() * height
}

/**
 * A screenshot handed to the OCR engine. ARGB_8888 pixels (Android Bitmap layout, little-endian
 * 0xAARRGGBB per Int) at the captured resolution; [exclude] = OCR exclusion rectangles (status bar,
 * IME window, Murmur's own overlay) when the display capture fallback was used (window capture needs none).
 */
class ScreenImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val exclude: List<Rect> = emptyList(),
) {
    init { require(argb.size == width * height) { "pixel count" } }
}

/** Intermediate stages, exposed for the parity test against ml/ocr/golden (stage-level comparison). */
data class DetBox(val rect: Rect, val score: Double)
data class RecLine(val rect: Rect, val text: String, val conf: Double)
