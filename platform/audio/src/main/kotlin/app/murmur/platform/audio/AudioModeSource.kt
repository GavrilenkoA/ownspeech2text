package app.murmur.platform.audio

import android.content.Context
import android.media.AudioManager
import java.util.concurrent.Executor

/**
 * The AudioManager mode as seen by the capture: telephony and VoIP calls switch it (ARCHITECTURE 4.4 call row).
 * An interface so that the CallStarted path is testable without a modem (fake source in tests).
 */
interface AudioModeSource {
    val mode: Int
    /** Calls [listener] with every new mode until the returned handle is closed. */
    fun observe(executor: Executor, listener: (Int) -> Unit): AutoCloseable
}

/** Which modes mean "a call started" for a running dictation (CaptureSignal.CallStarted). */
object CallModes {
    /**
     * IN_CALL (telephony), IN_COMMUNICATION (VoIP), RINGTONE (an incoming call rings: the in-call UI takes the screen and
     * the ringtone would be recorded), CALL_SCREENING, CALL_REDIRECT, COMMUNICATION_REDIRECT.
     */
    fun isCall(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_CALL_SCREENING,
        AudioManager.MODE_CALL_REDIRECT,
        AudioManager.MODE_COMMUNICATION_REDIRECT -> true
        else -> false
    }

    /** Stable names for logs (codes only, D9). */
    fun name(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
        else -> "MODE_$mode"
    }
}

/** AudioManager.addOnModeChangedListener (API 31). */
class AndroidAudioModeSource(context: Context) : AudioModeSource {
    private val am = context.getSystemService(AudioManager::class.java)

    override val mode: Int get() = am.mode

    override fun observe(executor: Executor, listener: (Int) -> Unit): AutoCloseable {
        val l = AudioManager.OnModeChangedListener { listener(it) }
        am.addOnModeChangedListener(executor, l)
        return AutoCloseable { runCatching { am.removeOnModeChangedListener(l) } }
    }
}
