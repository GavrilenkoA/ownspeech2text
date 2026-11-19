package app.murmur.platform.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A bare accessibility service for the overlay test: it only provides the TYPE_ACCESSIBILITY_OVERLAY window token and
 * reads the focused field / keyboard bounds the way :platform:a11y does. Enabled by the test through secure settings.
 */
class OverlayTestService : AccessibilityService() {
    override fun onServiceConnected() {
        instance.value = this
        connected.countDown()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    /** Top of the focused editable field and of the IME window, in screen pixels (null when absent). */
    fun fieldAndIme(): Pair<Int?, Int?> {
        val field: AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        val r = Rect()
        val fieldTop = field?.let { it.getBoundsInScreen(r); r.top }
        val imeTop = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { it.getBoundsInScreen(r); r.top }
        return fieldTop to imeTop
    }

    /** Bounds on screen of the first editable node in [pkg]'s windows (to tap it), or null. */
    fun editableBounds(pkg: String): Rect? {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isEditable) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val node = windows.mapNotNull { it.root }.filter { it.packageName == pkg }.firstNotNullOfOrNull { find(it) } ?: return null
        return Rect().also { node.getBoundsInScreen(it) }
    }

    companion object {
        val instance = MutableStateFlow<OverlayTestService?>(null)
        @Volatile private var connected = CountDownLatch(1)

        fun reset() { connected = CountDownLatch(1) }
        fun await(seconds: Long): OverlayTestService? {
            connected.await(seconds, TimeUnit.SECONDS)
            return instance.value
        }
    }
}
