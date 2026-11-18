package app.murmur.core.audio

/** Capture format of the whole app: 16 kHz, mono, PCM16 (VOICE_RECOGNITION source, platform spike). */
object PcmFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    /** 20 ms frames on the capture thread and over the :main -> :asr pipe. */
    const val FRAME_SAMPLES = 320

    fun msToSamples(ms: Long): Long = ms * SAMPLE_RATE / 1000
    fun samplesToMs(samples: Long): Long = samples * 1000 / SAMPLE_RATE
}

/** A block of PCM16 samples; [startSample] counts from the first captured sample of the session. */
class PcmChunk(val samples: ShortArray, val startSample: Long) {
    val endSample: Long get() = startSample + samples.size
}
