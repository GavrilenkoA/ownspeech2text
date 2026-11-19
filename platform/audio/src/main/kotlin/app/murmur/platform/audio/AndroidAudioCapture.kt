package app.murmur.platform.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.murmur.core.audio.FrameAssembler
import app.murmur.core.audio.MicStartGate
import app.murmur.core.audio.PcmFormat
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.store.AudioStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Microphone capture in `:main` (ADR-024; lifted from the platform spike's Recorder.kt): RecordingFgs first, then
 * AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16) read in 20 ms frames on a THREAD_PRIORITY_URGENT_AUDIO thread.
 * Every frame goes to the session buffer (replayed from sample 0 by [CaptureHandle.chunks]), the history writer
 * (only when keepRecording) and the level meter.
 *
 * [start] returns once capture really started: at the first non-zero sample (android-platform 12, ARMING -> LISTENING).
 * Failures at start: no permission -> MicPermissionMissing; AudioRecord not initialised / builder refused ->
 * MicStartFailed; not RECORDING after startRecording -> MicBusy; isClientSilenced or a first second of exact zeros ->
 * MicSilenced. Mid-recording problems arrive on [CaptureHandle.signals]: Silenced (AudioRecordingCallback),
 * Lost(MicLost) when the input stream died ([MicReadWatch]: read error, reads that stay empty, or no samples for 1 s; a
 * mere route change keeps recording on the new device), CallStarted when a call starts during the recording
 * ([CallWatch]: a call already up at start is not signalled).
 *
 * No audio focus is requested (decision of WP-09, measured on the API 36 emulator: while another app, with its own UID,
 * comes to the front, takes AUDIOFOCUS_GAIN and plays, the recording keeps pace with the wall clock and is not
 * silenced; a focus request would only fade other apps out).
 *
 * @param fgsContext the context the FGS is started from: the accessibility service while connected (WIU through the
 *   binding, platform spike b), else the application.
 */
class AndroidAudioCapture(
    private val context: Context,
    private val store: AudioStore,
    private val fgsContext: () -> Context = { context },
    private val modes: AudioModeSource = AndroidAudioModeSource(context),
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : AudioCapture {

    /** Where the time of the last [start] went (codes and numbers only), for the latency checks. */
    @Volatile internal var lastStart: StartTimings? = null
        private set

    override suspend fun start(session: SessionId, keepRecording: Boolean, audioKey: String): Outcome<CaptureHandle> {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Outcome.Err(MurmurError.MicPermissionMissing)
        }
        val fgsGeneration = RecordingFgsController.start(fgsContext())
        val writer = if (keepRecording) store.newWriter(audioKey) else null
        val source = AudioRecordSource(modes)
        lastStart = source.timings
        val capture = CaptureSession(writer, source)
        capture.doOnEnd { RecordingFgsController.stop(fgsGeneration) }
        source.begin(capture, session)
        val started = try {
            withTimeoutOrNull(startTimeoutMs) { source.started.await() }
                ?: Outcome.Err(MurmurError.MicStartFailed("no_audio"))
        } catch (e: CancellationException) {
            capture.cancel()
            throw e
        }
        if (started is Outcome.Err) {
            capture.cancel()
            return started
        }
        return Outcome.Ok(capture)
    }

    companion object {
        /** The first-second zero check needs 1 s; the reducer's MIC_START timer is 2 s. */
        const val START_TIMEOUT_MS = 1_500L
        internal const val TAG = "MurmurCapture"
    }
}

/**
 * Milliseconds from the start request (uptime) to: AudioRecord built, RECORDING state, the first buffer of any
 * content, the first non-zero sample (= capture started; -1 while it has not happened).
 */
internal class StartTimings {
    @Volatile var builtMs = -1L
    @Volatile var recordingMs = -1L
    @Volatile var firstBufferMs = -1L
    @Volatile var startedMs = -1L

    override fun toString() = "built=$builtMs recording=$recordingMs first_buffer=$firstBufferMs started=$startedMs"
}

/** The AudioRecord thread of one capture. */
internal class AudioRecordSource(private val modes: AudioModeSource) : CaptureSession.Source {
    @Volatile private var running = true
    @Volatile private var failed = false
    override val finished = CompletableDeferred<Unit>()
    /** Ok at the first non-zero sample; Err for every start failure. */
    val started = CompletableDeferred<Outcome<Unit>>()
    val timings = StartTimings()
    /** Mode listener, recording callback and the stall watchdog. */
    private val callbacks: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-callbacks") }
    private val watch = MicReadWatch()
    /** Guards stop-from-the-watchdog against release on the capture thread. */
    private val recLock = Any()
    private var released = false

    override fun halt() { running = false }

    fun begin(session: CaptureSession, id: SessionId) {
        Thread({ run(session, id) }, "audio-capture").start()
    }

    @SuppressLint("MissingPermission") // checked by AndroidAudioCapture.start
    private fun run(session: CaptureSession, id: SessionId) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t0 = SystemClock.uptimeMillis()
        var rec: AudioRecord? = null
        var modeWatch: AutoCloseable? = null
        var recordingCallback: AudioManager.AudioRecordingCallback? = null
        try {
            val r = try {
                build()
            } catch (e: SecurityException) {
                fail(MurmurError.MicPermissionMissing); return
            } catch (e: Exception) {
                fail(MurmurError.MicStartFailed("build:${e.javaClass.simpleName}")); return
            }
            rec = r
            timings.builtMs = SystemClock.uptimeMillis() - t0
            if (r.state != AudioRecord.STATE_INITIALIZED) { fail(MurmurError.MicStartFailed("init")); return }
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId } ?: return
                    if (mine.isClientSilenced && started.isCompleted) session.signal(CaptureSignal.Silenced)
                }
            }
            r.registerAudioRecordingCallback(callbacks, cb)
            recordingCallback = cb
            val modeAtStart = modes.mode
            val calls = CallWatch(modeAtStart)
            fun onMode(mode: Int, from: String) {
                val call = calls.onMode(mode)
                if (from == "listener" || call) {
                    Log.i(AndroidAudioCapture.TAG, "mode_changed ${CallModes.name(mode)} call_started=$call session=${id.value}")
                }
                if (call) session.signal(CaptureSignal.CallStarted)
            }
            modeWatch = modes.observe(callbacks) { onMode(it, "listener") }
            // A change between the first read and the registration is caught here (a repeat of the same mode is not).
            onMode(modes.mode, "recheck")
            try {
                r.startRecording()
            } catch (e: IllegalStateException) {
                fail(MurmurError.MicStartFailed("start")); return
            }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { fail(MurmurError.MicBusy); return }
            timings.recordingMs = SystemClock.uptimeMillis() - t0

            val gate = MicStartGate()
            val frames = FrameAssembler { frame, n ->
                session.onFrame(frame, n)
                if (!started.isCompleted) {
                    when (gate.push(frame, n)) {
                        MicStartGate.State.STARTED -> {
                            if (r.activeRecordingConfiguration?.isClientSilenced == true) {
                                fail(MurmurError.MicSilenced)
                            } else {
                                timings.startedMs = SystemClock.uptimeMillis() - t0
                                Log.i(AndroidAudioCapture.TAG, "mic_started ms=${timings.startedMs} $timings " +
                                    "mode=${CallModes.name(modeAtStart)} session=${id.value}")
                                armWatchdog(r, session, id)
                                started.complete(Outcome.Ok(Unit))
                            }
                        }
                        MicStartGate.State.SILENT -> fail(MurmurError.MicSilenced)
                        MicStartGate.State.PENDING -> Unit
                    }
                }
            }
            val buf = ShortArray(PcmFormat.FRAME_SAMPLES)
            while (running && !failed) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0 && timings.firstBufferMs < 0) timings.firstBufferMs = SystemClock.uptimeMillis() - t0
                when (val v = watch.onRead(n, SystemClock.uptimeMillis())) {
                    MicReadWatch.Verdict.Samples -> frames.push(buf, 0, n)
                    is MicReadWatch.Verdict.Retry -> SystemClock.sleep(v.ms)
                    is MicReadWatch.Verdict.Lost -> {
                        Log.w(AndroidAudioCapture.TAG, "mic_lost cause=${v.cause} session=${id.value}")
                        if (!started.isCompleted) fail(MurmurError.MicStartFailed(v.cause))
                        else session.signal(CaptureSignal.Lost(MurmurError.MicLost(v.cause)))
                        break
                    }
                }
            }
        } finally {
            modeWatch?.let { runCatching { it.close() } }
            rec?.let { r ->
                recordingCallback?.let { runCatching { r.unregisterAudioRecordingCallback(it) } }
                synchronized(recLock) {
                    runCatching { r.stop() }
                    r.release()
                    released = true
                }
            }
            // Only after both listeners are gone: nothing is dispatched to a shut-down executor.
            callbacks.shutdownNow()
            if (!started.isCompleted) started.complete(Outcome.Err(MurmurError.MicStartFailed("ended")))
            finished.complete(Unit)
        }
    }

    /**
     * From the first sample on: a read that blocks without delivering for [MicReadWatch.MAX_GAP_MS] is a lost input.
     * The watchdog signals Lost and stops the AudioRecord, which unblocks the read so that the capture can end.
     */
    private fun armWatchdog(r: AudioRecord, session: CaptureSession, id: SessionId) {
        watch.armed(SystemClock.uptimeMillis())
        val period = MicReadWatch.WATCHDOG_PERIOD_MS
        runCatching {
            callbacks.scheduleWithFixedDelay({
                if (running && !failed && watch.stalled(SystemClock.uptimeMillis())) {
                    Log.w(AndroidAudioCapture.TAG, "mic_lost cause=stall session=${id.value}")
                    failed = true
                    session.signal(CaptureSignal.Lost(MurmurError.MicLost("stall")))
                    synchronized(recLock) { if (!released) runCatching { r.stop() } }
                }
            }, period, period, TimeUnit.MILLISECONDS)
        }
    }

    private fun fail(e: MurmurError) {
        Log.w(AndroidAudioCapture.TAG, "mic_start_failed ${e.code} ${e.detail ?: ""}")
        failed = true
        started.complete(Outcome.Err(e))
    }

    @SuppressLint("MissingPermission")
    private fun build(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(PcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val format = AudioFormat.Builder()
            .setSampleRate(PcmFormat.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            // max(2 x minimum, 500 ms) (android-platform 4.2): a stalled reader never loses the first word.
            .setBufferSizeInBytes(maxOf(min * 2, PcmFormat.SAMPLE_RATE * PcmFormat.BYTES_PER_SAMPLE / 2))
            .build()
    }
}
