package app.murmur.platform.audio

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.platform.audio.test.EmulatorMic
import app.murmur.platform.audio.test.Shell
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A real telephony call on the emulator while recording (`adb emu gsm call`). The call has to come from the host's
 * emulator console, so this test is driven by platform/audio/scripts/emulator_call_signal.sh, which passes
 * `-e wp09.gsm 1`, waits for "WP09_CALL_READY" in logcat, sends `gsm call` / `gsm accept` / `gsm cancel` and times
 * the CallStarted signal. Without that argument there is no call to wait for and the test does not run.
 */
@RunWith(AndroidJUnit4::class)
class GsmCallSignalTest {
    @Test
    fun incomingCallWhileRecordingSignalsCallStarted() = runBlocking {
        assumeTrue(
            "needs the host console: run platform/audio/scripts/emulator_call_signal.sh",
            InstrumentationRegistry.getArguments().getString("wp09.gsm") == "1",
        )
        val context = Shell.context
        Shell.grant(Manifest.permission.RECORD_AUDIO)
        EmulatorMic.warmUp()
        val modes = AndroidAudioModeSource(context)
        val seen = Collections.synchronizedList(ArrayList<String>())
        val t0 = SystemClock.uptimeMillis()
        val firstCall = AtomicLong(-1)
        val watch = modes.observe(Executors.newSingleThreadExecutor()) {
            val now = SystemClock.uptimeMillis()
            if (CallModes.isCall(it)) firstCall.compareAndSet(-1, now)
            seen += "${CallModes.name(it)}@${now - t0}ms"
        }
        val store = OpusAudioStore(context, dir = File(context.filesDir, "history/audio-gsm-test"))
        val h = (AndroidAudioCapture(context, store).start(SessionId(1), false, "unused") as Outcome.Ok).value
        Log.i(MicCaptureTest.TAG, "WP09_CALL_READY mode=${CallModes.name(modes.mode)}")
        val readyAt = SystemClock.uptimeMillis()
        val s = withTimeoutOrNull(60_000) { h.signals.first() }
        val at = SystemClock.uptimeMillis()
        Log.i(MicCaptureTest.TAG, "WP09_CALL_SIGNAL signal=$s ms_after_ready=${at - readyAt} modes=$seen")
        Thread.sleep(8_000) // the script accepts the call, then hangs up: record the modes of the whole call
        val r = h.stop()
        watch.close()
        // Device clock: the system's mode change, seen by an independent listener, -> our signal (negative: ours first).
        val firstCallModeAt = firstCall.get()
        val modeToSignal = at - firstCallModeAt
        Log.i(MicCaptureTest.TAG, "WP09_CALL_DONE modes=$seen recorded_ms=${r.durationMs} mode_to_signal_ms=$modeToSignal")
        assertEquals(CaptureSignal.CallStarted, s)
        assertTrue("a call mode was seen", firstCallModeAt >= 0)
        assertTrue("mode change -> CallStarted $modeToSignal ms <= 1000 ms", modeToSignal <= 1_000)
    }
}
