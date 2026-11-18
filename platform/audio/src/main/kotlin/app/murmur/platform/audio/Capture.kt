package app.murmur.platform.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.murmur.core.audio.PcmChunk
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.AudioWriter

/**
 * AudioRecord(VOICE_RECOGNITION, 16 kHz mono PCM16) on a THREAD_PRIORITY_URGENT_AUDIO thread in :main,
 * started together with [RecordingFgs] (lift spikes/platform Recorder.kt + RecordingFgs.kt). Chunks go to:
 * the :asr pipe (via the ASR session), the history AudioWriter, and the LevelMeter. isClientSilenced and an
 * all-zero first second -> MicSilenced. WP-09.
 */
class AndroidAudioCapture(private val context: Context, private val store: AudioStore) : AudioCapture {
    override suspend fun start(session: SessionId, keepRecording: Boolean): Outcome<CaptureHandle> = TODO("WP-09")
}

/** Typed microphone FGS (FOREGROUND_SERVICE_TYPE_MICROPHONE, FOREGROUND_SERVICE_DEFERRED). WP-09. */
class RecordingFgs : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * History audio: Opus in Ogg, mono 16 kHz, ~24 kbit/s, MediaCodec "audio/opus" + MediaMuxer OGG (API 29+),
 * files in filesDir/history/audio/<session>.ogg (ADR-018). Decoding back to PCM for Retry/Re-polish via
 * MediaExtractor + MediaCodec. WP-09 verifies encode/decode on the emulator and falls back to AAC/M4A if the
 * Opus encoder is missing.
 */
class OpusAudioStore(private val context: Context) : AudioStore {
    override fun newWriter(sessionId: Long): AudioWriter = TODO("WP-09")
    override suspend fun delete(ref: AudioRef) = TODO("WP-09")
    override suspend fun readPcm(ref: AudioRef): ShortArray? = TODO("WP-09")
    override suspend fun exists(ref: AudioRef): Boolean = TODO("WP-09")
}

/** Shared chunk type re-export to keep the capture API in one place. */
typealias Chunk = PcmChunk
