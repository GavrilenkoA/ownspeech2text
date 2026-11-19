package app.murmur.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * [Bilinear.resizeBgr] against cv2 5.1 `cv2.resize(INTER_LINEAR)` on float32 (ml/ocr's resize), non-integer scales
 * down and up, a crop, the detector cover size and a recogniser crop. Reference: resources/bilinear/cv2_reference.bin.gz
 * (written by make_reference.py next to it): every value must be bit-identical.
 */
class BilinearTest {
    private class Case(val imgW: Int, val imgH: Int, val x0: Int, val y0: Int, val w: Int, val h: Int, val dw: Int, val dh: Int,
                       val rows: IntArray, val want: FloatArray)

    private fun cases(): List<Case> {
        val raw = javaClass.getResourceAsStream("/bilinear/cv2_reference.bin.gz")!!.use { GZIPInputStream(it).readBytes() }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return List(b.int) {
            val head = IntArray(9) { b.int }
            val rows = IntArray(head[8]) { b.int }
            val want = FloatArray(rows.size * 3 * head[6]) { b.float }
            Case(head[0], head[1], head[2], head[3], head[4], head[5], head[6], head[7], rows, want)
        }.also { check(!b.hasRemaining()) }
    }

    /** The generator's pixel hash: low 8 bits of products and xors (identical for Python ints and Kotlin Ints). */
    private fun image(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        fun ch(c: Int) = ((x * 73856093) xor (y * 19349663) xor (c * 83492791) xor (x * y * 2654435761L.toInt())) and 0xFF
        (0xFF shl 24) or (ch(2) shl 16) or (ch(1) shl 8) or ch(0)
    }

    private fun ulps(a: Float, b: Float): Int = abs(a.toRawBits() - b.toRawBits())

    @Test
    fun matchesOpenCvLinearResizeOnFloat32() {
        val all = cases()
        assertEquals(4, all.size)
        var values = 0
        for (c in all) {
            val got = Bilinear.resizeBgr(image(c.imgW, c.imgH), c.imgW, c.x0, c.y0, c.w, c.h, c.dw, c.dh)
            val plane = c.dw * c.dh
            c.rows.forEachIndexed { k, y ->
                for (ch in 0 until 3) for (x in 0 until c.dw) {
                    val want = c.want[(k * 3 + ch) * c.dw + x]
                    val v = got[ch * plane + y * c.dw + x]
                    if (want.toRawBits() != v.toRawBits()) {
                        throw AssertionError("${c.w}x${c.h}->${c.dw}x${c.dh} y=$y c=$ch x=$x: $v vs cv2 $want (${ulps(want, v)} ulps)")
                    }
                    values++
                }
            }
        }
        println("bilinear vs cv2 5.1: $values of $values values bit-identical")
    }

    /** Random sign and mantissa, exponent 2^-30..2^29. */
    private fun anyFloat(rnd: java.util.Random) =
        Float.fromBits((rnd.nextInt() and 0x807FFFFF.toInt()) or ((rnd.nextInt(60) + 97) shl 23))

    /** [Bilinear.fma] is a correctly rounded float fused multiply-add: the JVM's Math.fma (exact) is the oracle. */
    @Test
    fun fmaRoundsOnce() {
        fun check(a: Float, b: Float, c: Float) {
            val want = Math.fma(a, b, c)
            val got = Bilinear.fma(a, b, c)
            if (want.toRawBits() != got.toRawBits()) throw AssertionError("fma($a, $b, $c) = $got, want $want")
        }
        // Double rounding: a*b + c rounds to a float midpoint in double although the exact sum lies above it, so
        // (float)(double) a*b + c gives 1.0 where the single rounding gives 1 + 2^-23.
        val a = java.lang.Float.parseFloat("0x1.6a1e42p-25")
        val b = java.lang.Float.parseFloat("0x1.69f58cp0")
        assertEquals(1f, (a.toDouble() * b + 1.0).toFloat(), 0f)
        assertEquals(java.lang.Float.parseFloat("0x1.000002p0"), Bilinear.fma(a, b, 1f), 0f)
        check(a, b, 1f)
        check(-a, b, Math.nextUp(1f))
        check(a, -b, -1f)
        // The resize's domain (8-bit values, weights in [0, 1), float row values) and wide random exponents.
        val rnd = java.util.Random(7)
        repeat(2_000_000) {
            val w = rnd.nextFloat()
            val p0 = rnd.nextInt(256).toFloat()
            check((rnd.nextInt(511) - 255).toFloat(), w, p0)
            val r0 = rnd.nextFloat() * 255f
            check(rnd.nextFloat() * 255f - r0, w, r0)
            check(anyFloat(rnd), anyFloat(rnd), anyFloat(rnd))
        }
    }
}
