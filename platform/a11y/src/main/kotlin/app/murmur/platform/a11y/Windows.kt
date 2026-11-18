package app.murmur.platform.a11y

import android.view.accessibility.AccessibilityWindowInfo
import app.murmur.core.ocr.Rect

/**
 * What the service needs from an AccessibilityWindowInfo, platform-free, so that the window choice and the exclusion
 * rectangles are plain functions with JVM tests. [pkg] is the package of the window's root node; null when the window
 * has no root (or it was not asked for: only application and accessibility-overlay windows need it).
 */
data class WinInfo(
    val id: Int,
    val type: Int,
    val focused: Boolean,
    val layer: Int,
    val displayId: Int,
    val pkg: String?,
    val title: String?,
    val bounds: Rect,
) {
    val isApplication get() = type == AccessibilityWindowInfo.TYPE_APPLICATION
}

/** The target window rules of ARCHITECTURE 9.1 / ADR-056 (platform spike d: rootInActiveWindow is wrong at tap time). */
object WindowChoice {
    const val SYSTEM_UI = "com.android.systemui"

    /**
     * The app window the user works in: the focused TYPE_APPLICATION window, else the top-most application window.
     * At tap time the active window can be the bubble itself, and right after a cold start getWindows() can briefly
     * omit the app (platform spike c); both are handled by the caller re-resolving at capture time.
     */
    fun target(windows: List<WinInfo>): WinInfo? =
        windows.firstOrNull { it.isApplication && it.focused } ?: windows.filter { it.isApplication }.maxByOrNull { it.layer }

    /**
     * NOTIFICATION start (ADR-056): only a *focused* application window of a package other than SystemUI counts.
     * While the shade is down the app below it is not focused (the shade is), so a non-focused fallback would read
     * the wrong state.
     */
    fun afterShade(windows: List<WinInfo>): WinInfo? =
        windows.firstOrNull { it.isApplication && it.focused && it.pkg != null && it.pkg != SYSTEM_UI }

    /** The window a snapshot was taken from, at capture time: same id, else the target if it is the same package. */
    fun reResolve(windows: List<WinInfo>, windowId: Int, pkg: String): WinInfo? =
        windows.firstOrNull { it.id == windowId && it.isApplication } ?: target(windows)?.takeIf { it.pkg == pkg }
}

/**
 * OCR exclusion rectangles for the display-capture fallback (ARCHITECTURE 9.1): the IME window, Murmur's own
 * accessibility overlays and the system bars, in screen pixels, clipped to the display. A window capture needs none
 * (it holds only the app's pixels, platform spike 2).
 */
object ExclusionRects {
    fun of(windows: List<WinInfo>, ownPackage: String, displayId: Int, displayW: Int, displayH: Int): List<Rect> =
        windows.asSequence()
            .filter { it.displayId == displayId && excluded(it, ownPackage) }
            .mapNotNull { clip(it.bounds, displayW, displayH) }
            .distinct()
            .toList()

    private fun excluded(w: WinInfo, ownPackage: String): Boolean = when (w.type) {
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> true
        AccessibilityWindowInfo.TYPE_SYSTEM -> true
        // An overlay without a root node is ours too: the only service drawing overlays for this process is Murmur's.
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> w.pkg == null || w.pkg == ownPackage
        else -> false
    }

    /** Intersection with [0,w) x [0,h); null when empty. */
    fun clip(r: Rect, w: Int, h: Int): Rect? {
        val c = Rect(r.x0.coerceIn(0, w), r.y0.coerceIn(0, h), r.x1.coerceIn(0, w), r.y1.coerceIn(0, h))
        return c.takeIf { it.width > 0 && it.height > 0 }
    }
}
