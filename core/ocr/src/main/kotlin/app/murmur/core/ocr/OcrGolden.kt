package app.murmur.core.ocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.abs

/**
 * One ml/ocr/golden/NNN.json (the parity suite of D11): inputs and every stage of the Python reference.
 * `stages.det_boxes` scores and `stages.rec` confs are rounded to 4 digits there; `ocr` is the final output.
 * Image decoding is platform code (ImageIO on the host, BitmapFactory on Android): see [OcrGolden.image].
 */
class OcrGolden(
    val name: String,
    val pipeline: String,
    val exclude: List<Rect>,
    /** Detector input (w, h). */
    val detInput: Pair<Int, Int>,
    val detBoxes: List<DetBox>,
    val rec: List<RecLine>,
    val ocr: ScreenText,
) {
    /** [argb] = the decoded PNG (0xAARRGGBB, alpha ignored) as a [ScreenImage] with this golden's exclusion rectangles. */
    fun image(width: Int, height: Int, argb: IntArray) = ScreenImage(width, height, argb, exclude)

    companion object {
        fun parse(name: String, json: String): OcrGolden {
            val o = Json.parseToJsonElement(json).jsonObject
            val st = o.getValue("stages").jsonObject
            val di = st.getValue("det_input").jsonArray
            return OcrGolden(
                name = name,
                pipeline = o.getValue("pipeline").jsonPrimitive.content,
                exclude = o.getValue("exclude_rects").jsonArray.map { rect(it.jsonArray) },
                detInput = di[0].jsonPrimitive.int to di[1].jsonPrimitive.int,
                detBoxes = st.getValue("det_boxes").jsonArray.map {
                    val a = it.jsonArray
                    DetBox(rect(a), a[4].jsonPrimitive.double)
                },
                rec = st.getValue("rec").jsonArray.map {
                    val r = it.jsonObject
                    RecLine(rect(r.getValue("box").jsonArray), r.getValue("text").jsonPrimitive.content,
                        r.getValue("conf").jsonPrimitive.double)
                },
                ocr = screenText(o.getValue("ocr").jsonObject),
            )
        }

        private fun rect(a: JsonArray) =
            Rect(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.int, a[3].jsonPrimitive.int)

        private fun screenText(o: JsonObject) = ScreenText(
            size = o.getValue("size").jsonArray.map { it.jsonPrimitive.int },
            lines = o.getValue("lines").jsonArray.map {
                val l = it.jsonObject
                OcrLine(l.getValue("text").jsonPrimitive.content, l.getValue("conf").jsonPrimitive.double,
                    l.getValue("box").jsonArray.map { v -> v.jsonPrimitive.int })
            },
        )
    }
}

/**
 * The ml/ocr README tolerances ("Parity with Android") for a port that is not bit-exact: same number of lines, boxes
 * within 2 px, texts identical for >= 98 % of lines and CER <= 0.5 % on the rest (the non-identical lines, pooled:
 * edits / reference code points of those lines), conf within 0.02. Lines are paired by position (both sides are in
 * reading order). The CER over all lines ([Suite.cer]) is reported, not gated. Used by the parity tests and by the
 * benchmark's OCR validity gate (WP-14).
 */
object OcrTolerance {
    const val BOX_PX = 2
    const val CONF = 0.02
    const val MIN_IDENTICAL = 0.98
    const val MAX_CER = 0.005

    class Page(
        val name: String,
        val wantLines: Int,
        val gotLines: Int,
        val identical: Int,
        /** Levenshtein edits over all paired lines, and the reference code points of those lines. */
        val edits: Int,
        val chars: Int,
        /** The same over the non-identical lines only. */
        val restEdits: Int,
        val restChars: Int,
        val maxBoxPx: Int,
        val maxConf: Double,
        /** Lists equal (text, conf, box of every line). */
        val exact: Boolean,
    ) {
        val withinLineTolerance get() = wantLines == gotLines && maxBoxPx <= BOX_PX && maxConf <= CONF
        override fun toString() = "$name lines $gotLines/$wantLines identical $identical edits $edits/$chars " +
            "box<=${maxBoxPx}px conf<=${"%.4f".format(Locale.ROOT, maxConf)}${if (exact) " EXACT" else ""}"
    }

    class Suite(val pages: List<Page>) {
        val lines = pages.sumOf { it.wantLines }
        val identical = pages.sumOf { it.identical }
        val cer = pages.sumOf { it.edits }.toDouble() / maxOf(1, pages.sumOf { it.chars })
        val restCer = pages.sumOf { it.restEdits }.toDouble() / maxOf(1, pages.sumOf { it.restChars })
        val failures: List<String> = buildList {
            pages.filter { !it.withinLineTolerance }.forEach { add("${it.name}: line count / box / conf outside tolerance") }
            if (identical < MIN_IDENTICAL * lines) add("identical texts $identical/$lines below 98 %")
            if (restCer > MAX_CER) add("CER on the non-identical lines ${"%.3f".format(Locale.ROOT, 100 * restCer)} % above 0.5 %")
        }
        val ok get() = failures.isEmpty()

        override fun toString() = buildString {
            pages.forEach { appendLine(it) }
            append("suite: pages ${pages.size}, exact ${pages.count { it.exact }}, lines $lines, identical $identical " +
                "(${"%.2f".format(Locale.ROOT, 100.0 * identical / maxOf(1, lines))} %), CER ${"%.3f".format(Locale.ROOT, 100 * cer)} %, " +
                "rest CER ${"%.3f".format(Locale.ROOT, 100 * restCer)} %, max box ${pages.maxOfOrNull { it.maxBoxPx } ?: 0} px, " +
                "max conf ${"%.4f".format(Locale.ROOT, pages.maxOfOrNull { it.maxConf } ?: 0.0)}")
            if (failures.isNotEmpty()) append("\nFAIL: ").append(failures.joinToString("; "))
        }
    }

    fun compare(name: String, want: List<OcrLine>, got: List<OcrLine>): Page {
        var identical = 0
        var edits = 0
        var chars = 0
        var restEdits = 0
        var restChars = 0
        var maxBox = 0
        var maxConf = 0.0
        for (i in 0 until minOf(want.size, got.size)) {
            val w = want[i]
            val g = got[i]
            val d = levenshtein(w.text, g.text)
            val n = w.text.codePointCount(0, w.text.length)
            edits += d
            chars += n
            if (d == 0) identical++ else { restEdits += d; restChars += n }
            for (k in 0 until 4) maxBox = maxOf(maxBox, abs(w.box[k] - g.box[k]))
            maxConf = maxOf(maxConf, abs(w.conf - g.conf))
        }
        // Unpaired lines count as fully wrong.
        for (w in want.drop(got.size)) { val n = w.text.codePointCount(0, w.text.length); edits += n; chars += n; restEdits += n; restChars += n }
        return Page(name, want.size, got.size, identical, edits, chars, restEdits, restChars, maxBox, maxConf, want == got)
    }

    fun levenshtein(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prev = IntArray(y.size + 1) { it }
        for (i in 1..x.size) {
            val cur = IntArray(y.size + 1)
            cur[0] = i
            for (j in 1..y.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[y.size]
    }
}
