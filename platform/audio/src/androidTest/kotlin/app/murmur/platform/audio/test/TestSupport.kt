package app.murmur.platform.audio.test

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.platform.audio.StatusAction
import app.murmur.platform.audio.StatusActionHandler
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin

/** The test APK's Application: receives the notification actions like :main's Application does. */
class TestApp : Application(), StatusActionHandler {
    override fun onStatusAction(action: StatusAction) { actions.put(action) }

    companion object {
        val actions = LinkedBlockingQueue<StatusAction>()
    }
}

object Shell {
    /** Runs a shell command as the shell user (dumpsys, pm grant) and returns its stdout. */
    fun run(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun grant(vararg permissions: String) {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in permissions) ua.grantRuntimePermission(context.packageName, p)
    }

    /** Polls [check] every 50 ms for up to [timeoutMs]; returns the last value. */
    fun <T> waitFor(timeoutMs: Long, check: () -> T, done: (T) -> Boolean): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var v = check()
        while (!done(v) && SystemClock.uptimeMillis() < end) {
            Thread.sleep(50)
            v = check()
        }
        return v
    }
}

/**
 * Plays a 440 Hz tone with AudioTrack (USAGE_MEDIA) for [EXTRA_MS] after requesting AUDIOFOCUS_GAIN, in its own
 * process (":player"), i.e. another audio client that takes the focus while the capture records. Writes
 * "focus=<request result> played_ms=<n>" to filesDir/player_result.txt.
 */
class PlayerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ms = intent?.getLongExtra(EXTRA_MS, 3000L) ?: 3000L
        Thread {
            val am = getSystemService(AudioManager::class.java)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build()
            val focusResult = am.requestAudioFocus(focus)
            val rate = 48_000
            val track = AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val buf = ShortArray(rate / 50) // 20 ms
            var n = 0L
            val t0 = SystemClock.uptimeMillis()
            track.play()
            while (SystemClock.uptimeMillis() - t0 < ms) {
                for (i in buf.indices) buf[i] = (8000 * sin(2 * PI * 440 * (n + i) / rate)).toInt().toShort()
                n += buf.size
                track.write(buf, 0, buf.size)
            }
            val played = SystemClock.uptimeMillis() - t0
            track.stop(); track.release()
            am.abandonAudioFocusRequest(focus)
            File(filesDir, RESULT).writeText("focus=$focusResult played_ms=$played")
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_MS = "ms"
        const val RESULT = "player_result.txt"
    }
}
