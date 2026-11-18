package app.murmur.platform.overlay

import android.content.Context
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.dictation.UserInput
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose bubble in a TYPE_ACCESSIBILITY_OVERLAY window (no SYSTEM_ALERT_WINDOW; platform spike a), one per
 * display. Lift spikes/platform OverlayHost.kt: manual Lifecycle/SavedState/ViewModelStore owners,
 * WRAP_CONTENT + FLAG_NOT_FOCUSABLE window, drag via updateViewLayout, hide via alpha + FLAG_NOT_TOUCHABLE
 * (never View.GONE: fold BLAST stall), re-clamp on DisplayListener.onDisplayChanged. WP-03.
 *
 * [context] must be the AccessibilityService (window token of the accessibility overlay type).
 */
class OverlayHost(
    private val context: Context,
    private val displayId: Int,
    private val dictation: StateFlow<DictationBubble>,
    private val levels: StateFlow<FloatArray>,
    private val onInput: (UserInput) -> Unit,
) {
    fun attach(): Unit = TODO("WP-03")
    fun updateVisibility(input: VisibilityInput): Unit = TODO("WP-03")
    fun detach(): Unit = TODO("WP-03")
}
