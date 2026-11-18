package app.murmur.platform.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.murmur.core.model.ProductName

/** What the user tapped on one of the two notifications (status: Hide / Show now / Start; microphone: Stop). */
sealed interface StatusAction {
    /** "Hide 1 h": the bubble stays hidden until [untilEpochMs] (the host writes Settings.hiddenUntilEpochMs). */
    data class Hide(val untilEpochMs: Long) : StatusAction
    /** "Show now": clears Settings.hiddenUntilEpochMs. */
    data object ShowNow : StatusAction
    /** "Start dictation" -> UserInput.NotificationStart (ARCHITECTURE 5.2 row 3). */
    data object StartDictation : StatusAction
    /** "Stop" on the microphone notification: finish the running dictation (same as a tap on the Listening pill). */
    data object StopDictation : StatusAction
}

/**
 * Implemented by the Application of `:main` (like A11yServiceHost): the notification actions arrive here through
 * [StatusActionReceiver]. The host maps them to Settings writes and orchestrator inputs (WP-15 / WP-20 wiring).
 */
interface StatusActionHandler {
    fun onStatusAction(action: StatusAction)
}

/** Receives the notification actions (explicit, not exported) and forwards them to the [StatusActionHandler]. */
class StatusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_HIDE -> StatusAction.Hide(System.currentTimeMillis() + HIDE_MS)
            ACTION_SHOW -> StatusAction.ShowNow
            ACTION_START -> StatusAction.StartDictation
            ACTION_STOP -> StatusAction.StopDictation
            else -> return
        }
        val handler = context.applicationContext as? StatusActionHandler
        if (handler == null) {
            Log.w(TAG, "status action without a handler: ${intent.action}")
            return
        }
        handler.onStatusAction(action)
    }

    companion object {
        const val ACTION_HIDE = "app.murmur.platform.audio.action.HIDE_1H"
        const val ACTION_SHOW = "app.murmur.platform.audio.action.SHOW_NOW"
        const val ACTION_START = "app.murmur.platform.audio.action.START_DICTATION"
        const val ACTION_STOP = "app.murmur.platform.audio.action.STOP_DICTATION"
        /** DESIGN 5.4: the bubble hides for one hour. */
        const val HIDE_MS = 60 * 60 * 1000L
        private const val TAG = "MurmurStatus"
    }
}

/** The one silent, low-importance channel of DESIGN 5.1, shared by the status and the microphone notification. */
internal object StatusChannel {
    const val ID = "status"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(R.string.notif_channel_status, ProductName.NAME)
        val channel = NotificationChannel(ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notif_channel_status_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        // Re-creating an existing channel only updates its name and description (language change), never user choices.
        nm.createNotificationChannel(channel)
    }

    fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(action).setClass(context, StatusActionReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Tapping the notification body opens the app (its launcher activity), if it has one. */
    fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
