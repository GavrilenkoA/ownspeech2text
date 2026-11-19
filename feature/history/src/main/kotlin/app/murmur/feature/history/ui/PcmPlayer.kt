package app.murmur.feature.history.ui

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Plays a history recording (PCM16 mono 16 kHz, decoded by the audio store) through a static [AudioTrack]
 * (media usage, speech content). The track is created on the first play and released when the composable leaves.
 * Position is polled every [POLL_MS] while playing; the end of the clip stops and rewinds.
 *
 * Audio focus: play asks for transient focus (music and other players pause for the clip) and does not start without
 * it (a call); any loss of focus pauses (spoken content is paused, not ducked); pause, the end of the clip and
 * release give it back. [rememberPcmPlayer] also pauses when the screen stops (app in the background).
 */
@Stable
internal class PcmPlayer(private val pcm: ShortArray, private val audioManager: AudioManager?) {
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    val durationMs: Long = pcm.size * 1000L / SAMPLE_RATE

    private var track: AudioTrack? = null
    private var focused = false

    private val attributes: AudioAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        // Called on the main looper.
        .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
        .build()

    private fun requestFocus(): Boolean {
        if (focused) return true
        val am = audioManager ?: return true
        focused = am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focused
    }

    private fun abandonFocus() {
        if (!focused) return
        focused = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun track(): AudioTrack? {
        track?.let { return it }
        if (pcm.isEmpty()) return null
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .also { t -> t.write(pcm, 0, pcm.size); track = t }
        } catch (_: Exception) {
            null
        }
    }

    fun toggle() { if (playing) pause() else play() }

    fun play() {
        val t = track() ?: return
        if (!requestFocus()) return
        val frame = frameOf(positionMs).let { if (it >= pcm.size - 1) 0 else it }
        try {
            t.stop()
            t.reloadStaticData()
            t.playbackHeadPosition = frame
            t.play()
            positionMs = frame * 1000L / SAMPLE_RATE
            playing = true
        } catch (_: IllegalStateException) {
            playing = false
            abandonFocus()
        }
    }

    fun pause() {
        if (playing) {
            track?.let { t -> try { t.pause(); positionMs = t.playbackHeadPosition * 1000L / SAMPLE_RATE } catch (_: IllegalStateException) {} }
        }
        playing = false
        abandonFocus()
    }

    fun seek(ms: Long) {
        val clamped = ms.coerceIn(0, durationMs)
        positionMs = clamped
        if (playing) play()
    }

    /** Called by the poll loop. */
    fun tick() {
        val t = track ?: return
        if (!playing) return
        val frame = t.playbackHeadPosition
        if (frame >= pcm.size - 1 || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            try { t.stop() } catch (_: IllegalStateException) {}
            playing = false
            positionMs = 0
            abandonFocus()
        } else {
            positionMs = frame * 1000L / SAMPLE_RATE
        }
    }

    fun release() {
        track?.release()
        track = null
        playing = false
        abandonFocus()
    }

    private fun frameOf(ms: Long): Int = (ms * SAMPLE_RATE / 1000).toInt().coerceIn(0, pcm.size)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val POLL_MS = 33L
    }
}

@Composable
internal fun rememberPcmPlayer(pcm: ShortArray): PcmPlayer {
    val context = LocalContext.current
    val player = remember(pcm) { PcmPlayer(pcm, context.getSystemService(AudioManager::class.java)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        // The composition outlives a stopped activity: pause when the app goes to the background.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    LaunchedEffect(player, player.playing) {
        while (isActive && player.playing) {
            delay(PcmPlayer.POLL_MS)
            player.tick()
        }
    }
    return player
}
