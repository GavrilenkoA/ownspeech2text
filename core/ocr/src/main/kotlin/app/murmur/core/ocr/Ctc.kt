package app.murmur.core.ocr

import kotlin.math.abs

/** Greedy CTC decoding (ml/ocr README section 5.5, pipeline.py `ctc_decode`). */
internal object Ctc {
    /**
     * [probs] = softmax rows [steps x classes] starting at [offset]. Argmax per step (first maximum on ties), merge
     * consecutive repeats, drop class 0; conf = mean of the kept steps' maxima (0.0 when nothing is kept).
     * [chars] = "" (blank), the dictionary, " " (space).
     */
    fun decode(probs: FloatArray, offset: Int, steps: Int, classes: Int, chars: List<String>): Pair<String, Double> {
        val text = StringBuilder()
        val confs = DoubleArray(steps)
        var kept = 0
        var prev = -1
        for (t in 0 until steps) {
            val row = offset + t * classes
            var k = 0
            var best = probs[row]
            for (c in 1 until classes) {
                val v = probs[row + c]
                if (v > best) { best = v; k = c }
            }
            if (k != prev && k != 0) {
                text.append(chars[k])
                confs[kept++] = best.toDouble()
            }
            prev = k
        }
        return text.toString() to (if (kept == 0) 0.0 else pySum(confs, kept) / kept)
    }

    /**
     * Python's built-in `sum()` of floats (CPython >= 3.12: Neumaier compensated summation, compensation added at the
     * end when non-zero and finite). The reference averages the CTC maxima with it.
     */
    fun pySum(v: DoubleArray, n: Int = v.size): Double {
        if (n == 0) return 0.0
        var s = v[0]
        var c = 0.0
        for (i in 1 until n) {
            val x = v[i]
            val t = s + x
            c += if (abs(s) >= abs(x)) (s - t) + x else (x - t) + s
            s = t
        }
        if (c != 0.0 && c.isFinite()) s += c
        return s
    }
}
