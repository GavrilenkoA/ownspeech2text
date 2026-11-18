package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.store.SettingsRepository

/**
 * Implemented by the Application (:app). Gives the service its collaborators without a DI framework
 * (ADR-010). Only the :main process has it.
 */
interface A11yServiceHost {
    val dictation: DictationOrchestrator
    val settings: SettingsRepository
    /**
     * Called when the service connects/disconnects (Dashboard/Settings "The bubble is off" banner). On disconnect the
     * host also calls DictationOrchestrator.onAccessibilityLost() (ExternalStop(A11Y_OFF) for a running dictation).
     */
    fun onServiceState(connected: Boolean)
}

/**
 * The accessibility service (WP-02). Owns: focus tracking -> VisibilityInput, one OverlayHost per display,
 * the target snapshot at press, screenshots, the insertion ladder, the AccessibilityInputMethod (API 33)
 * for commitText/getSurroundingText. Node reads on a single HandlerThread ("a11y-io"), never on main.
 */
class MurmurAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yBridge.service.value = this
        (application as? A11yServiceHost)?.onServiceState(true)
        // WP-02: attach OverlayHost for the default display; register DisplayListener.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // WP-02: focus / window changes -> visibility policy (debounced 300 ms on blur, DESIGN 5.2).
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        A11yBridge.service.value = null
        (application as? A11yServiceHost)?.onServiceState(false)
        return super.onUnbind(intent)
    }
}
