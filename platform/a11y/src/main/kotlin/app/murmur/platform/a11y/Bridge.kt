package app.murmur.platform.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.dictation.InsertResult
import app.murmur.core.dictation.Inserter
import app.murmur.core.dictation.TargetReader
import app.murmur.core.dictation.UserInput
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.ScreenImage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The orchestrator lives longer than any service instance. The connected service publishes itself here; the bridged
 * ports below forward to it and fail cleanly (AccessibilityOff) when it is not connected.
 */
object A11yBridge {
    val service = MutableStateFlow<MurmurAccessibilityService?>(null)
}

class BridgedTargetReader : TargetReader {
    private fun delegate() = A11yBridge.service.value?.targetReader
    override suspend fun snapshot(): A11ySnapshot? = delegate()?.snapshot()
    override suspend fun snapshotAfterShade(timeoutMs: Long): A11ySnapshot? = delegate()?.snapshotAfterShade(timeoutMs)
    override suspend fun screenshot(target: A11ySnapshot): Outcome<ScreenImage> =
        delegate()?.screenshot(target) ?: Outcome.Err(MurmurError.AccessibilityOff)
    override suspend fun current(): A11ySnapshot? = delegate()?.current()
}

class BridgedInserter : Inserter {
    private fun delegate() = A11yBridge.service.value?.inserter
    override suspend fun insert(target: A11ySnapshot, text: String): InsertResult =
        delegate()?.insert(target, text) ?: InsertResult.Failed(MurmurError.AccessibilityOff)
    override fun copyToClipboard(text: String): Boolean = delegate()?.copyToClipboard(text) ?: false
}

/**
 * Intents into this module. The status notification (WP-09, `:platform:audio`, which cannot depend on this module)
 * builds its "Start dictation" action as
 * `PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_START_DICTATION).setClassName(ctx, RECEIVER_CLASS), FLAG_IMMUTABLE)`.
 */
object A11yIntents {
    const val ACTION_START_DICTATION = "app.murmur.platform.a11y.action.START_DICTATION"
    const val RECEIVER_CLASS = "app.murmur.platform.a11y.NotificationStartReceiver"
}

/**
 * Notification "Start dictation" (DESIGN 5.1, ADR-056). A broadcast action does not collapse the shade by itself, so the
 * service closes it (GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE); the orchestrator then takes its target snapshot with
 * snapshotAfterShade. Not exported: only this app's own PendingIntent reaches it.
 */
class NotificationStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != A11yIntents.ACTION_START_DICTATION) return
        A11yBridge.service.value?.dismissNotificationShade()
        (context.applicationContext as? A11yServiceHost)?.onUserInput(UserInput.NotificationStart)
    }
}
