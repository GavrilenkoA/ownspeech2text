package app.murmur.spike

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

/**
 * A Compose bubble in a TYPE_ACCESSIBILITY_OVERLAY window owned by an AccessibilityService.
 * No SYSTEM_ALERT_WINDOW. One instance per display (pass the display id); the window context
 * is created for that display so the same code serves a second logical display.
 *
 * Lift as-is: lifecycle/saved-state owners, LayoutParams, drag with updateViewLayout,
 * clamp on display change, hide via alpha + FLAG_NOT_TOUCHABLE (never View.GONE).
 */
class OverlayHost(
    private val service: AccessibilityService,
    private val displayId: Int,
    private val onTap: () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val display: Display =
        service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
    /** Window context bound to the target display and window type. */
    private val ctx: Context = service.createWindowContext(
        display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null
    )
    private val wm = ctx.getSystemService(WindowManager::class.java)

    var state by mutableStateOf("idle")
    private val sizePx = (64 * ctx.resources.displayMetrics.density).roundToInt()
    private var attached = false

    val lp = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MurmurBubble"
        setCanPlayMoveAnimation(false) // API 34
        x = 0; y = 600
    }

    private val view = ComposeView(ctx).apply {
        setViewTreeLifecycleOwner(this@OverlayHost)
        setViewTreeSavedStateRegistryOwner(this@OverlayHost)
        setViewTreeViewModelStoreOwner(this@OverlayHost)
        setContent { Bubble() }
    }

    @androidx.compose.runtime.Composable
    private fun Bubble() {
        val color = when (state) { "listening" -> Color(0xFFE5484D); "busy" -> Color(0xFFF5A524); else -> Color(0xFF3E63DD) }
        Box(
            Modifier.fillMaxSize().background(color, CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { Ev.log("bubble_tap", "display" to displayId); onTap() }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { snapToEdge(); Ev.log("bubble_drag_end", "x" to lp.x, "y" to lp.y) },
                    ) { change, drag ->
                        change.consume()
                        lp.x += drag.x.roundToInt(); lp.y += drag.y.roundToInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text(if (state == "listening") "REC" else "M", color = Color.White, fontSize = 16.sp) }
    }

    fun attach() {
        if (attached) return
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        runCatching { wm.addView(view, lp); attached = true }
            .onFailure { Ev.log("bubble_attach_failed", "err" to it.toString()) } // BadTokenException
        Ev.log("bubble_attached", "display" to displayId, "ok" to attached, "type" to lp.type,
            "bounds" to wm.currentWindowMetrics.bounds.toShortString())
    }

    fun detach() {
        if (!attached) return
        runCatching { wm.removeViewImmediate(view) }
        attached = false
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        Ev.log("bubble_detached", "display" to displayId)
    }

    fun destroy() { detach(); lifecycleRegistry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }

    /** Hide without GONE: Ramblr saw GONE overlay roots stall fold display switches. */
    fun setHidden(hidden: Boolean) {
        view.alpha = if (hidden) 0f else 1f
        lp.flags = if (hidden) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (attached) runCatching { wm.updateViewLayout(view, lp) }
    }

    /** Re-clamp after rotation / resize / fold: keep the edge and the proportional y. */
    fun onDisplayChanged(reason: String) {
        if (!attached) return
        val b = wm.currentWindowMetrics.bounds
        val oldW = lastBounds?.width() ?: b.width(); val oldH = lastBounds?.height() ?: b.height()
        val onRight = lp.x + sizePx / 2 > oldW / 2
        val fracY = (lp.y.toFloat() / (oldH - sizePx).coerceAtLeast(1)).coerceIn(0f, 1f)
        lp.x = if (onRight) b.width() - sizePx else 0
        lp.y = (fracY * (b.height() - sizePx)).roundToInt()
        runCatching { wm.updateViewLayout(view, lp) }
        lastBounds = b
        Ev.log("bubble_reclamped", "reason" to reason, "bounds" to b.toShortString(), "x" to lp.x, "y" to lp.y,
            "rotation" to display.rotation, "attached" to view.isAttachedToWindow)
    }
    private var lastBounds: android.graphics.Rect? = null

    private fun snapToEdge() {
        val b = wm.currentWindowMetrics.bounds
        lp.x = if (lp.x + sizePx / 2 > b.width() / 2) b.width() - sizePx else 0
        lp.y = lp.y.coerceIn(0, b.height() - sizePx)
        runCatching { wm.updateViewLayout(view, lp) }
        lastBounds = b
    }

    /** Screen-space centre of the bubble, for `adb shell input tap`. */
    fun centre(): Pair<Int, Int> {
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        return (loc[0] + sizePx / 2) to (loc[1] + sizePx / 2)
    }
}
