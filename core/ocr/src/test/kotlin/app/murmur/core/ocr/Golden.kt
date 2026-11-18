package app.murmur.core.ocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO

/**
 * ml/ocr/golden/NNN.json (+ NNN.png), the parity suite of D11, read in place. `stages.det_input`, `stages.det_boxes`
 * (score rounded to 4 digits), `stages.rec` (raw text, conf rounded to 4 digits), `ocr` (final output).
 */
class Golden(
    val name: String,
    val png: File,
    val pipeline: String,
    val exclude: List<Rect>,
    val detInput: Pair<Int, Int>,
    val detBoxes: List<DetBox>,
    val rec: List<RecLine>,
    val ocr: ScreenText,
) {
    /** The PNG as a [ScreenImage] (RGB8, no colour management, alpha 0xFF) with this golden's exclusion rectangles. */
    fun image(): ScreenImage {
        val img = ImageIO.read(png)
        val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return ScreenImage(img.width, img.height, argb, exclude)
    }

    companion object {
        val root = File(System.getProperty("murmur.repoRoot") ?: ".")
        val dir = File(root, "ml/ocr/golden")

        fun all(): List<Golden> {
            val files = dir.listFiles { f -> f.name.matches(Regex("\\d{3}\\.json")) }?.sortedBy { it.name }.orEmpty()
            check(files.isNotEmpty()) { "ml/ocr/golden not found under $root" }
            return files.map(::load)
        }

        fun load(f: File): Golden {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            val st = o.getValue("stages").jsonObject
            val di = st.getValue("det_input").jsonArray
            return Golden(
                name = f.nameWithoutExtension,
                png = File(f.parentFile, f.nameWithoutExtension + ".png"),
                pipeline = o.getValue("pipeline").jsonPrimitive.content,
                exclude = o.getValue("exclude_rects").jsonArray.map { rect(it.jsonArray) },
                detInput = di[0].jsonPrimitive.int to di[1].jsonPrimitive.int,
                detBoxes = st.getValue("det_boxes").jsonArray.map {
                    val a = it.jsonArray
                    DetBox(rect(a), a[4].jsonPrimitive.double)
                },
                rec = st.getValue("rec").jsonArray.map {
                    val r = it.jsonObject
                    RecLine(rect(r.getValue("box").jsonArray), r.getValue("text").jsonPrimitive.content, r.getValue("conf").jsonPrimitive.double)
                },
                ocr = screenText(o.getValue("ocr").jsonObject),
            )
        }

        private fun rect(a: JsonArray) = Rect(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.int, a[3].jsonPrimitive.int)

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
 * The ml/ocr README tolerances ("Parity with Android"): same number of lines, boxes within 2 px, texts identical for
 * >= 98 % of lines and CER <= 0.5 %, conf within 0.02. Lines are paired by position (both sides are in reading order).
 */
object Parity {
    const val BOX_PX = 2
    const val CONF = 0.02
    const val MIN_IDENTICAL = 0.98
    const val MAX_CER = 0.005

    class PageDiff(
        val name: String,
        val wantLines: Int,
        val gotLines: Int,
        val identical: Int,
        /** Levenshtein edits over all paired lines / reference characters of those lines. */
        val edits: Int,
        val chars: Int,
        /** Same, over the non-identical lines only. */
        val restEdits: Int,
        val restChars: Int,
        val maxBoxPx: Int,
        val maxConf: Double,
        val exact: Boolean,
    ) {
        val sameCount get() = wantLines == gotLines
        override fun toString() = "$name lines $gotLines/$wantLines identical $identical edits $edits/$chars " +
            "box<=${maxBoxPx}px conf<=${"%.4f".format(maxConf)}${if (exact) " EXACT" else ""}"
    }

    fun compare(name: String, want: List<OcrLine>, got: List<OcrLine>): PageDiff {
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
            for (k in 0 until 4) maxBox = maxOf(maxBox, kotlin.math.abs(w.box[k] - g.box[k]))
            maxConf = maxOf(maxConf, kotlin.math.abs(w.conf - g.conf))
        }
        return PageDiff(name, want.size, got.size, identical, edits, chars, restEdits, restChars, maxBox, maxConf, want == got)
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

    /** Asserts the README tolerances over a suite; returns a printable summary. */
    fun assertWithin(pages: List<PageDiff>): String {
        val lines = pages.sumOf { it.wantLines }
        val identical = pages.sumOf { it.identical }
        val edits = pages.sumOf { it.edits }
        val chars = pages.sumOf { it.chars }
        val summary = buildString {
            pages.forEach { appendLine(it) }
            append("suite: pages ${pages.size}, exact ${pages.count { it.exact }}, lines $lines, identical $identical " +
                "(${"%.2f".format(100.0 * identical / maxOf(1, lines))} %), CER ${"%.3f".format(100.0 * edits / maxOf(1, chars))} %, " +
                "rest CER ${"%.3f".format(100.0 * pages.sumOf { it.restEdits } / maxOf(1, pages.sumOf { it.restChars }))} %, " +
                "max box ${pages.maxOfOrNull { it.maxBoxPx } ?: 0} px, max conf ${"%.4f".format(pages.maxOfOrNull { it.maxConf } ?: 0.0)}")
        }
        val bad = pages.filter { !it.sameCount || it.maxBoxPx > BOX_PX || it.maxConf > CONF }
        check(bad.isEmpty()) { "outside tolerance (count / box / conf): ${bad.joinToString { it.name }}\n$summary" }
        check(identical >= MIN_IDENTICAL * lines) { "identical texts below 98 %\n$summary" }
        check(edits <= MAX_CER * chars) { "CER above 0.5 %\n$summary" }
        return summary
    }
}
