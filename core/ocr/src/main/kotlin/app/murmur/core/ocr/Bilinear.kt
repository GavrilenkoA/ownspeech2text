package app.murmur.core.ocr

import kotlin.math.floor

/**
 * Bilinear resize with half-pixel centres computed in float32, the semantics of OpenCV `cv2.resize(INTER_LINEAR)` on
 * a float32 image that ml/ocr uses (README section 2.3):
 *  - per axis `src = (dst + 0.5) * scale - 0.5` with `scale = 1 / (dst / src)` in double, `i = floor(src)`, weight
 *    `a = (float)(src - i)`, `1 - a` in float (OpenCV 5 computes the offsets in double; an impulse probe of
 *    cv2 5.1 reproduces these weights exactly);
 *  - x: an offset left of the first or right of the last column is clamped together with its weight (edge pixel);
 *    y: row indices are clamped, weights are kept (OpenCV's vertical pass);
 *  - horizontal pass first (`S[i]*a0 + S[i+1]*a1`), then the vertical blend of two such rows, all in float.
 * Against cv2 5.1 the result differs by at most 1-2 float ulps on some pixels (OpenCV's SIMD blend), far below
 * anything the networks can see.
 */
internal object Bilinear {
    class Axis(val i0: IntArray, val i1: IntArray, val w0: FloatArray, val w1: FloatArray)

    fun axis(src: Int, dst: Int, clampWeights: Boolean): Axis {
        require(src > 0 && dst > 0)
        val scale = 1.0 / (dst.toDouble() / src)
        val i0 = IntArray(dst)
        val i1 = IntArray(dst)
        val w0 = FloatArray(dst)
        val w1 = FloatArray(dst)
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
            w0[d] = 1f - a
            w1[d] = a
        }
        return Axis(i0, i1, w0, w1)
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
                val a0 = ax.w0[x]
                val a1 = ax.w1[x]
                buf[x] = (p0 and 0xFF).toFloat() * a0 + (p1 and 0xFF).toFloat() * a1
                buf[dw + x] = ((p0 ushr 8) and 0xFF).toFloat() * a0 + ((p1 ushr 8) and 0xFF).toFloat() * a1
                buf[2 * dw + x] = ((p0 ushr 16) and 0xFF).toFloat() * a0 + ((p1 ushr 16) and 0xFF).toFloat() * a1
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
            val b0 = ay.w0[y]
            val b1 = ay.w1[y]
            for (c in 0 until 3) {
                val o = c * plane + y * dw
                val s = c * dw
                for (x in 0 until dw) out[o + x] = r0[s + x] * b0 + r1[s + x] * b1
            }
        }
        return out
    }
}
