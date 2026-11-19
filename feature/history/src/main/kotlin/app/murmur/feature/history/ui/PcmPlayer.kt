package app.murmur.feature.history.ui

import android.media.AudioAttributes
import android.media.AudioFormat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Plays a history recording (PCM16 mono 16 kHz, decoded by the audio store) through a static [AudioTrack]
 * (media usage, speech content). The track is created on the first play and released when the composable leaves.
 * Position is polled every [POLL_MS] while playing; the end of the clip stops and rewinds.
 */
@Stable
internal class PcmPlayer(private val pcm: ShortArray) {
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    val durationMs: Long = pcm.size * 1000L / SAMPLE_RATE

    private var track: AudioTrack? = null

    private fun track(): AudioTrack? {
        track?.let { return it }
        if (pcm.isEmpty()) return null
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
        }
    }

    fun pause() {
        track?.let { t -> try { t.pause(); positionMs = t.playbackHeadPosition * 1000L / SAMPLE_RATE } catch (_: IllegalStateException) {} }
        playing = false
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
        } else {
            positionMs = frame * 1000L / SAMPLE_RATE
        }
    }

    fun release() {
        track?.release()
        track = null
        playing = false
    }

    private fun frameOf(ms: Long): Int = (ms * SAMPLE_RATE / 1000).toInt().coerceIn(0, pcm.size)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val POLL_MS = 33L
    }
}

@Composable
internal fun rememberPcmPlayer(pcm: ShortArray): PcmPlayer {
    val player = remember(pcm) { PcmPlayer(pcm) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, player.playing) {
        while (isActive && player.playing) {
            delay(PcmPlayer.POLL_MS)
            player.tick()
        }
    }
    return player
}
