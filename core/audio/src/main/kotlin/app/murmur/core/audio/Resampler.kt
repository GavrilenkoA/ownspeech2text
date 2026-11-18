package app.murmur.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rational polyphase resampler (windowed sinc, Blackman window), zero phase: output sample n sits exactly at input
 * time n * from / to, so a resampled signal lines up with the original sample for sample. Used to bring decoded
 * history audio (the Opus decoder always outputs 48 kHz; AAC and WAV files may use any rate) back to the 16 kHz the
 * ASR models take. The low-pass cut-off is [cutoff] x the lower Nyquist frequency.
 */
object Resampler {
    /** Zero crossings of the sinc on each side, at the lower of the two rates. */
    private const val HALF_ZEROS = 16
    private const val MAX_PHASES = 4096

    fun resample(input: ShortArray, fromRate: Int, toRate: Int, cutoff: Double = 0.92): ShortArray {
        require(fromRate > 0 && toRate > 0) { "rates" }
        require(cutoff > 0 && cutoff <= 1) { "cutoff" }
        if (fromRate == toRate) return input.copyOf()
        if (input.isEmpty()) return ShortArray(0)
        val g = gcd(fromRate, toRate)
        val up = toRate / g
        val down = fromRate / g
        require(up <= MAX_PHASES) { "unsupported rate pair $fromRate -> $toRate" }

        // Filter in input-sample units: cut-off as a fraction of the input Nyquist.
        val scale = minOf(1.0, toRate.toDouble() / fromRate)
        val fc = cutoff * scale
        val half = (HALF_ZEROS / scale).toInt()
        val taps = 2 * half + 1
        // Phase p: output position = base + p/up; kernel tap j covers input base - half + j.
        val table = Array(up) { p ->
            val frac = p.toDouble() / up
            val k = DoubleArray(taps) { j -> kernel((j - half) - frac, fc, half + 1.0) }
            val sum = k.sum()
            DoubleArray(taps) { k[it] / sum }
        }

        val outLen = ((input.size.toLong() * up + down - 1) / down).toInt()
        val out = ShortArray(outLen)
        for (n in 0 until outLen) {
            val pos = n.toLong() * down
            val base = (pos / up).toInt()
            val h = table[(pos % up).toInt()]
            var acc = 0.0
            val start = base - half
            for (j in 0 until taps) {
                val idx = start + j
                if (idx < 0 || idx >= input.size) continue
                acc += input[idx] * h[j]
            }
            out[n] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Average of interleaved channels (decoders may hand back stereo for a mono file). */
    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        require(channels >= 1) { "channels" }
        if (channels == 1) return interleaved
        val frames = interleaved.size / channels
        return ShortArray(frames) { f ->
            var acc = 0
            for (c in 0 until channels) acc += interleaved[f * channels + c]
            (acc / channels).toShort()
        }
    }

    private fun kernel(x: Double, fc: Double, width: Double): Double {
        val sinc = if (x == 0.0) fc else sin(PI * fc * x) / (PI * x)
        val t = (x / width + 1) / 2 // 0..1 across the window
        if (t <= 0 || t >= 1) return 0.0
        val w = 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        return sinc * w
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
