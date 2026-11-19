package app.murmur.core.benchmark

import app.murmur.core.ocr.ScreenText
import kotlin.math.abs

/**
 * The OCR correctness gate: the device output must match the golden within the tolerances of ml/ocr README
 * ("Parity with Android") as ADR-077 reads them for WP-07's `OcrTolerance.Suite`: per page the same number of lines,
 * boxes within 2 px and conf within 0.02; over **all pages of the run** (the README states a suite rule) texts
 * identical for >= 98 % of the lines and CER <= 0.5 % on the rest (the non-identical lines, pooled: edits / reference
 * code points of those lines). Lines are compared in order (both sides are in reading order). Pure.
 *
 * With the two bundled pages (14 + 29 = 43 lines) 98 % leaves no differing line, so the text rule is in effect an exact
 * match here (see the WP-14 report: an allowance for small samples is an architect decision).
 */
object OcrGoldenMatch {
    const val BOX_PX = 2
    const val MIN_IDENTICAL_SHARE = 0.98
    const val MAX_CER_REST = 0.005
    const val CONF_DELTA = 0.02

    /** One page: null = matches, else the invalidReason code. */
    fun check(golden: ScreenText, actual: ScreenText): String? = check(listOf(golden to actual))

    /** Every page of the run as (golden, actual): null = matches, else the invalidReason code of the first rule broken. */
    fun check(pages: List<Pair<ScreenText, ScreenText>>): String? {
        if (pages.any { (_, a) -> a.lines.isEmpty() }) return "ocr_no_boxes"
        if (pages.any { (g, a) -> a.lines.size != g.lines.size }) return "ocr_line_count"
        val pairs = pages.flatMap { (g, a) -> g.lines.zip(a.lines) }
        if (pairs.any { (g, a) -> g.box.zip(a.box).any { (x, y) -> abs(x - y) > BOX_PX } }) return "ocr_box"
        if (pairs.any { (g, a) -> abs(g.conf - a.conf) > CONF_DELTA + 1e-9 }) return "ocr_conf"
        val different = pairs.filter { (g, a) -> g.text != a.text }
        if (pairs.size - different.size < MIN_IDENTICAL_SHARE * pairs.size) return "ocr_text"
        val restChars = different.sumOf { (g, _) -> g.text.codePointCount(0, g.text.length) }
        val restEdits = different.sumOf { (g, a) -> WordErrorRate.editDistance(codePoints(g.text), codePoints(a.text)) }
        if (restChars > 0 && restEdits.toDouble() / restChars > MAX_CER_REST) return "ocr_text"
        return null
    }

    private fun codePoints(s: String): List<Int> = s.codePoints().toArray().asList()
}
