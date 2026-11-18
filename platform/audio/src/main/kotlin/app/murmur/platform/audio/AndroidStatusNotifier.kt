package app.murmur.platform.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import app.murmur.core.dictation.StatusNotifier
import app.murmur.core.model.ProductName

/**
 * The persistent idle notification of DESIGN 5.1 / ADR-055: a plain ongoing notification (not an FGS; the microphone
 * notification exists only while recording, I3) on the silent "<product> status" channel. Title "<product> is ready",
 * the models line as subtitle, actions "Hide 1 h" + "Start dictation", or "Show now" + "Start dictation" while the
 * bubble is hidden ([hiddenUntilEpochMs] in the future). When the hidden time runs out the notification flips back to
 * "Hide 1 h" by itself. WP-15 decides when it is shown (service connected, notifications allowed).
 */
class AndroidStatusNotifier(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : StatusNotifier {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastModels: String? = null
    private val unhide = Runnable { lastModels?.let { show(it, null) } }

    override fun show(modelsLine: String, hiddenUntilEpochMs: Long?) {
        main.removeCallbacks(unhide)
        if (!canPost()) return
        lastModels = modelsLine
        val hidden = hiddenUntilEpochMs != null && hiddenUntilEpochMs > now()
        StatusChannel.ensure(context)
        nm.notify(NOTIFICATION_ID, build(modelsLine, hidden))
        if (hidden) main.postDelayed(unhide, hiddenUntilEpochMs - now())
    }

    override fun hide() {
        main.removeCallbacks(unhide)
        lastModels = null
        nm.cancel(NOTIFICATION_ID)
    }

    internal fun build(modelsLine: String, hidden: Boolean): Notification {
        val visibility = if (hidden) {
            action(R.string.notif_action_show, StatusActionReceiver.ACTION_SHOW, REQ_SHOW)
        } else {
            action(R.string.notif_action_hide, StatusActionReceiver.ACTION_HIDE, REQ_HIDE)
        }
        return Notification.Builder(context, StatusChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentTitle(context.getString(R.string.notif_ready_title, ProductName.NAME))
            .setContentText(modelsLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(StatusChannel.openAppIntent(context))
            .addAction(visibility)
            .addAction(action(R.string.notif_action_start, StatusActionReceiver.ACTION_START, REQ_START))
            .build()
    }

    private fun action(label: Int, action: String, request: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_mark),
            context.getString(label),
            StatusChannel.actionIntent(context, action, request),
        ).build()

    private fun canPost(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            nm.areNotificationsEnabled()

    companion object {
        const val NOTIFICATION_ID = 0x4D01
        private const val REQ_HIDE = 1
        private const val REQ_SHOW = 2
        private const val REQ_START = 3
    }
}
