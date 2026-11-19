package app.murmur.feature.history

import kotlin.math.abs
import kotlin.math.max

/** Waveform peaks for the audio player (DESIGN 4: bars from the recording's real peaks). */
internal object Peaks {
    /** Default resolution; the player resamples to the bars that fit its width (max per bucket). */
    const val BUCKETS = 240

    /**
     * Max |sample| per bucket of 16-bit PCM, normalised to the loudest bucket (linear, so loud and quiet syllables
     * read as such). Silence stays 0.
     */
    fun of(pcm: ShortArray, buckets: Int = BUCKETS): FloatArray {
        if (pcm.isEmpty() || buckets <= 0) return FloatArray(0)
        val n = minOf(buckets, pcm.size)
        val raw = FloatArray(n)
        for (b in 0 until n) {
            val from = (b.toLong() * pcm.size / n).toInt()
            val to = max(from + 1, ((b + 1).toLong() * pcm.size / n).toInt())
            var m = 0
            for (k in from until to) m = max(m, abs(pcm[k].toInt()))
            raw[b] = m.toFloat()
        }
        val top = raw.max()
        if (top <= 0f) return FloatArray(n)
        return FloatArray(n) { raw[it] / top }
    }
}
