package app.murmur.infer.asr.qnnep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * GigaAM v3 front end: 64-bin log-mel features of 16 kHz audio, the input of the e2e CTC graph ([1, 64, frames]).
 * Port of native/qnn-contexts/gigaam_frontend.py (the reference; JVM parity test GigaAmFrontEndTest, max |d| <= 1e-4
 * on the FLEURS ru clips), which follows the model's own preprocessor (v3_e2e_ctc.yaml): torchaudio MelSpectrogram
 * with n_fft = win = 320, hop 160, center off, periodic Hann window, power 2, 64 HTK mel bands over 0..8 kHz without
 * norm, then ln(clamp(x, 1e-9, 1e9)). The checkpoint keeps the window and the filterbank in bfloat16, so both
 * constants are rounded to bf16 exactly as the model saw them in training (equal to onnx-asr 0.12.0's tables).
 * Arithmetic in double (a 320-point DFT as 5 x 64-point radix-2 FFTs), output float32. Thread-safe (tables only).
 */
object GigaAmFrontEnd {
    const val SAMPLE_RATE = 16_000
    const val N_FFT = 320
    const val WIN = 320
    const val HOP = 160
    const val N_MELS = 64
    private const val BINS = N_FFT / 2 + 1
    private const val CLAMP_MIN = 1e-9
    private const val CLAMP_MAX = 1e9

    /** The feature of digital silence (ln 1e-9): what a frame of zeros gives; pads a fixed window. */
    val SILENCE: Float = ln(CLAMP_MIN).toFloat()

    private val window: DoubleArray = DoubleArray(WIN) { n -> bf16(0.5 - 0.5 * cos(2.0 * PI * n / WIN)) }

    /** Mel band m covers FFT bins [lo[m], lo[m] + weights[m].size) with these bf16 weights (the rest are 0). */
    private val lo = IntArray(N_MELS)
    private val weights = arrayOfNulls<DoubleArray>(N_MELS)

    // 320 = 5 x 64: X[k] = sum_r W320^(r k) * FFT64(x[5 m + r])[k mod 64]
    private const val SUB = 64
    private const val RADIX = 5
    private val cos320 = DoubleArray(N_FFT) { cos(-2.0 * PI * it / N_FFT) }
    private val sin320 = DoubleArray(N_FFT) { sin(-2.0 * PI * it / N_FFT) }
    private val bitrev = IntArray(SUB) { Integer.reverse(it) ushr (32 - 6) }

    init {
        val fb = filterbank()
        for (m in 0 until N_MELS) {
            val nz = (0 until BINS).filter { fb[it][m] != 0.0 }
            lo[m] = nz.firstOrNull() ?: 0
            weights[m] = if (nz.isEmpty()) DoubleArray(0) else DoubleArray(nz.last() - lo[m] + 1) { fb[lo[m] + it][m] }
        }
    }

    /** Frames of [samples] samples: (samples - 320) / 160 + 1, 0 below one window. */
    fun frames(samples: Int): Int = if (samples < WIN) 0 else (samples - WIN) / HOP + 1

    /** Samples that make exactly [frames] frames. */
    fun samplesFor(frames: Int): Int = if (frames <= 0) 0 else (frames - 1) * HOP + WIN

    /**
     * Log-mel of samples[from, to) (16 kHz, [-1, 1]) laid out [64][outFrames] (bin-major, the graph's [1, 64, T]).
     * Frames beyond the audio are [pad]; audio beyond [outFrames] frames is not read.
     */
    fun features(samples: FloatArray, from: Int = 0, to: Int = samples.size, outFrames: Int = frames(to - from), pad: Float = SILENCE): FloatArray {
        require(from in 0..to && to <= samples.size && outFrames >= 0) { "range" }
        val n = min(frames(to - from), outFrames)
        val out = FloatArray(N_MELS * outFrames)
        if (n < outFrames) for (m in 0 until N_MELS) out.fill(pad, m * outFrames + n, (m + 1) * outFrames)
        val frame = DoubleArray(WIN)
        val power = DoubleArray(BINS)
        val re = Array(RADIX) { DoubleArray(SUB) }
        val im = Array(RADIX) { DoubleArray(SUB) }
        for (t in 0 until n) {
            val start = from + t * HOP
            for (i in 0 until WIN) frame[i] = samples[start + i].toDouble() * window[i]
            powerSpectrum(frame, re, im, power)
            for (m in 0 until N_MELS) {
                val w = weights[m]!!
                var e = 0.0
                val base = lo[m]
                for (j in w.indices) e += power[base + j] * w[j]
                out[m * outFrames + t] = ln(e.coerceIn(CLAMP_MIN, CLAMP_MAX)).toFloat()
            }
        }
        return out
    }

    /** |rfft(frame, 320)|^2 for bins 0..160. */
    private fun powerSpectrum(frame: DoubleArray, re: Array<DoubleArray>, im: Array<DoubleArray>, power: DoubleArray) {
        for (r in 0 until RADIX) {
            val a = re[r]
            val b = im[r]
            for (m in 0 until SUB) {
                a[bitrev[m]] = frame[RADIX * m + r]
                b[bitrev[m]] = 0.0
            }
            fft64(a, b)
        }
        for (k in 0 until BINS) {
            var sr = 0.0
            var si = 0.0
            val km = k and (SUB - 1)
            for (r in 0 until RADIX) {
                val idx = (r * k) % N_FFT
                val c = cos320[idx]
                val s = sin320[idx]
                val xr = re[r][km]
                val xi = im[r][km]
                sr += xr * c - xi * s
                si += xr * s + xi * c
            }
            power[k] = sr * sr + si * si
        }
    }

    /** In-place radix-2 DIT FFT of 64 points whose input is already in bit-reversed order. */
    private fun fft64(re: DoubleArray, im: DoubleArray) {
        var len = 2
        while (len <= SUB) {
            val half = len / 2
            val stride = N_FFT / len // W_len^j = exp(-2 pi i j / len) = W_320^(j * 320 / len); len divides 64, so 320 / len is whole
            for (i in 0 until SUB step len) {
                for (j in 0 until half) {
                    val tw = j * stride
                    val c = cos320[tw]
                    val s = sin320[tw]
                    val ur = re[i + j]
                    val ui = im[i + j]
                    val vr = re[i + j + half] * c - im[i + j + half] * s
                    val vi = re[i + j + half] * s + im[i + j + half] * c
                    re[i + j] = ur + vr
                    im[i + j] = ui + vi
                    re[i + j + half] = ur - vr
                    im[i + j + half] = ui - vi
                }
            }
            len *= 2
        }
    }

    /** torchaudio melscale_fbanks(161, 0, 8000, 64, 16000, norm=None, mel_scale="htk") in double, each value bf16: [161][64]. */
    internal fun filterbank(): Array<DoubleArray> {
        fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
        val fMax = SAMPLE_RATE / 2.0
        val freqs = linspace(0.0, fMax, BINS)
        val fPts = linspace(hzToMel(0.0), hzToMel(fMax), N_MELS + 2).map(::melToHz)
        return Array(BINS) { f ->
            DoubleArray(N_MELS) { m ->
                val down = -(fPts[m] - freqs[f]) / (fPts[m + 1] - fPts[m])
                val up = (fPts[m + 2] - freqs[f]) / (fPts[m + 2] - fPts[m + 1])
                bf16(max(0.0, min(down, up)))
            }
        }
    }

    /** numpy.linspace: start + i * step, the last value exactly [stop]. */
    private fun linspace(start: Double, stop: Double, num: Int): DoubleArray {
        val step = (stop - start) / (num - 1)
        return DoubleArray(num) { if (it == num - 1) stop else it * step + start }
    }

    /** The window as the model saw it (bf16 Hann). */
    internal fun windowValues(): DoubleArray = window.copyOf()

    /** double -> float32 -> bfloat16 (round to nearest, ties to even) -> double. */
    internal fun bf16(x: Double): Double {
        val bits = java.lang.Float.floatToRawIntBits(x.toFloat()).toLong() and 0xffffffffL
        val rounded = ((bits + 0x7fff + ((bits ushr 16) and 1)) ushr 16) shl 16
        return java.lang.Float.intBitsToFloat(rounded.toInt()).toDouble()
    }
}
