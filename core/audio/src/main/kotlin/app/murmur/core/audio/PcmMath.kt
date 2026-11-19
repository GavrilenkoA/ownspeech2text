package app.murmur.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Small PCM16 measurements shared by the capture (peak), the store checks and the codec tests (SNR). */
object PcmMath {
    fun peakAbs(data: ShortArray, count: Int = data.size): Int {
        var peak = 0
        for (i in 0 until count) {
            val a = abs(data[i].toInt())
            if (a > peak) peak = a
        }
        return peak
    }

    fun rms(data: ShortArray, from: Int = 0, to: Int = data.size): Double {
        if (to <= from) return 0.0
        var acc = 0.0
        for (i in from until to) acc += data[i].toDouble() * data[i]
        return sqrt(acc / (to - from))
    }

    /** SNR of [decoded] against [reference] at the best lag in -[maxLag]..[maxLag] (decoded[i + lag] ~ reference[i]). */
    data class Snr(val db: Double, val lag: Int, val comparedSamples: Int)

    /**
     * Plain waveform SNR (no gain fitting): 10 log10(sum ref^2 / sum (ref - dec)^2) over the overlap, skipping [edge]
     * samples at both ends (codec ramp-in/out). The lag is chosen on a [searchWindow]-sample excerpt from the middle
     * (same error measure), then the SNR is computed over the whole overlap at that lag.
     */
    fun snr(reference: ShortArray, decoded: ShortArray, maxLag: Int, edge: Int = 0, searchWindow: Int = 16_000): Snr {
        val mid = reference.size / 2
        val wFrom = maxOf(edge, mid - searchWindow / 2)
        val wTo = minOf(reference.size - edge, wFrom + searchWindow)
        var bestLag = 0
        var bestDb = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            val db = snrAt(reference, decoded, lag, wFrom, wTo).db
            if (db > bestDb) { bestDb = db; bestLag = lag }
        }
        return snrAt(reference, decoded, bestLag, edge, reference.size - edge)
    }

    private fun snrAt(reference: ShortArray, decoded: ShortArray, lag: Int, fromIdx: Int, toIdx: Int): Snr {
        val from = maxOf(fromIdx, -lag)
        val to = minOf(toIdx, decoded.size - lag)
        if (to - from <= 0) return Snr(Double.NEGATIVE_INFINITY, lag, 0)
        var sig = 0.0
        var err = 0.0
        for (i in from until to) {
            val r = reference[i].toDouble()
            val d = r - decoded[i + lag]
            sig += r * r
            err += d * d
        }
        val db = if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
        return Snr(db, lag, to - from)
    }
}
