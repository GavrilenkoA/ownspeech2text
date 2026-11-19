package app.murmur.platform.audio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.PcmMath
import app.murmur.core.audio.WavPcm
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.platform.audio.test.Shell
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** The debug-only WAV capture WP-20's end-to-end tests use instead of the (unfeedable) emulator microphone. */
@RunWith(AndroidJUnit4::class)
class WavFileCaptureTest {
    private val context = Shell.context
    private val store = OpusAudioStore(context, dir = File(context.filesDir, "history/audio-wav-test").also { it.deleteRecursively() })

    @Test
    fun playsTheClipInRealTimeThroughTheNormalPath() = runBlocking {
        // 1 s at 48 kHz stereo: the capture converts to 16 kHz mono.
        val stereo = ShortArray(48_000 * 2) { i -> (7000 * sin(2 * PI * 440 * (i / 2) / 48_000)).toInt().toShort() }
        val capture = WavFileCapture(context, store, "wp09-test.wav")
        File(context.filesDir, capture.fileName).writeBytes(WavPcm.encode(stereo, 48_000, 2))
        assertTrue(capture.available)

        val t0 = SystemClock.uptimeMillis()
        val h = (capture.start(SessionId(1), keepRecording = true, audioKey = "1763500000000-1") as Outcome.Ok).value
        val first50 = withTimeout(5_000) { h.chunks.take(50).toList() }
        val elapsed = SystemClock.uptimeMillis() - t0
        assertTrue("50 frames paced in real time: $elapsed ms", elapsed in 950..1_400)
        assertTrue(first50.all { it.samples.size == PcmFormat.FRAME_SAMPLES })
        val got = first50.map { it.samples }.reduce { a, b -> a + b }
        val ref = ShortArray(16_000) { (7000 * sin(2 * PI * 440 * it / 16_000)).toInt().toShort() }
        assertTrue("content is the clip", PcmMath.snr(ref, got, 2, edge = 200).db > 30)
        assertTrue("level bars move", h.levels.value[2] > 0.3f)

        assertTrue(capture.inject(CaptureSignal.CallStarted))
        assertEquals(CaptureSignal.CallStarted, withTimeout(1_000) { h.signals.first() })
        Thread.sleep(300) // past the clip: digital silence keeps coming
        val r = h.stop()
        assertTrue(r.durationMs >= 1_000)
        assertTrue("peak ${r.peakAbs}", r.peakAbs in 6_800..7_200)
        val stored = store.readPcm(r.audio!!)!!
        assertTrue(stored.size >= r.durationMs * 16 * 99 / 100)
        assertFalse("no capture after stop", capture.inject(CaptureSignal.Silenced))
    }

    @Test
    fun missingClipFailsToStart() = runBlocking {
        val capture = WavFileCapture(context, store, "does-not-exist.wav")
        assertFalse(capture.available)
        val o = capture.start(SessionId(2), keepRecording = false, audioKey = "x")
        assertEquals(Outcome.Err(MurmurError.MicStartFailed("debug_wav")), o)
    }

    @Test
    fun cancelLeavesNoFile() = runBlocking {
        val capture = WavFileCapture(context, store, "wp09-cancel.wav")
        File(context.filesDir, capture.fileName).writeBytes(WavPcm.encode(ShortArray(16_000) { (it % 100 * 50).toShort() }))
        val h = (capture.start(SessionId(3), keepRecording = true, audioKey = "1763500000000-3") as Outcome.Ok).value
        Thread.sleep(400)
        h.cancel()
        Thread.sleep(500)
        assertTrue(store.dir.listFiles().isNullOrEmpty())
        assertArrayEquals(emptyArray<String>(), store.listAll().map { it.path }.toTypedArray())
    }
}
