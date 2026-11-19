package app.murmur.platform.audio

import android.Manifest
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.platform.audio.test.EmulatorMic
import app.murmur.platform.audio.test.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Audio-focus decision (brief item 6) with another app: the focus-player helper (its own package and UID, built by
 * platform/audio/scripts/build_focus_player.sh) comes to the front, requests AUDIOFOCUS_GAIN and plays for 3 s while
 * we record without any focus request. Driven by platform/audio/scripts/emulator_focus_other_app.sh, which installs the
 * helper and passes `-e wp09.player 1`; without it there is no helper to start and the test does not run.
 */
@RunWith(AndroidJUnit4::class)
class FocusOtherAppTest {
    @Test
    fun recordingContinuesWhileAnotherAppTakesFocusAndPlays() = runBlocking {
        assumeTrue(
            "needs the helper app: run platform/audio/scripts/emulator_focus_other_app.sh",
            InstrumentationRegistry.getArguments().getString("wp09.player") == "1",
        )
        val context = Shell.context
        Shell.grant(Manifest.permission.RECORD_AUDIO)
        EmulatorMic.warmUp()
        Shell.run("logcat -c")
        val store = OpusAudioStore(context, dir = File(context.filesDir, "history/audio-focus-app"))
        val scope = CoroutineScope(Dispatchers.Default)
        val tStart = SystemClock.uptimeMillis()
        val h = (AndroidAudioCapture(context, store).start(SessionId(1), false, "unused") as Outcome.Ok).value
        val startMs = SystemClock.uptimeMillis() - tStart
        var silenced = false
        val sig = scope.launch { h.signals.collect { if (it == CaptureSignal.Silenced) silenced = true } }
        Thread.sleep(500)
        Shell.run("am start -W -n $PLAYER --el ms 3000")
        val done = Shell.waitFor(15_000, { Shell.run("logcat -d -s WP09P:I").lines().firstOrNull { it.contains("WP09P_DONE") } }) { it != null }
        Thread.sleep(500)
        val tStop = SystemClock.uptimeMillis()
        val r = h.stop()
        sig.cancel(); scope.cancel()
        val wallMs = tStop - tStart
        val uid = done?.substringAfter("uid=")?.trim()?.toIntOrNull()
        Log.i(MicCaptureTest.TAG, "focus_other_app player=[${done?.substringAfter("WP09P_DONE")?.trim()}] our_uid=${Process.myUid()} " +
            "recorded_ms=${r.durationMs} wall_ms=$wallMs start_ms=$startMs silenced=$silenced")
        assertNotNull("the helper played", done)
        assertTrue("the helper got the focus: $done", done!!.contains("focus=1"))
        assertNotEquals("the helper is another app (UID)", Process.myUid(), uid)
        assertTrue("recorded ${r.durationMs} ms of $wallMs ms", r.durationMs >= wallMs - startMs - 100)
        assertFalse("not silenced by the other app's focus and playback", silenced || r.silenced)
    }

    private companion object {
        const val PLAYER = "app.murmur.focusplayer/.PlayActivity"
    }
}
