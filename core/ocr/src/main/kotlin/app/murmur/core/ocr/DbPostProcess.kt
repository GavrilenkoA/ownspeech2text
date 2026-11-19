package app.murmur.core.ocr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * DB post-processing with axis-aligned boxes (ml/ocr README section 3, pipeline.py `db_boxes`):
 * threshold -> 8-connected components -> box score (mean of the probability over the bounding box) -> unclip ->
 * scale to the source image. Arithmetic follows the reference: float32 threshold (NumPy compares a float32 map
 * with a Python float in float32), float64 integral image accumulated in NumPy's `cumsum(0).cumsum(1)` order.
 */
internal object DbPostProcess {

    fun boxes(prob: FloatArray, mapW: Int, mapH: Int, srcW: Int, srcH: Int, config: OcrConfig): List<DetBox> {
        require(prob.size == mapW * mapH) { "probability map size" }
        val thresh = config.dbThresh.toFloat()
        val components = components(prob, mapW, mapH, thresh)
        if (components.isEmpty()) return emptyList()
        val ii = integral(prob, mapW, mapH)
        val stride = mapW + 1
        val sx = srcW.toDouble() / mapW
        val sy = srcH.toDouble() / mapH
        val out = ArrayList<DetBox>()
        for (c in components) {
            val x = c[0]
            val y = c[1]
            val w = c[2]
            val h = c[3]
            if (min(w, h) < config.dbMinSide) continue
            val s = (ii[(y + h) * stride + x + w] - ii[y * stride + x + w] - ii[(y + h) * stride + x] + ii[y * stride + x]) /
                (w * h)
            if (s < config.dbBoxThresh) continue
            val d = w * h * config.dbUnclipRatio / (2.0 * (w + h))
            val bx0 = x - d
            val by0 = y - d
            val bx1 = x + w + d
            val by1 = y + h + d
            if (min(bx1 - bx0, by1 - by0) < config.dbMinSideUnclipped) continue
            val r0 = floorHalfUp(max(0.0, bx0) * sx).coerceIn(0, srcW)
            val r1 = floorHalfUp(max(0.0, by0) * sy).coerceIn(0, srcH)
            val r2 = floorHalfUp(min(mapW.toDouble(), bx1) * sx).coerceIn(0, srcW)
            val r3 = floorHalfUp(min(mapH.toDouble(), by1) * sy).coerceIn(0, srcH)
            if (r2 - r0 < 2 || r3 - r1 < 2) continue
            out += DetBox(Rect(r0, r1, r2, r3), s)
        }
        return out.sortedWith(compareBy<DetBox>({ it.rect.y0 }, { it.rect.x0 }, { it.rect.y1 }, { it.rect.x1 }))
    }

    private fun floorHalfUp(v: Double): Int = floor(v + 0.5).toInt()

    /**
     * Bounding boxes [x, y, w, h] of the 8-connected components of `prob > thresh`, in raster order of each
     * component's first pixel (OpenCV's label order).
     */
    fun components(prob: FloatArray, w: Int, h: Int, thresh: Float): List<IntArray> {
        val n = w * h
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        val out = ArrayList<IntArray>()
        for (start in 0 until n) {
            if (seen[start] || !(prob[start] > thresh)) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var minX = start % w
            var maxX = minX
            var minY = start / w
            var maxY = minY
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if ((dx == 0 && dy == 0) || nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (!seen[q] && prob[q] > thresh) {
                            seen[q] = true
                            stack[top++] = q
                        }
                    }
                }
            }
            out += intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
        return out
    }

    /** (h+1) x (w+1) float64 integral image, row 0 and column 0 zero: `ii[1:,1:] = prob.cumsum(0).cumsum(1)`. */
    fun integral(prob: FloatArray, w: Int, h: Int): DoubleArray {
        val stride = w + 1
        val ii = DoubleArray((h + 1) * stride)
        val col = DoubleArray(w)
        for (y in 0 until h) {
            var acc = 0.0
            for (x in 0 until w) {
                col[x] = if (y == 0) prob[x].toDouble() else col[x] + prob[y * w + x]
                acc = if (x == 0) col[0] else acc + col[x]
                ii[(y + 1) * stride + x + 1] = acc
            }
        }
        return ii
    }
}
