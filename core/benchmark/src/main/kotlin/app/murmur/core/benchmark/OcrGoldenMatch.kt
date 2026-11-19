package app.murmur.core.benchmark

import app.murmur.core.ocr.ScreenText
import kotlin.math.abs

/**
 * The OCR correctness gate: the device output must match the golden within the tolerances of ml/ocr README
 * ("Parity with Android"): same number of lines, boxes within 2 px, texts identical for >= 98 % of the lines and
 * CER <= 0.5 % on the rest, conf within 0.02. Lines are compared in order (both sides are in reading order). Pure.
 */
object OcrGoldenMatch {
    const val BOX_PX = 2
    const val MIN_IDENTICAL_SHARE = 0.98
    const val MAX_CER_REST = 0.005
    const val CONF_DELTA = 0.02

    /** null = matches, else the invalidReason code. */
    fun check(golden: ScreenText, actual: ScreenText): String? {
        if (actual.lines.isEmpty()) return "ocr_no_boxes"
        if (actual.lines.size != golden.lines.size) return "ocr_line_count"
        val pairs = golden.lines.zip(actual.lines)
        if (pairs.any { (g, a) -> g.box.zip(a.box).any { (x, y) -> abs(x - y) > BOX_PX } }) return "ocr_box"
        if (pairs.any { (g, a) -> abs(g.conf - a.conf) > CONF_DELTA + 1e-9 }) return "ocr_conf"
        val different = pairs.filter { (g, a) -> g.text != a.text }
        if (pairs.size - different.size < MIN_IDENTICAL_SHARE * pairs.size) return "ocr_text"
        val refChars = different.sumOf { it.first.text.length }
        val edits = different.sumOf { (g, a) -> WordErrorRate.editDistance(g.text.toList(), a.text.toList()) }
        if (refChars > 0 && edits.toDouble() / refChars > MAX_CER_REST) return "ocr_text"
        return null
    }
}
