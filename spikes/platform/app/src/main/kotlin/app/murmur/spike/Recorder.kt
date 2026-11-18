package app.murmur.spike

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * 16 kHz mono PCM16 VOICE_RECOGNITION capture on a URGENT_AUDIO thread.
 * Reports: first-buffer latency, non-zero sample count, peak, and the system's own
 * AudioRecordingConfiguration.isClientSilenced() (the authoritative "silenced" signal).
 */
class Recorder(private val label: String, private val perSecondLog: Boolean = false) {
    private val cbExec = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    var startUptime = 0L
    @Volatile var firstBufferMs = -1L
    @Volatile var samples = 0L
    @Volatile var nonZero = 0L
    @Volatile var peak = 0
    @Volatile var silencedSeen: Boolean? = null

    @SuppressLint("MissingPermission")
    fun start(t0: Long): Boolean {
        val fmt = AudioFormat.Builder().setSampleRate(16_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(fmt).setBufferSizeInBytes(max(min * 2, 16_000)).build()
        } catch (e: Exception) {
            Ev.log("rec_build_failed", "label" to label, "err" to e.toString()); return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Ev.log("rec_not_initialized", "label" to label); r.release(); return false
        }
        r.registerAudioRecordingCallback(cbExec, object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId } ?: return
                silencedSeen = mine.isClientSilenced
                Ev.log("rec_config", "label" to label, "silenced" to mine.isClientSilenced,
                    "source" to mine.clientAudioSource, "device" to mine.audioDevice?.productName?.toString())
            }
        })
        rec = r; samples = 0; nonZero = 0; peak = 0; firstBufferMs = -1; startUptime = t0
        try { r.startRecording() } catch (e: Exception) {
            Ev.log("rec_start_failed", "label" to label, "err" to e.toString()); r.release(); rec = null; return false
        }
        running = true
        Ev.log("rec_started", "label" to label, "recordingState" to r.recordingState, "session" to r.audioSessionId)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(320) // 20 ms
            var lastTick = SystemClock.uptimeMillis(); var nzAtTick = 0L; var sAtTick = 0L
            while (running) {
                val now = SystemClock.uptimeMillis()
                if (perSecondLog && now - lastTick >= 1000) {
                    Ev.log("rec_tick", "label" to label, "t" to (now - startUptime) / 1000, "nonZeroFrac" to
                        "%.3f".format((nonZero - nzAtTick).toDouble() / (samples - sAtTick).coerceAtLeast(1)),
                        "silenced" to r.activeRecordingConfiguration?.isClientSilenced)
                    lastTick = now; nzAtTick = nonZero; sAtTick = samples
                }
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) Ev.log("rec_read_error", "label" to label, "code" to n); continue }
                if (firstBufferMs < 0) firstBufferMs = SystemClock.uptimeMillis() - startUptime
                samples += n
                for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a != 0) nonZero++; if (a > peak) peak = a }
            }
        }, "audio-$label").also { it.start() }
        return true
    }

    fun stop(): Map<String, Any?> {
        val r = rec ?: return mapOf("label" to label, "running" to false)
        val cfg = r.activeRecordingConfiguration
        val silencedNow = cfg?.isClientSilenced
        running = false
        thread?.join(500)
        runCatching { r.stop() }; r.release(); rec = null
        return mapOf(
            "label" to label, "samples" to samples, "seconds" to samples / 16000.0,
            "nonZero" to nonZero, "peak" to peak, "firstBufferMs" to firstBufferMs,
            "silencedAtStop" to silencedNow, "silencedCallback" to silencedSeen,
        )
    }
}
