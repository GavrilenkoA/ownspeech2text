package app.murmur

import android.content.Context
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.dictation.CaptureResult
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.store.AudioStore
import app.murmur.platform.audio.AndroidAudioModeSource
import app.murmur.platform.audio.CallWatch
import app.murmur.platform.audio.WavFileCapture
import java.util.concurrent.Executors

/**
 * Debug builds only (D6, ARCHITECTURE 16): the microphone, or WP-09's [WavFileCapture] when `files/debug-capture.wav`
 * exists. The file is checked at every start, so an end-to-end test can install a clip, replace it or remove it between
 * dictations without restarting the app (the emulator microphone cannot be fed).
 *
 * The WAV capture plays the clip in real time through the same capture session as the microphone but does not watch
 * the audio mode, so this adds WP-09's [CallWatch] on [AndroidAudioModeSource]: a call that starts during the recording
 * (`adb emu gsm call`, RINGTONE) raises [CaptureSignal.CallStarted] exactly as the microphone capture does
 * (ARCHITECTURE 5.2 call row, ADR-074).
 */
internal class DebugCapture(
    context: Context,
    private val microphone: AudioCapture,
    store: AudioStore,
) : AudioCapture {
    private val wav = WavFileCapture(context, store)
    private val modes = AndroidAudioModeSource(context)
    private val modeExecutor = Executors.newSingleThreadExecutor { Thread(it, "debug-capture-mode").apply { isDaemon = true } }

    override suspend fun start(session: SessionId, keepRecording: Boolean, audioKey: String): Outcome<CaptureHandle> {
        if (!wav.available) return microphone.start(session, keepRecording, audioKey)
        // The mode at the start: a call that is already up is not a reason to stop (CallWatch).
        val watch = CallWatch(modes.mode)
        val started = wav.start(session, keepRecording, audioKey)
        if (started !is Outcome.Ok) return started
        val observer = modes.observe(modeExecutor) { mode -> if (watch.onMode(mode)) wav.inject(CaptureSignal.CallStarted) }
        val handle = started.value
        return Outcome.Ok(object : CaptureHandle by handle {
            override suspend fun stop(): CaptureResult {
                observer.close()
                return handle.stop()
            }

            override fun cancel() {
                observer.close()
                handle.cancel()
            }
        })
    }
}
