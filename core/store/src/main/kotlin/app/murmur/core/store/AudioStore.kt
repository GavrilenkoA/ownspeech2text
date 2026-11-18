package app.murmur.core.store

/**
 * History audio (D9: compressed audio, deleted with its entry). Real: Opus in Ogg via MediaCodec +
 * MediaMuxer in :main (ADR-018), files under filesDir/history/audio/. The writer receives the same PCM
 * the ASR sees.
 */
interface AudioStore {
    fun newWriter(sessionId: Long): AudioWriter
    suspend fun delete(ref: AudioRef)
    /** Raw PCM16 16 kHz for Retry / Re-polish (decoded from the stored file). */
    suspend fun readPcm(ref: AudioRef): ShortArray?
    suspend fun exists(ref: AudioRef): Boolean
}

interface AudioWriter {
    fun write(samples: ShortArray, count: Int)
    /** Finish encoding; null when nothing was written or keepRecordings is off. */
    suspend fun close(): AudioRef?
    fun discard()
}
