package app.murmur.core.store

/**
 * History audio (D9: compressed audio, deleted with its entry). Real: Opus in Ogg via MediaCodec +
 * MediaMuxer in :main (ADR-018), files under filesDir/history/audio/<key>.ogg. The writer receives the same PCM
 * the ASR sees.
 */
interface AudioStore {
    /**
     * [key] = AudioKey.of(createdAtEpochMs, session).value. Never the bare SessionId: it restarts with :main and
     * would overwrite an older entry's audio (review 2026-11-19).
     */
    fun newWriter(key: String): AudioWriter
    suspend fun delete(ref: AudioRef)
    /** Raw PCM16 16 kHz for Retry / Re-polish (decoded from the stored file). */
    suspend fun readPcm(ref: AudioRef): ShortArray?
    suspend fun exists(ref: AudioRef): Boolean
    /** Every stored file, for the orphan cleanup at app start (files without a history row are deleted, WP-12). */
    suspend fun listAll(): List<AudioRef>
}

interface AudioWriter {
    fun write(samples: ShortArray, count: Int)
    /** Finish encoding; null when nothing was written or keepRecordings is off. */
    suspend fun close(): AudioRef?
    fun discard()
}
