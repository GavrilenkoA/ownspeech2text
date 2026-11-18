package app.murmur.core.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Pure-Kotlin parts of the OCR pipeline, a port of ml/ocr (README.md sections 2-6). The ONNX sessions are
 * behind [OcrModels] so this code runs on the host JVM in tests (parity with ml/ocr/golden) and inside
 * the :asr process on the phone (ORT CPU EP, ADR-013).
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

/** Every stage of one run, for the stage-by-stage parity test against ml/ocr/golden (`stages`, `ocr`). */
class OcrRun(
    /** Detector input [w, h]. */
    val detInput: Pair<Int, Int>,
    /** After DB post-processing, before exclusion, sorted by (y0, x0, y1, x1). */
    val detBoxes: List<DetBox>,
    /** After exclusion and recognition, before post-processing (raw text, unrounded conf), box order. */
    val rec: List<RecLine>,
    val result: ScreenText,
)

class OcrPipeline(private val config: OcrConfig = OcrConfig(), private val models: OcrModels) {
    /** CTC classes: 0 = blank, 1..N = dictionary, N+1 = space (PaddleOCR use_space_char=True). */
    private val chars: List<String> = buildList {
        add("")
        addAll(models.dictionary)
        add(" ")
    }

    init {
        require(models.classes == chars.size) { "dictionary has ${chars.size} classes, model outputs ${models.classes}" }
    }

    /** Full pipeline: image -> ScreenText (det -> DB post -> exclusion -> rec -> CTC -> post-processing). */
    fun run(image: ScreenImage): ScreenText = runDetailed(image).result

    fun runDetailed(image: ScreenImage): OcrRun {
        val t0 = System.nanoTime()
        val w = image.width
        val h = image.height
        val (tw, th) = detTargetSize(w, h)
        val x = detInput(image, tw, th)
        val prob = probMap(x, tw, th)
        val dets = DbPostProcess.boxes(prob, tw, th, w, h, config)
        val t1 = System.nanoTime()
        val boxes = dets.map { it.rect }.filter { !OcrPostProcess.excluded(it, image.exclude, config.excludeMinOverlap) }
        val (recs, calls) = recognise(image, boxes)
        val t2 = System.nanoTime()
        val lines = OcrPostProcess.process(recs, w, h, config)
        val t3 = System.nanoTime()
        val stats = OcrStats(
            detInput = listOf(tw, th),
            detMs = ms(t1 - t0),
            boxes = dets.size,
            excluded = dets.size - boxes.size,
            recLines = boxes.size,
            recMs = ms(t2 - t1),
            recCalls = calls,
            totalMs = ms(t3 - t0),
        )
        return OcrRun(tw to th, dets, recs, ScreenText(listOf(w, h), lines, stats))
    }

    /** README section 2: target size (round half up to a multiple of 32, never upscale). Returns (w, h). */
    fun detTargetSize(w: Int, h: Int): Pair<Int, Int> {
        var r = 1.0
        val longest = max(w, h)
        if (config.detLimit != 0 && longest > config.detLimit) r = config.detLimit.toDouble() / longest
        val m = config.detMultiple
        return max(m, floor(w * r / m + 0.5).toInt() * m) to max(m, floor(h * r / m + 0.5).toInt() * m)
    }

    /** NCHW [1, 3, th, tw]: BGR, resized (half-pixel bilinear, float32), `(v / 255 - mean) / std` per channel. */
    internal fun detInput(image: ScreenImage, tw: Int, th: Int): FloatArray {
        val x = Bilinear.resizeBgr(image.argb, image.width, 0, 0, image.width, image.height, tw, th)
        val plane = tw * th
        for (c in 0 until 3) {
            val mean = config.detMean[c].toFloat()
            val std = config.detStd[c].toFloat()
            for (i in c * plane until (c + 1) * plane) x[i] = (x[i] / 255f - mean) / std
        }
        return x
    }

    /** Probability map (th x tw), optionally in full-width horizontal tiles (README section 2.5; off by default). */
    internal fun probMap(x: FloatArray, tw: Int, th: Int): FloatArray {
        val tile = config.detTile
        if (tile == 0 || th <= tile) return checkMap(models.detect(x, th, tw), tw, th)
        val ov = config.detTileOverlap
        val out = FloatArray(tw * th)
        var y = 0
        while (true) {
            val y1 = minOf(th, y + tile)
            val y0 = max(0, y1 - tile)
            val rows = y1 - y0
            val sub = FloatArray(3 * rows * tw)
            for (c in 0 until 3) System.arraycopy(x, c * th * tw + y0 * tw, sub, c * rows * tw, rows * tw)
            val p = checkMap(models.detect(sub, rows, tw), tw, rows)
            // Each tile owns rows from the middle of the overlap above to the middle of the overlap below.
            val own0 = if (y0 == 0) 0 else y0 + ov / 2
            val own1 = if (y1 == th) th else y1 - ov / 2
            if (own1 > own0) System.arraycopy(p, (own0 - y0) * tw, out, own0 * tw, (own1 - own0) * tw)
            if (y1 == th) break
            y = y1 - ov
        }
        return out
    }

    private fun checkMap(p: FloatArray, tw: Int, th: Int): FloatArray {
        check(p.size == tw * th) { "detector output ${p.size} != $th x $tw" }
        return p
    }

    /** (resized width, padded bucket width) for a crop of w x h (README section 5.2). */
    fun recWidth(w: Int, h: Int): Pair<Int, Int> {
        val rw = ceil((config.recHeight * w).toDouble() / h).toInt()
        for (b in config.recBuckets) if (rw <= b) return rw to b
        val last = config.recBuckets.last()
        return last to last
    }

    /** One line's recogniser input [3, 48, bw]: crop -> BGR -> resize to rw x 48 -> `(v/255 - 0.5)/0.5`, right zero padding. */
    internal fun recInput(image: ScreenImage, box: Rect, rw: Int, bw: Int, dst: FloatArray, offset: Int) {
        val hh = config.recHeight
        val r = Bilinear.resizeBgr(image.argb, image.width, box.x0, box.y0, box.width, box.height, rw, hh)
        for (c in 0 until 3) {
            for (y in 0 until hh) {
                val src = c * hh * rw + y * rw
                val o = offset + c * hh * bw + y * bw
                for (x in 0 until rw) dst[o + x] = (r[src + x] / 255f - 0.5f) / 0.5f
                // dst is freshly allocated (zeros): the padding stays 0 = mid-grey after normalisation.
            }
        }
    }

    /** Batches of the same bucket width, in box order inside each bucket. Returns the lines and the number of calls. */
    internal fun recognise(image: ScreenImage, boxes: List<Rect>): Pair<List<RecLine>, Int> {
        val widths = boxes.map { recWidth(it.width, it.height) }
        val res = arrayOfNulls<RecLine>(boxes.size)
        val byWidth = boxes.indices.groupBy { widths[it].second }.toSortedMap()
        val hh = config.recHeight
        var calls = 0
        for ((bw, ids) in byWidth) {
            for (chunk in ids.chunked(config.recBatch)) {
                val per = 3 * hh * bw
                val input = FloatArray(chunk.size * per)
                chunk.forEachIndexed { j, i -> recInput(image, boxes[i], widths[i].first, bw, input, j * per) }
                val out = models.recognize(input, chunk.size, bw)
                calls++
                val perOut = out.size / chunk.size
                check(perOut * chunk.size == out.size && perOut % models.classes == 0) { "recogniser output ${out.size}" }
                val steps = perOut / models.classes
                chunk.forEachIndexed { j, i ->
                    val (text, conf) = Ctc.decode(out, j * perOut, steps, models.classes, chars)
                    res[i] = RecLine(boxes[i], text, conf)
                }
            }
        }
        return res.map { it!! } to calls
    }

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
}

/** Engine-facing contract (implemented in :infer:asr, faked in :testing). */
interface OcrEngine {
    suspend fun recognize(image: ScreenImage): ScreenText
}
