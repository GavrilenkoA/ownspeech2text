package app.murmur.core.benchmark

import app.murmur.core.ocr.ScreenImage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Minimal PNG reader for the bundled benchmark pages (pure JVM: `javax.imageio` does not exist on Android and
 * `BitmapFactory` does not exist in a core module). Supports what the OCR golden suite uses and nothing more:
 * 8-bit greyscale, RGB and RGBA, non-interlaced, all five filter types. Pixels come out as [ScreenImage]'s
 * ARGB_8888 (alpha forced to 0xFF: OCR drops alpha, ml/ocr README section 1).
 */
object Png {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun decode(bytes: ByteArray, exclude: List<app.murmur.core.ocr.Rect> = emptyList()): ScreenImage {
        val input = DataInputStream(bytes.inputStream())
        val sig = ByteArray(8).also { input.readFully(it) }
        if (!sig.contentEquals(SIGNATURE)) throw IOException("not a PNG")
        var width = 0; var height = 0; var colorType = -1
        val idat = ByteArrayOutputStream()
        while (true) {
            val length = input.readInt()
            val type = ByteArray(4).also { input.readFully(it) }
            val data = ByteArray(length).also { input.readFully(it) }
            val crc = input.readInt()
            val check = CRC32().apply { update(type); update(data) }
            if (check.value.toInt() != crc) throw IOException("PNG chunk CRC mismatch")
            when (String(type, Charsets.US_ASCII)) {
                "IHDR" -> {
                    val h = DataInputStream(data.inputStream())
                    width = h.readInt(); height = h.readInt()
                    val depth = h.readUnsignedByte(); colorType = h.readUnsignedByte()
                    val compression = h.readUnsignedByte(); val filter = h.readUnsignedByte(); val interlace = h.readUnsignedByte()
                    if (depth != 8 || colorType !in setOf(0, 2, 6) || compression != 0 || filter != 0 || interlace != 0) {
                        throw IOException("unsupported PNG: depth $depth, colour type $colorType, interlace $interlace")
                    }
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        if (width <= 0 || height <= 0) throw IOException("PNG without IHDR")
        val channels = when (colorType) { 0 -> 1; 2 -> 3; else -> 4 }
        val stride = width * channels
        val raw = inflate(idat.toByteArray(), (stride + 1) * height)
        val argb = IntArray(width * height)
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        for (y in 0 until height) {
            val off = y * (stride + 1)
            val filter = raw[off].toInt()
            System.arraycopy(raw, off + 1, cur, 0, stride)
            unfilter(filter, cur, prev, channels)
            for (x in 0 until width) {
                val p = x * channels
                val r = cur[p].toInt() and 0xFF
                val g = if (channels == 1) r else cur[p + 1].toInt() and 0xFF
                val b = if (channels == 1) r else cur[p + 2].toInt() and 0xFF
                argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val t = prev; prev = cur; cur = t
        }
        return ScreenImage(width, height, argb, exclude)
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inflater.finished()) {
                val k = inflater.inflate(out, n, expected - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            if (n != expected) throw IOException("PNG data is $n bytes, expected $expected")
            return out
        } finally {
            inflater.end()
        }
    }

    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        when (filter) {
            0 -> Unit
            1 -> for (i in bpp until cur.size) cur[i] = (cur[i] + cur[i - bpp]).toByte()
            2 -> for (i in cur.indices) cur[i] = (cur[i] + prev[i]).toByte()
            3 -> for (i in cur.indices) {
                val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + ((left + (prev[i].toInt() and 0xFF)) ushr 1)).toByte()
            }
            4 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + paeth(a, b, c)).toByte()
            }
            else -> throw IOException("bad PNG filter $filter")
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
