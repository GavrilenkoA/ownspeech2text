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
import app.murmur.platform.audio.test.EmulatorMic
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
import org.junit.AfterClass
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
        EmulatorMic.warmUp()
    }

    @After
    fun tearDown() { scope.cancel() }

    private fun capture(modes: AudioModeSource = AndroidAudioModeSource(context)) = AndroidAudioCapture(context, store, modes = modes)

    private fun started(o: Outcome<CaptureHandle>): CaptureHandle {
        assertTrue("capture start: $o", o is Outcome.Ok)
        return (o as Outcome.Ok).value
    }

    /** Starts a capture and records how long the start took (every start of the suite is logged and summarised). */
    private suspend fun startTimed(
        session: Long,
        keepRecording: Boolean = false,
        key: String = "unused",
        modes: AudioModeSource = AndroidAudioModeSource(context),
    ): CaptureHandle {
        val c = capture(modes)
        val t0 = SystemClock.uptimeMillis()
        val o = c.start(SessionId(session), keepRecording, key)
        val ms = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "capture_start session=$session ms=$ms ${c.lastStart}")
        starts += ms
        return started(o)
    }

    /** stop() -> CaptureResult, timed. */
    private suspend fun stopTimed(h: CaptureHandle, label: String): app.murmur.core.dictation.CaptureResult {
        val t0 = SystemClock.uptimeMillis()
        val r = h.stop()
        val ms = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "capture_stop $label ms=$ms audio=${r.audio != null} duration_ms=${r.durationMs}")
        stops += ms
        return r
    }

    private fun fgsInDumpsys(): Boolean =
        Shell.run("dumpsys activity services $pkg").contains("RecordingFgs")

    /**
     * "Capture starts within 500 ms", over many warm starts (the emulator microphone is opened once per process
     * before, see [EmulatorMic]; the cold first start is logged as mic_warm_up). Start = press until the first
     * non-zero sample, which on this host depends on when the virtual microphone stops delivering exact zeros; the
     * stages (AudioRecord built, RECORDING, first buffer of any content) are logged to tell the two apart.
     */
    @Test
    fun warmStartsTakeAMedianOfAtMost500ms() = runBlocking {
        val total = ArrayList<Long>()
        val firstBuffer = ArrayList<Long>()
        repeat(WARM_STARTS) { i ->
            val c = capture()
            val t0 = SystemClock.uptimeMillis()
            val h = started(c.start(SessionId(200L + i), false, "unused"))
            total += SystemClock.uptimeMillis() - t0
            val t = c.lastStart!!
            firstBuffer += t.firstBufferMs
            Log.i(TAG, "warm_start i=$i ms=${total.last()} $t")
            Thread.sleep(300)
            stopTimed(h, "warm_start")
            Thread.sleep(200)
        }
        val sorted = total.sorted()
        val median = sorted[sorted.size / 2]
        val fbMedian = firstBuffer.sorted()[firstBuffer.size / 2]
        Log.i(TAG, "warm_starts n=${sorted.size} median_ms=$median p90_ms=${sorted[sorted.size * 9 / 10]} " +
            "max_ms=${sorted.last()} over_500=${sorted.count { it > 500 }} first_buffer_median_ms=$fbMedian " +
            "first_buffer_max_ms=${firstBuffer.max()} all=$total")
        assertTrue("median warm start $median ms <= 500 ms (all: $total)", median <= 500)
        assertTrue("median time to the first buffer $fbMedian ms <= 500 ms", fbMedian <= 500)
    }

    @Test
    fun delivers20msChunksAt50HzFor10s() = runBlocking {
        val c = capture()
        val t0 = SystemClock.uptimeMillis()
        val h = started(c.start(SessionId(1), keepRecording = false, audioKey = "unused"))
        Log.i(TAG, "capture_start session=1 ms=${SystemClock.uptimeMillis() - t0} ${c.lastStart}")
        starts += SystemClock.uptimeMillis() - t0
        // Sample 0 is the first sample after AudioRecord entered RECORDING: the audio clock starts there.
        val recordingAt = t0 + c.lastStart!!.recordingMs

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
        Thread.sleep(12_500) // 1 s settle + 1 s of window starts + 10 s windows + margin
        val stopAt = SystemClock.uptimeMillis()
        val r = stopTimed(h, "10s")
        job.join()
        val times = synchronized(arrivals) { arrivals.map { it.first } }
        // Chunks delivered in 10 s of wall time, for every 10 s window that starts at one of the chunks of the second
        // second (steady state). The median is the rate; a single host stall cannot move it, a real drop would.
        val counts = times.filter { it >= times.first() + 1_000 && it < times.first() + 2_000 && it + 10_000 <= stopAt }
            .map { from -> times.count { it >= from && it < from + 10_000 } }.sorted()
        val median = counts[counts.size / 2]
        // The audio clock must keep pace with the wall clock: samples delivered vs time since RECORDING began (the
        // first chunks are collected late, as a replay, when the start waited for the first non-zero sample).
        val audioVsWallMs = r.durationMs - (stopAt - recordingAt)
        Log.i(TAG, "chunks_in_10s median=$median min=${counts.first()} max=${counts.last()} windows=${counts.size} " +
            "total=${arrivals.size} duration_ms=${r.durationMs} audio_minus_wall_ms=$audioVsWallMs peak=${r.peakAbs}")
        assertTrue("every chunk is one 20 ms frame", arrivals.all { it.second == PcmFormat.FRAME_SAMPLES })
        assertTrue("chunks are contiguous from sample 0", contiguous)
        assertTrue("$median chunks in 10 s (500 +- 2)", median in 498..502)
        assertTrue("audio clock within 100 ms of the wall clock: $audioVsWallMs", audioVsWallMs in -100..100)
        assertEquals(arrivals.size * 20L, r.durationMs)
        assertNull("keepRecording=false: no file", r.audio)
        assertFalse(r.silenced)
    }

    @Test
    fun theFgsRunsOnlyWhileRecordingAndTheStatusNotificationIndependently() = runBlocking {
        val notifier = AndroidStatusNotifier(context)
        notifier.show("Parakeet · LFM3", null)
        assertFalse("no FGS before recording", fgsInDumpsys())

        val h = startTimed(2)
        val fg = Shell.waitFor(3_000, { RecordingFgsController.state.value }) { it is RecordingFgsController.State.Foreground }
        Log.i(TAG, "fgs_state=$fg")
        assertTrue("FGS foreground while recording: $fg", fg is RecordingFgsController.State.Foreground)
        val services = Shell.run("dumpsys activity services $pkg")
        val line = services.lines().filter { it.contains("RecordingFgs") || it.contains("isForeground=") || it.contains("foregroundServiceType") }
        Log.i(TAG, "dumpsys_services_while_recording=" + line.joinToString(" | ") { it.trim() })
        assertTrue("dumpsys lists RecordingFgs while recording", services.contains("RecordingFgs"))
        assertTrue("it is a foreground service", services.contains("isForeground=true"))
        assertTrue("status notification posted next to it", statusNotificationListed())

        stopTimed(h, "fgs")
        val gone = Shell.waitFor(3_000, { fgsInDumpsys() }) { !it }
        assertFalse("FGS gone after stop", gone)
        assertTrue("status notification stays after the recording", statusNotificationListed())

        // Cancel ends the FGS as well.
        val h2 = startTimed(3)
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
        val h = startTimed(4, modes = fake)
        Thread.sleep(300)
        val t = SystemClock.uptimeMillis()
        fake.set(android.media.AudioManager.MODE_IN_CALL)
        val s = withTimeoutOrNull(1_000) { h.signals.first() }
        val ms = SystemClock.uptimeMillis() - t
        stopTimed(h, "fake_call")
        Log.i(TAG, "fake_mode_call_signal=$s ms=$ms")
        assertEquals(CaptureSignal.CallStarted, s)
        assertTrue("mode change -> signal $ms ms <= 1000 ms", ms <= 1_000)
    }

    /**
     * A VoIP mode that is already set when the dictation starts (a call the user is in, or one a VoIP app leaked) does
     * not stop the dictation; a call that starts during it does.
     */
    @Test
    fun aCallAlreadyUpAtStartIsNotSignalledButANewOneIs() = runBlocking {
        val fake = FakeModes(android.media.AudioManager.MODE_IN_COMMUNICATION)
        val h = startTimed(5, modes = fake)
        val early = withTimeoutOrNull(1_000) { h.signals.first() }
        assertNull("no CallStarted for the call that was already up", early)
        fake.set(android.media.AudioManager.MODE_IN_COMMUNICATION) // reported again: still the same call
        assertNull(withTimeoutOrNull(500) { h.signals.first() })
        fake.set(android.media.AudioManager.MODE_NORMAL)
        fake.set(android.media.AudioManager.MODE_RINGTONE)
        val s = withTimeoutOrNull(1_000) { h.signals.first() }
        stopTimed(h, "call_at_start")
        assertEquals(CaptureSignal.CallStarted, s)
    }

    /**
     * Stop latency (stop() -> CaptureResult) with a history file while every core is busy at default priority, like
     * :asr and :llm during a dictation: stop() waits until the background-priority encoder has finished the file.
     */
    @Test
    fun stopWithAFileReturnsPromptlyWhileTheCpuIsBusy() = runBlocking {
        val busy = java.util.concurrent.atomic.AtomicBoolean(true)
        val cores = Runtime.getRuntime().availableProcessors()
        val burners = (0 until cores * 2).map { Thread { var x = 0L; while (busy.get()) x += x xor 1 }.also { it.start() } }
        try {
            val key = AudioKey.of(1_763_700_000_000, SessionId(9)).value
            val h = startTimed(9, keepRecording = true, key = key)
            Thread.sleep(5_000)
            val t0 = SystemClock.uptimeMillis()
            val r = h.stop()
            val ms = SystemClock.uptimeMillis() - t0
            stops += ms
            Log.i(TAG, "stop_under_load ms=$ms cores=$cores burners=${burners.size} duration_ms=${r.durationMs} " +
                "bytes=${r.audio?.bytes}")
            assertNotNull(r.audio)
            assertTrue("stop under load $ms ms <= $STOP_UNDER_LOAD_MS ms", ms <= STOP_UNDER_LOAD_MS)
        } finally {
            busy.set(false)
            burners.forEach { it.join() }
        }
    }

    @Test
    fun keepRecordingsOffWritesNothingAndOnWritesOneFile() = runBlocking {
        val key = AudioKey.of(1_763_500_000_000, SessionId(6)).value
        val off = startTimed(6, key = key)
        Thread.sleep(1_000)
        val rOff = stopTimed(off, "keep_off")
        assertNull(rOff.audio)
        assertTrue("no file at all", store.dir.listFiles().isNullOrEmpty())
        assertTrue(store.listAll().isEmpty())

        val on = startTimed(7, keepRecording = true, key = key)
        Thread.sleep(1_000)
        val rOn = stopTimed(on, "keep_on")
        assertNotNull(rOn.audio)
        val f = File(rOn.audio!!.path)
        assertEquals("$key.${store.codec.extension}", f.name)
        assertTrue(f.isFile && f.length() > 0)
        val pcm = store.readPcm(rOn.audio!!)!!
        Log.i(TAG, "mic_file bytes=${f.length()} duration_ms=${rOn.durationMs} decoded=${pcm.size}")
        assertEquals("decoded back to the recorded length", rOn.durationMs * 16, pcm.size.toLong())
    }

    /**
     * Audio-focus decision (brief item 6): capture requests no focus. Measured here: another audio client (separate
     * process) requests AUDIOFOCUS_GAIN and plays while we record; the recording must go on at 50 Hz, unsilenced.
     */
    @Test
    fun recordingContinuesWhileAnotherClientTakesFocusAndPlays() = runBlocking {
        val result = File(context.filesDir, PlayerService.RESULT).also { it.delete() }
        val tStart = SystemClock.uptimeMillis()
        val h = startTimed(8)
        val tStarted = SystemClock.uptimeMillis()
        var silenced = false
        val sig = scope.launch { h.signals.collect { if (it == CaptureSignal.Silenced) silenced = true } }
        Thread.sleep(500)
        context.startService(Intent(context, PlayerService::class.java).putExtra(PlayerService.EXTRA_MS, 3_000L))
        Shell.waitFor(10_000, { result.isFile }) { it }
        Thread.sleep(500)
        val tStop = SystemClock.uptimeMillis()
        val r = stopTimed(h, "focus_same_uid")
        sig.cancel()
        // The audio clock must keep pace with the wall clock through the other client's focus + playback: a paused or
        // dropped recording would come up short by the pause. Allowed slack: the start latency plus 100 ms.
        val wallMs = tStop - tStart
        Log.i(TAG, "focus_measurement player=${result.readText()} recorded_ms=${r.durationMs} wall_ms=$wallMs " +
            "start_ms=${tStarted - tStart} silenced=$silenced")
        assertTrue("player ran: ${result.readText()}", result.readText().startsWith("focus=1"))
        assertTrue("recorded ${r.durationMs} ms of $wallMs ms", r.durationMs >= wallMs - (tStarted - tStart) - 100)
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

    companion object {
        const val TAG = "WP09"
        private const val WARM_STARTS = 15
        /** Measured on the emulator, 2 burners per core: 58-126 ms (961 ms with a background-priority encoder). */
        private const val STOP_UNDER_LOAD_MS = 500L
        private val starts = Collections.synchronizedList(ArrayList<Long>())
        private val stops = Collections.synchronizedList(ArrayList<Long>())

        /** Every start and stop of the suite (single starts are logged, not asserted: see warmStarts...). */
        @JvmStatic
        @AfterClass
        fun summary() {
            val s = synchronized(starts) { starts.sorted() }
            val t = synchronized(stops) { stops.sorted() }
            if (s.isNotEmpty()) Log.i(TAG, "suite_starts n=${s.size} median_ms=${s[s.size / 2]} max_ms=${s.last()} all=$s")
            if (t.isNotEmpty()) Log.i(TAG, "suite_stops n=${t.size} median_ms=${t[t.size / 2]} max_ms=${t.last()} all=$t")
        }
    }
}
