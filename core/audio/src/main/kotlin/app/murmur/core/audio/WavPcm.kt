package app.murmur.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader and writer for 16-bit PCM (format 1, or WAVE_FORMAT_EXTENSIBLE with the PCM sub-format).
 * Used by the debug WAV capture (end-to-end tests feed a known clip) and by tests; never on the production path.
 */
object WavPcm {
    data class Clip(val sampleRate: Int, val channels: Int, /** Interleaved PCM16. */ val samples: ShortArray) {
        val frames: Int get() = samples.size / channels

        /** Mono 16 kHz, the capture format of the app. */
        fun toCaptureFormat(): ShortArray =
            Resampler.resample(Resampler.downmix(samples, channels), sampleRate, PcmFormat.SAMPLE_RATE)
    }

    class FormatException(message: String) : Exception(message)

    fun decode(bytes: ByteArray): Clip {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") throw FormatException("not a RIFF/WAVE file")
        var pos = 12
        var rate = -1
        var channels = -1
        var data: ShortArray? = null
        while (pos + 8 <= bytes.size) {
            val id = tag(b, pos)
            val size = b.getInt(pos + 4).toLong() and 0xffffffffL
            val body = pos + 8
            val end = minOf(bytes.size.toLong(), body + size).toInt()
            when (id) {
                "fmt " -> {
                    if (size < 16) throw FormatException("short fmt chunk")
                    val format = b.getShort(body).toInt() and 0xffff
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    val bits = b.getShort(body + 14).toInt()
                    val pcm = format == 1 || (format == 0xFFFE && size >= 26 && b.getShort(body + 24).toInt() == 1)
                    if (!pcm || bits != 16) throw FormatException("only 16-bit PCM is supported (format $format, $bits bit)")
                    if (channels < 1 || rate <= 0) throw FormatException("bad fmt values")
                }
                "data" -> {
                    val n = (end - body) / 2
                    data = ShortArray(n) { b.getShort(body + it * 2) }
                }
            }
            val next = body + ((size + 1) and 1L.inv()) // chunks are word aligned
            if (next > bytes.size) break
            pos = next.toInt()
        }
        if (rate < 0) throw FormatException("no fmt chunk")
        val pcm = data ?: throw FormatException("no data chunk")
        return Clip(rate, channels, pcm.copyOf(pcm.size - pcm.size % channels))
    }

    fun encode(samples: ShortArray, sampleRate: Int = PcmFormat.SAMPLE_RATE, channels: Int = 1): ByteArray {
        val dataBytes = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in samples) b.putShort(s)
        return b.array()
    }

    private fun tag(b: ByteBuffer, at: Int): String =
        String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
}
