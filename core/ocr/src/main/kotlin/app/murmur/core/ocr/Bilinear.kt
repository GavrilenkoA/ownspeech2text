package app.murmur.core.ocr

import kotlin.math.floor

/**
 * Bilinear resize with half-pixel centres computed in float32, bit-identical to OpenCV 5.1 `cv2.resize(INTER_LINEAR)`
 * on a float32 image, the resize ml/ocr uses (README section 2.3):
 *  - per axis `src = (dst + 0.5) * scale - 0.5` with `scale = 1 / (dst / src)` in double, `i = floor(src)`, weight
 *    `a = (float)(src - i)` (OpenCV 5 computes the offsets in double);
 *  - x: an offset left of the first or right of the last column is clamped together with its weight (edge pixel);
 *    y: row indices are clamped, weights are kept (OpenCV's vertical pass);
 *  - horizontal pass first, then the vertical pass over two such rows, each value a lerp with one rounding:
 *    `fma(p1 - p0, a, p0)` in float (OpenCV 5's float kernels). BilinearTest checks every value bit for bit against
 *    cv2 5.1 for down- and up-scaling, a crop, the detector size and a recogniser crop.
 */
internal object Bilinear {
    /** Source indices and the weight `a` of the second one per destination position. */
    class Axis(val i0: IntArray, val i1: IntArray, val a: FloatArray)

    fun axis(src: Int, dst: Int, clampWeights: Boolean): Axis {
        require(src > 0 && dst > 0)
        val scale = 1.0 / (dst.toDouble() / src)
        val i0 = IntArray(dst)
        val i1 = IntArray(dst)
        val w = FloatArray(dst)
        for (d in 0 until dst) {
            val f = (d + 0.5) * scale - 0.5
            var s = floor(f).toInt()
            var a = (f - s).toFloat()
            if (clampWeights) {
                if (s < 0) { s = 0; a = 0f }
                if (s >= src - 1) { s = src - 1; a = 0f }
            }
            i0[d] = s.coerceIn(0, src - 1)
            i1[d] = (s + 1).coerceIn(0, src - 1)
            w[d] = a
        }
        return Axis(i0, i1, w)
    }

    /**
     * Resizes the region [x0, x0+w) x [y0, y0+h) of an ARGB_8888 image ([stride] pixels per row) to [dw] x [dh] and
     * returns planar float32 B, G, R planes (each dh*dw, values 0..255): `out[c*dh*dw + y*dw + x]`.
     */
    fun resizeBgr(argb: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int, dw: Int, dh: Int): FloatArray {
        val ax = axis(w, dw, clampWeights = true)
        val ay = axis(h, dh, clampWeights = false)
        val plane = dw * dh
        val out = FloatArray(3 * plane)
        // Two cached horizontally resized source rows (B, G, R interleaved per plane: [c*dw + x]).
        var rowA = -1
        var rowB = -1
        var bufA = FloatArray(3 * dw)
        var bufB = FloatArray(3 * dw)
        fun hpass(srcRow: Int, buf: FloatArray) {
            val base = (y0 + srcRow) * stride + x0
            for (x in 0 until dw) {
                val p0 = argb[base + ax.i0[x]]
                val p1 = argb[base + ax.i1[x]]
                val a = ax.a[x]
                buf[x] = lerp(p0 and 0xFF, p1 and 0xFF, a)
                buf[dw + x] = lerp((p0 ushr 8) and 0xFF, (p1 ushr 8) and 0xFF, a)
                buf[2 * dw + x] = lerp((p0 ushr 16) and 0xFF, (p1 ushr 16) and 0xFF, a)
            }
        }
        fun row(srcRow: Int): FloatArray {
            if (srcRow == rowA) return bufA
            if (srcRow == rowB) return bufB
            // Rows are requested in non-decreasing order: replace the older one.
            val t = bufA; bufA = bufB; bufB = t
            rowA = rowB
            rowB = srcRow
            hpass(srcRow, bufB)
            return bufB
        }
        for (y in 0 until dh) {
            val r0 = row(ay.i0[y])
            val r1 = row(ay.i1[y])
            val b = ay.a[y]
            for (c in 0 until 3) {
                val o = c * plane + y * dw
                val s = c * dw
                for (x in 0 until dw) out[o + x] = fma(r1[s + x] - r0[s + x], b, r0[s + x])
            }
        }
        return out
    }

    /** Horizontal pass on 8-bit channel values: the difference is exact, one rounding. */
    private fun lerp(p0: Int, p1: Int, a: Float): Float = fma((p1 - p0).toFloat(), a, p0.toFloat())

    /**
     * `a * b + c` on floats with a single rounding (IEEE fused multiply-add), in double arithmetic: `a * b` is exact
     * in double (24 + 24 significant bits) and the sum is rounded once to double. Rounding that double to float is
     * then correct unless the double landed exactly on a float midpoint while the exact sum did not; TwoSum gives the
     * exact remainder and decides that case. (Math.fma would do, but off the JIT intrinsic it goes through BigDecimal.)
     */
    internal fun fma(a: Float, b: Float, c: Float): Float {
        val p = a.toDouble() * b.toDouble()
        val cd = c.toDouble()
        val s = p + cd
        val r = s.toFloat()
        val rd = r.toDouble()
        if (rd == s) return r
        val other = if (s > rd) Math.nextUp(r) else Math.nextDown(r)
        if ((rd + other.toDouble()) * 0.5 != s) return r
        val bb = s - p
        val e = (p - (s - bb)) + (cd - bb) // s + e == a * b + c exactly
        if (e == 0.0) return r
        return if ((e > 0) == (s > rd)) other else r
    }
}
