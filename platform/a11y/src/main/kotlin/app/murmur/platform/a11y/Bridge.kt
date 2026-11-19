package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import app.murmur.core.dictation.UserInput

// Shim of WP-02's NotificationStart (main, Bridge.kt, ADR-079) on this branch's base: the same call for the app. At the
// merge main's Bridge.kt replaces this file.

/**
 * Notification "Start dictation" (DESIGN 5.1, ADR-056, ADR-079). The status notification and its one receiver belong to
 * WP-09 (`:platform:audio` StatusActionReceiver -> `StatusActionHandler`, implemented by the Application); the host
 * answers `StatusAction.StartDictation` with [start]. A broadcast action does not collapse the shade by itself, so the
 * service closes it (GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE); the orchestrator then takes its target snapshot with
 * snapshotAfterShade.
 */
object NotificationStart {
    fun start(context: Context) {
        A11yBridge.service.value?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        (context.applicationContext as? A11yServiceHost)?.dictation?.onInput(UserInput.NotificationStart)
    }
}
