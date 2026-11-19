package app.murmur.platform.audio

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.platform.audio.test.EmulatorMic
import app.murmur.platform.audio.test.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * The input stream dies mid-recording: the host breaks the audio server (platform/audio/scripts/emulator_mic_lost.sh,
 * which needs `adb root` and passes `-e wp09.miclost kill|stall`). The capture must never stall silently.
 * - kill: either AudioRecord restores itself and samples keep coming (no gap of 1 s or more), or
 *   CaptureSignal.Lost(MicLost) arrives;
 * - stall (the server is frozen for 2.5 s): reads block, and Lost(MicLost("stall")) must arrive within 1.5 s of the
 *   last chunk.
 */
@RunWith(AndroidJUnit4::class)
class MicLostTest {
    @Test
    fun aDeadAudioServerIsALossOrARecoveryNeverASilentStall() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("wp09.miclost")
        assumeTrue("needs the host to break the audio server: run platform/audio/scripts/emulator_mic_lost.sh", mode != null)
        val context = Shell.context
        Shell.grant(Manifest.permission.RECORD_AUDIO)
        EmulatorMic.warmUp()
        val store = OpusAudioStore(context, dir = File(context.filesDir, "history/audio-lost-test"))
        val h = (AndroidAudioCapture(context, store).start(SessionId(1), false, "unused") as Outcome.Ok).value
        val arrivals = Collections.synchronizedList(ArrayList<Long>())
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { h.chunks.collect { arrivals += SystemClock.uptimeMillis() } }
        Thread.sleep(1_000)
        val readyAt = SystemClock.uptimeMillis()
        Log.i(MicCaptureTest.TAG, "WP09_LOST_READY")
        val s = withTimeoutOrNull(10_000) { h.signals.first() }
        val signalAt = SystemClock.uptimeMillis()
        Thread.sleep(1_000)
        val r = h.stop()
        scope.cancel()
        val times = synchronized(arrivals) { arrivals.toList() }
        val after = times.filter { it >= readyAt }
        val gaps = after.zipWithNext { a, b -> b - a }
        val maxGap = gaps.maxOrNull() ?: Long.MAX_VALUE
        val lastChunkBeforeSignal = times.lastOrNull { it <= signalAt } ?: readyAt
        val stopMs = SystemClock.uptimeMillis() - signalAt - 1_000
        Log.i(MicCaptureTest.TAG, "WP09_LOST_DONE mode=$mode signal=$s signal_after_ready_ms=${signalAt - readyAt} " +
            "last_chunk_to_signal_ms=${signalAt - lastChunkBeforeSignal} chunks_after_ready=${after.size} " +
            "max_gap_ms=$maxGap recorded_ms=${r.durationMs} stop_ms=$stopMs")
        if (mode == "stall") {
            assertTrue("a frozen audio server is a lost microphone: $s", s is CaptureSignal.Lost && s.error == MurmurError.MicLost("stall"))
            assertTrue("reported ${signalAt - lastChunkBeforeSignal} ms after the last chunk", signalAt - lastChunkBeforeSignal <= 1_500)
        } else if (s is CaptureSignal.Lost) {
            assertTrue("a lost microphone carries MicLost: ${s.error}", s.error is MurmurError.MicLost)
        } else {
            assertTrue("no Lost signal, so the audio must have kept coming: max gap $maxGap ms", maxGap < 1_000)
        }
    }
}
