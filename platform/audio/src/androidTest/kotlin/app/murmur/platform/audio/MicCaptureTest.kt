package app.murmur.platform.audio

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.audio.PcmFormat
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.AudioKey
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.platform.audio.test.PlayerService
import app.murmur.platform.audio.test.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor

/**
 * Microphone capture on the API 36 emulator (WP-09 acceptance). The emulator microphone cannot be fed (platform
 * spike b): samples are near zero, so content is a device item (DC5); timing, framing, FGS, signals and files are
 * checked here. Measurements are logged with the tag WP09 (codes and numbers only).
 */
@RunWith(AndroidJUnit4::class)
class MicCaptureTest {
    private val context = Shell.context
    private val pkg = context.packageName
    private lateinit var store: OpusAudioStore
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        Shell.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        store = OpusAudioStore(context, dir = File(context.filesDir, "history/audio-mic-test").also { it.deleteRecursively() })
    }

    @After
    fun tearDown() { scope.cancel() }

    private fun capture(modes: AudioModeSource = AndroidAudioModeSource(context)) = AndroidAudioCapture(context, store, modes = modes)

    private fun started(o: Outcome<CaptureHandle>): CaptureHandle {
        assertTrue("capture start: $o", o is Outcome.Ok)
        return (o as Outcome.Ok).value
    }

    private fun fgsInDumpsys(): Boolean =
        Shell.run("dumpsys activity services $pkg").contains("RecordingFgs")

    @Test
    fun startsWithin500msAndDelivers20msChunksAt50HzFor10s() = runBlocking {
        val t0 = SystemClock.uptimeMillis()
        val h = started(capture().start(SessionId(1), keepRecording = false, audioKey = "unused"))
        val startMs = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "capture_start_ms=$startMs")
        assertTrue("capture started in $startMs ms", startMs <= 500)

        val arrivals = Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
        var expectedStart = 0L
        var contiguous = true
        val job = scope.launch {
            h.chunks.collect { c ->
                arrivals += SystemClock.uptimeMillis() to c.samples.size
                if (c.startSample != expectedStart) contiguous = false
                expectedStart = c.endSample
            }
        }
        val windowStart = SystemClock.uptimeMillis() + 1_000 // steady state after the first second
        Thread.sleep(11_200)
        val r = h.stop()
        job.join()
        val inWindow = synchronized(arrivals) { arrivals.filter { it.first >= windowStart && it.first < windowStart + 10_000 } }
        Log.i(TAG, "chunks_in_10s=${inWindow.size} total=${arrivals.size} duration_ms=${r.durationMs} peak=${r.peakAbs}")
        assertTrue("every chunk is one 20 ms frame", arrivals.all { it.second == PcmFormat.FRAME_SAMPLES })
        assertTrue("chunks are contiguous from sample 0", contiguous)
        assertTrue("${inWindow.size} chunks in 10 s (500 +- 2)", inWindow.size in 498..502)
        assertEquals(arrivals.size * 20L, r.durationMs)
        assertNull("keepRecording=false: no file", r.audio)
        assertFalse(r.silenced)
    }

    @Test
    fun theFgsRunsOnlyWhileRecordingAndTheStatusNotificationIndependently() = runBlocking {
        val notifier = AndroidStatusNotifier(context)
        notifier.show("Parakeet · LFM3", null)
        assertFalse("no FGS before recording", fgsInDumpsys())

        val h = started(capture().start(SessionId(2), keepRecording = false, audioKey = "unused"))
        val fg = Shell.waitFor(3_000, { RecordingFgsController.state.value }) { it is RecordingFgsController.State.Foreground }
        Log.i(TAG, "fgs_state=$fg")
        assertTrue("FGS foreground while recording: $fg", fg is RecordingFgsController.State.Foreground)
        val services = Shell.run("dumpsys activity services $pkg")
        val line = services.lines().filter { it.contains("RecordingFgs") || it.contains("isForeground=") || it.contains("foregroundServiceType") }
        Log.i(TAG, "dumpsys_services_while_recording=" + line.joinToString(" | ") { it.trim() })
        assertTrue("dumpsys lists RecordingFgs while recording", services.contains("RecordingFgs"))
        assertTrue("it is a foreground service", services.contains("isForeground=true"))
        assertTrue("status notification posted next to it", statusNotificationListed())

        h.stop()
        val gone = Shell.waitFor(3_000, { fgsInDumpsys() }) { !it }
        assertFalse("FGS gone after stop", gone)
        assertTrue("status notification stays after the recording", statusNotificationListed())

        // Cancel ends the FGS as well.
        val h2 = started(capture().start(SessionId(3), keepRecording = false, audioKey = "unused"))
        Shell.waitFor(3_000, { fgsInDumpsys() }) { it }
        h2.cancel()
        assertFalse("FGS gone after cancel", Shell.waitFor(3_000, { fgsInDumpsys() }) { !it })

        notifier.hide()
        assertFalse(Shell.waitFor(2_000, { statusNotificationListed() }) { !it })
    }

    private fun statusNotificationListed(): Boolean {
        val dump = Shell.run("dumpsys notification --noredact")
        val lines = dump.lines()
        return lines.indices.any { i ->
            lines[i].contains("pkg=$pkg") && lines[i].contains("id=${AndroidStatusNotifier.NOTIFICATION_ID}")
        }
    }

    @Test
    fun callStartedFromAModeSourceArrivesWithin1s() = runBlocking {
        val fake = FakeModes()
        val h = started(capture(fake).start(SessionId(4), keepRecording = false, audioKey = "unused"))
        Thread.sleep(300)
        val t = SystemClock.uptimeMillis()
        fake.set(android.media.AudioManager.MODE_IN_CALL)
        val s = withTimeoutOrNull(1_000) { h.signals.first() }
        val ms = SystemClock.uptimeMillis() - t
        h.stop()
        Log.i(TAG, "fake_mode_call_signal=$s ms=$ms")
        assertEquals(CaptureSignal.CallStarted, s)
    }

    @Test
    fun aCallAlreadyUpAtStartIsSignalledImmediately() = runBlocking {
        val fake = FakeModes(android.media.AudioManager.MODE_IN_COMMUNICATION)
        val h = started(capture(fake).start(SessionId(5), keepRecording = false, audioKey = "unused"))
        val s = withTimeoutOrNull(1_000) { h.signals.first() }
        h.stop()
        assertEquals(CaptureSignal.CallStarted, s)
    }

    @Test
    fun keepRecordingsOffWritesNothingAndOnWritesOneFile() = runBlocking {
        val key = AudioKey.of(1_763_500_000_000, SessionId(6)).value
        val off = started(capture().start(SessionId(6), keepRecording = false, audioKey = key))
        Thread.sleep(1_000)
        val rOff = off.stop()
        assertNull(rOff.audio)
        assertTrue("no file at all", store.dir.listFiles().isNullOrEmpty())
        assertTrue(store.listAll().isEmpty())

        val on = started(capture().start(SessionId(7), keepRecording = true, audioKey = key))
        Thread.sleep(1_000)
        val rOn = on.stop()
        assertNotNull(rOn.audio)
        val f = File(rOn.audio!!.path)
        assertEquals("$key.${store.codec.extension}", f.name)
        assertTrue(f.isFile && f.length() > 0)
        val pcm = store.readPcm(rOn.audio!!)!!
        Log.i(TAG, "mic_file bytes=${f.length()} duration_ms=${rOn.durationMs} decoded=${pcm.size}")
        assertTrue("decoded ${pcm.size} of ${rOn.durationMs * 16}", pcm.size >= rOn.durationMs * 16 * 99 / 100)
    }

    /**
     * Audio-focus decision (brief item 6): capture requests no focus. Measured here: another audio client (separate
     * process) requests AUDIOFOCUS_GAIN and plays while we record; the recording must go on at 50 Hz, unsilenced.
     */
    @Test
    fun recordingContinuesWhileAnotherClientTakesFocusAndPlays() = runBlocking {
        val result = File(context.filesDir, PlayerService.RESULT).also { it.delete() }
        val h = started(capture().start(SessionId(8), keepRecording = false, audioKey = "unused"))
        val arrivals = Collections.synchronizedList(ArrayList<Long>())
        var silenced = false
        val job = scope.launch { h.chunks.collect { arrivals += SystemClock.uptimeMillis() } }
        val sig = scope.launch { h.signals.collect { if (it == CaptureSignal.Silenced) silenced = true } }
        Thread.sleep(500)
        val playStart = SystemClock.uptimeMillis()
        context.startService(Intent(context, PlayerService::class.java).putExtra(PlayerService.EXTRA_MS, 3_000L))
        Shell.waitFor(10_000, { result.isFile }) { it }
        val playEnd = SystemClock.uptimeMillis()
        val r = h.stop()
        job.join(); sig.cancel()
        val during = synchronized(arrivals) { arrivals.count { it in (playStart + 500)..(playStart + 2_500) } }
        Log.i(TAG, "focus_measurement player=${result.readText()} chunks_in_2s_during_playback=$during silenced=$silenced " +
            "played_window_ms=${playEnd - playStart} duration_ms=${r.durationMs}")
        assertTrue("player ran: ${result.readText()}", result.readText().startsWith("focus="))
        assertTrue("$during chunks in 2 s while the other client plays (100 +- 2)", during in 98..102)
        assertFalse("not silenced by the other client's focus/playback", silenced || r.silenced)
    }

    private class FakeModes(initial: Int = android.media.AudioManager.MODE_NORMAL) : AudioModeSource {
        @Volatile override var mode: Int = initial
        private var listener: ((Int) -> Unit)? = null
        private var executor: Executor? = null
        override fun observe(executor: Executor, listener: (Int) -> Unit): AutoCloseable {
            this.executor = executor; this.listener = listener
            return AutoCloseable { this.listener = null }
        }
        fun set(m: Int) {
            mode = m
            val l = listener ?: return
            executor?.execute { l(m) }
        }
    }

    companion object { const val TAG = "WP09" }
}
