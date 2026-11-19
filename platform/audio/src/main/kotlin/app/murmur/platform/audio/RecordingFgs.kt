package app.murmur.platform.audio

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Typed microphone FGS (platform spike b, ADR-028): `startForeground(id, n, FOREGROUND_SERVICE_TYPE_MICROPHONE)` with
 * FOREGROUND_SERVICE_DEFERRED, so short dictations never show it. It lives only while AudioRecord records (I3); its
 * notification only says that the microphone is on, with a Stop action. Started and stopped through
 * [RecordingFgsController] only.
 */
class RecordingFgs : Service() {
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val generation = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        StatusChannel.ensure(this)
        try {
            // Always reach startForeground once per start command: a service started with startForegroundService and
            // brought down before it would crash the app ("did not then call startForeground").
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            RecordingFgsController.onForeground(this, generation)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException (no while-in-use access): recording
            // continues without the FGS (android-platform 12, ARMING failure paths); the reason is logged as a code.
            RecordingFgsController.onFailed(generation, e.javaClass.simpleName)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RecordingFgsController.onDestroyed(this)
        super.onDestroy()
    }

    /** Main thread. Leaves the foreground state and stops, unless a newer start command is already queued. */
    internal fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun notification(): Notification {
        val stop = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_mic),
            getString(R.string.notif_action_stop),
            StatusChannel.actionIntent(this, StatusActionReceiver.ACTION_STOP, REQ_STOP),
        ).build()
        return Notification.Builder(this, StatusChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notif_mic_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(StatusChannel.openAppIntent(this))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .addAction(stop)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 0x4D09
        internal const val EXTRA_GENERATION = "generation"
        private const val REQ_STOP = 4
    }
}

/**
 * Start/stop of [RecordingFgs] for one capture at a time, safe against every ordering of "start command delivered",
 * "stop requested" and "next capture started": a stop never calls stopService (which would crash the app if the
 * service had not reached startForeground yet); the service always goes foreground first and then stops itself when
 * no capture wants it any more.
 */
object RecordingFgsController {
    sealed interface State {
        data object Idle : State
        data class Starting(val generation: Long) : State
        data class Foreground(val generation: Long, val msFromRequest: Long) : State
        /** startForegroundService or startForeground refused; recording continues without the FGS. */
        data class Failed(val generation: Long, val reason: String) : State
    }

    private const val TAG = "MurmurFgs"
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    private var wanted = false
    private var requestedAt = 0L
    /** Main thread only. */
    private var service: RecordingFgs? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called on the press path, right before AudioRecord.startRecording (android-platform 4.5). Returns the generation. */
    fun start(context: Context): Long {
        val gen = synchronized(lock) {
            generation++
            wanted = true
            requestedAt = SystemClock.uptimeMillis()
            generation
        }
        _state.value = State.Starting(gen)
        try {
            context.startForegroundService(
                Intent(context, RecordingFgs::class.java).putExtra(RecordingFgs.EXTRA_GENERATION, gen),
            )
        } catch (e: Exception) {
            onFailed(gen, e.javaClass.simpleName)
        }
        return gen
    }

    /** Capture of [gen] ended. A stale generation (a newer capture started meanwhile) changes nothing. */
    fun stop(gen: Long) {
        synchronized(lock) {
            if (gen != generation) return
            wanted = false
        }
        main.post { finishIfUnwanted() }
    }

    internal fun onForeground(svc: RecordingFgs, gen: Long) {
        service = svc
        val ms = SystemClock.uptimeMillis() - requestedAt
        Log.i(TAG, "fgs_started gen=$gen ms=$ms")
        val current = synchronized(lock) { gen == generation && wanted }
        if (current) _state.value = State.Foreground(gen, ms)
        finishIfUnwanted()
    }

    internal fun onFailed(gen: Long, reason: String) {
        Log.w(TAG, "fgs_failed gen=$gen reason=$reason")
        _state.value = State.Failed(gen, reason)
    }

    internal fun onDestroyed(svc: RecordingFgs) {
        if (service === svc) service = null
        val idle = synchronized(lock) { !wanted }
        if (idle && _state.value !is State.Failed) _state.value = State.Idle
    }

    private fun finishIfUnwanted() {
        val unwanted = synchronized(lock) { !wanted }
        if (!unwanted) return
        service?.finish()
        if (_state.value !is State.Failed) _state.value = State.Idle
    }
}
