package app.murmur.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock

/** Short-lived FGS of type microphone; lives exactly while AudioRecord records. */
class RecordingFgs : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val t0 = intent?.getLongExtra("t0", 0L) ?: 0L
        ensureChannel(this)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Murmur is listening").setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED).build()
        try {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            Ev.log("fgs_started", "type" to "microphone", "msFromTap" to (SystemClock.uptimeMillis() - t0))
            SpikeA11yService.instance?.onFgsStarted(t0)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException (missing WIU capability)
            Ev.log("fgs_start_failed", "err" to e.toString())
            SpikeA11yService.instance?.onFgsFailed(t0, e.toString())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { Ev.log("fgs_destroyed"); super.onDestroy() }

    companion object {
        const val CHANNEL = "rec"
        fun ensureChannel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
