package app.murmur.platform.audio

import android.content.Context
import android.os.Process
import android.os.SystemClock
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.WavPcm
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.store.AudioStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug builds only (this source set never ships): an [AudioCapture] that plays `filesDir/<fileName>` (16-bit PCM WAV,
 * any rate / channel count, converted to 16 kHz mono) in real time, 20 ms per frame, through the same
 * [CaptureSession] as the microphone: session buffer, history writer (when keepRecording), level meter. After the clip
 * it keeps delivering digital silence until stopped, like a microphone in a quiet room. For WP-20's end-to-end tests
 * on the emulator, whose microphone cannot be fed. [inject] puts a [CaptureSignal] on the running capture (call
 * started, mic lost, silenced) to drive the ExternalStop paths.
 */
class WavFileCapture(
    private val context: Context,
    private val store: AudioStore,
    val fileName: String = DEFAULT_FILE,
) : AudioCapture {
    @Volatile private var current: CaptureSession? = null

    /** True when the clip exists (the debug container swaps this capture in only then). */
    val available: Boolean get() = File(context.filesDir, fileName).isFile

    override suspend fun start(session: SessionId, keepRecording: Boolean, audioKey: String): Outcome<CaptureHandle> {
        val pcm = withContext(Dispatchers.IO) {
            runCatching { WavPcm.decode(File(context.filesDir, fileName).readBytes()).toCaptureFormat() }.getOrNull()
        } ?: return Outcome.Err(MurmurError.MicStartFailed("debug_wav"))
        val writer = if (keepRecording) store.newWriter(audioKey) else null
        val source = PacedSource(pcm)
        val capture = CaptureSession(writer, source)
        current = capture
        capture.doOnEnd { if (current === capture) current = null }
        source.begin(capture)
        return Outcome.Ok(capture)
    }

    /** Emits [signal] on the running capture; false when none runs. */
    fun inject(signal: CaptureSignal): Boolean {
        val c = current ?: return false
        c.signal(signal)
        return true
    }

    private class PacedSource(private val pcm: ShortArray) : CaptureSession.Source {
        @Volatile private var running = true
        override val finished = CompletableDeferred<Unit>()
        override fun halt() { running = false }

        fun begin(session: CaptureSession) = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                val frame = ShortArray(PcmFormat.FRAME_SAMPLES)
                val t0 = SystemClock.uptimeMillis()
                var i = 0L
                while (running) {
                    // Absolute schedule: frame i is due at t0 + 20 ms x i, so the pace never drifts.
                    val due = t0 + i * FRAME_MS
                    val wait = due - SystemClock.uptimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                    if (!running) break
                    val from = i * PcmFormat.FRAME_SAMPLES
                    frame.fill(0)
                    if (from < pcm.size) {
                        val to = minOf(pcm.size.toLong(), from + PcmFormat.FRAME_SAMPLES).toInt()
                        pcm.copyInto(frame, 0, from.toInt(), to)
                    }
                    session.onFrame(frame, frame.size)
                    i++
                }
            } catch (_: InterruptedException) {
            } finally {
                finished.complete(Unit)
            }
        }, "audio-wav").start()
    }

    companion object {
        const val DEFAULT_FILE = "debug-capture.wav"
        private const val FRAME_MS = 20L
    }
}
