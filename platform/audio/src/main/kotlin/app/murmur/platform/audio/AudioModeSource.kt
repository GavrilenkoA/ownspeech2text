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
     * the ringtone would be recorded; on the API 36 emulator an incoming call sets RINGTONE and IN_CALL only once it is
     * answered), CALL_SCREENING, CALL_REDIRECT, COMMUNICATION_REDIRECT.
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

/**
 * Decides when a mode change is "a call started" (CaptureSignal.CallStarted): only a change, during the recording, to a
 * call mode other than the one before it. A call that is already up when the dictation starts is not a reason to stop:
 * an accessibility service may capture during calls (android-platform 4.1 item 6), and VoIP apps often leave
 * MODE_IN_COMMUNICATION set after their call; a new call arriving later (NORMAL -> RINGTONE, IN_COMMUNICATION -> IN_CALL)
 * still is. Thread-safe: the mode listener and the capture thread may both call it.
 */
class CallWatch(modeAtStart: Int) {
    private var last = modeAtStart

    /**
     * true when the mode changed to a call mode (RINGTONE -> IN_CALL as well: the session signals CallStarted once);
     * repeated reports of the same mode, and changes to a non-call mode, never are.
     */
    @Synchronized
    fun onMode(mode: Int): Boolean {
        val started = mode != last && CallModes.isCall(mode)
        last = mode
        return started
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
