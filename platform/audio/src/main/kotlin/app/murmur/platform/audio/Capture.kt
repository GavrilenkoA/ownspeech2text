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
 * the :asr pipe (via the ASR session), the history AudioWriter, and the LevelMeter. At start: isClientSilenced and an
 * all-zero first second -> MicSilenced. Mid-recording: CaptureHandle.signals (Silenced, Lost, CallStarted from
 * AudioManager mode changes). WP-09.
 */
class AndroidAudioCapture(private val context: Context, private val store: AudioStore) : AudioCapture {
    override suspend fun start(session: SessionId, keepRecording: Boolean, audioKey: String): Outcome<CaptureHandle> = TODO("WP-09")
}

/** Typed microphone FGS (FOREGROUND_SERVICE_TYPE_MICROPHONE, FOREGROUND_SERVICE_DEFERRED). WP-09. */
class RecordingFgs : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * History audio: Opus in Ogg, mono 16 kHz, ~24 kbit/s, MediaCodec "audio/opus" + MediaMuxer OGG (API 29+),
 * files in filesDir/history/audio/<AudioKey>.ogg (ADR-018). Decoding back to PCM for Retry/Re-polish via
 * MediaExtractor + MediaCodec. WP-09 verifies encode/decode on the emulator and falls back to AAC/M4A if the
 * Opus encoder is missing.
 */
class OpusAudioStore(private val context: Context) : AudioStore {
    override fun newWriter(key: String): AudioWriter = TODO("WP-09")
    override suspend fun delete(ref: AudioRef) = TODO("WP-09")
    override suspend fun readPcm(ref: AudioRef): ShortArray? = TODO("WP-09")
    override suspend fun exists(ref: AudioRef): Boolean = TODO("WP-09")
    override suspend fun listAll(): List<AudioRef> = TODO("WP-09")
}

/**
 * The persistent idle notification of DESIGN 5.1 (channel "Murmur status": "Murmur is ready", models subtitle,
 * "Hide 1 h", "Start dictation", "Show now" while hidden). Not the FGS notification (that one exists only while
 * recording, I3). WP-09.
 */
class AndroidStatusNotifier(private val context: Context) : app.murmur.core.dictation.StatusNotifier {
    override fun show(modelsLine: String, hiddenUntilEpochMs: Long?) = TODO("WP-09")
    override fun hide() = TODO("WP-09")
}

/** Shared chunk type re-export to keep the capture API in one place. */
typealias Chunk = PcmChunk
