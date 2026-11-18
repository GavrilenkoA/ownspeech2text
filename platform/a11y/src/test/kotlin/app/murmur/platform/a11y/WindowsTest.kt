package app.murmur.platform.a11y

import app.murmur.core.ocr.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowsTest {
    private val app = 1 // AccessibilityWindowInfo.TYPE_APPLICATION
    private val ime = 2
    private val system = 3
    private val overlay = 4

    private fun w(id: Int, type: Int, focused: Boolean = false, layer: Int = 0, pkg: String? = null, display: Int = 0,
                  bounds: Rect = Rect(0, 0, 1968, 2184)) =
        WinInfo(id, type, focused, layer, display, pkg, title = "w$id", bounds = bounds)

    @Test
    fun targetIsTheFocusedApplicationWindowNotTheBubble() {
        // At tap time the bubble overlay is on top and active (platform spike d); the app window is focused.
        val ws = listOf(
            w(9, overlay, layer = 30, pkg = "app.murmur"),
            w(5, system, layer = 20),
            w(3, app, focused = false, layer = 2, pkg = "com.other"),
            w(4, app, focused = true, layer = 1, pkg = "org.telegram.messenger"),
        )
        assertEquals(4, WindowChoice.target(ws)?.id)
    }

    @Test
    fun withoutFocusTheTopMostApplicationWindowIsTheTarget() {
        val ws = listOf(w(3, app, layer = 1, pkg = "a"), w(7, app, layer = 5, pkg = "b"), w(9, overlay, layer = 30))
        assertEquals(7, WindowChoice.target(ws)?.id)
        assertNull(WindowChoice.target(listOf(w(9, overlay, layer = 30, pkg = "app.murmur"))))
    }

    @Test
    fun shadeDownMeansNoAppWindowYet() {
        // Notification shade expanded: the shade (SystemUI) has focus, the app below is not focused.
        val shade = listOf(
            w(20, system, focused = true, layer = 40),
            w(4, app, focused = false, layer = 1, pkg = "org.telegram.messenger"),
        )
        assertNull(WindowChoice.afterShade(shade))
        // A focused SystemUI application window (e.g. its own activity) does not count either.
        assertNull(WindowChoice.afterShade(listOf(w(21, app, focused = true, pkg = WindowChoice.SYSTEM_UI))))
        // After the collapse the app is focused again.
        val collapsed = listOf(w(4, app, focused = true, layer = 1, pkg = "org.telegram.messenger"))
        assertEquals(4, WindowChoice.afterShade(collapsed)?.id)
    }

    @Test
    fun reResolveKeepsTheSnapshotWindowOrTheSamePackage() {
        val ws = listOf(w(4, app, focused = true, pkg = "x"), w(5, app, pkg = "y"))
        assertEquals(5, WindowChoice.reResolve(ws, windowId = 5, pkg = "y")?.id)
        // Window id changed (activity recreated) but the same package is in front.
        assertEquals(4, WindowChoice.reResolve(ws, windowId = 99, pkg = "x")?.id)
        // Another app is in front: no window for this snapshot.
        assertNull(WindowChoice.reResolve(ws, windowId = 99, pkg = "z"))
    }

    @Test
    fun exclusionRectsAreImeOwnOverlayAndBarsClippedToTheDisplay() {
        val ws = listOf(
            w(1, app, focused = true, pkg = "com.other"),
            w(2, ime, bounds = Rect(0, 1343, 1968, 2184)),
            w(3, overlay, pkg = "app.murmur", bounds = Rect(0, 600, 168, 768)),
            w(4, overlay, pkg = "com.someone.else", bounds = Rect(10, 10, 20, 20)),
            w(5, system, bounds = Rect(0, 0, 1968, 63)),
            w(6, system, bounds = Rect(0, 2100, 2000, 2300)), // nav bar partly off-screen
            w(7, ime, display = 1, bounds = Rect(0, 0, 100, 100)), // other display
            w(8, system, bounds = Rect(0, 0, 0, 0)), // empty
        )
        val rects = ExclusionRects.of(ws, ownPackage = "app.murmur", displayId = 0, displayW = 1968, displayH = 2184)
        assertEquals(
            listOf(Rect(0, 1343, 1968, 2184), Rect(0, 600, 168, 768), Rect(0, 0, 1968, 63), Rect(0, 2100, 1968, 2184)),
            rects,
        )
    }

    @Test
    fun clipDropsRectanglesOutsideTheFrame() {
        assertNull(ExclusionRects.clip(Rect(2000, 0, 2100, 50), 1968, 2184))
        assertEquals(Rect(0, 0, 10, 10), ExclusionRects.clip(Rect(-5, -5, 10, 10), 1968, 2184))
    }
}
