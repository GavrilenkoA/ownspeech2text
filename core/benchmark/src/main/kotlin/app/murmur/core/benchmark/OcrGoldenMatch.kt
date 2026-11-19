package app.murmur.core.benchmark

import app.murmur.core.ocr.ScreenText
import kotlin.math.abs

/**
 * The benchmark's OCR correctness gate (ADR-080). Per page the same number of lines, boxes within 2 px and conf within
 * 0.02 (ml/ocr README "Parity with Android"); the text rule is judged over **all pages of the run**: at most
 * ceil(2 % of the lines) lines may differ, and the CER over all reference code points of the run is <= 0.5 %. Lines
 * are compared in order (both sides are in reading order). Pure.
 *
 * This is the README's 98 % / 0.5 % rule adapted to a small sample: with the two bundled pages (14 + 29 = 43 lines)
 * the parity reading of ADR-077 (`OcrTolerance.Suite`, CER on the non-identical lines) leaves no differing line, so one
 * rounding character on the NPU would mark a correct unit "Results look wrong". Here one line of the 43 may differ by a
 * few characters; a wrong recogniser still fails (two lines, or one line rewritten). The parity test over
 * ml/ocr/golden keeps `OcrTolerance.Suite` unchanged.
 */
object OcrGoldenMatch {
    const val BOX_PX = 2
    /** Share of lines that may differ, rounded up to whole lines (ceil(0.02 x 43) = 1). */
    const val MAX_DIFFERENT_SHARE = 0.02
    /** CER over every reference code point of the run. */
    const val MAX_CER = 0.005
    const val CONF_DELTA = 0.02

    /** Lines of a run of [lines] lines that may differ in text. */
    fun allowedDifferent(lines: Int): Int = kotlin.math.ceil(MAX_DIFFERENT_SHARE * lines - 1e-9).toInt()

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
        if (different.size > allowedDifferent(pairs.size)) return "ocr_text"
        val chars = pairs.sumOf { (g, _) -> g.text.codePointCount(0, g.text.length) }
        val edits = different.sumOf { (g, a) -> WordErrorRate.editDistance(codePoints(g.text), codePoints(a.text)) }
        if (edits > 0 && edits.toDouble() / maxOf(1, chars) > MAX_CER) return "ocr_text"
        return null
    }

    private fun codePoints(s: String): List<Int> = s.codePoints().toArray().asList()
}
