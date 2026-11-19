package app.murmur.core.context

import app.murmur.core.ocr.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Screen pixels -> screenshot pixels (field.box), the anchor of the renderer's "nearest to the field first" order. */
class CaptureFrameTest {
    @Test
    fun fullScreenWindowOnTheCoverIsIdentity() {
        val f = CaptureFrame.of(Rect(0, 0, 1080, 2520), 1080, 2520)
        assertEquals(CaptureFrame(0, 0, 1080, 2520), f)
        assertEquals(listOf(40, 2400, 940, 2490), f.toCapture(Rect(40, 2400, 940, 2490)))
    }

    @Test
    fun splitScreenRightPaneOnTheInnerDisplay() {
        // Inner display 1968 x 2184, app in the right half; window capture = 984 x 2184 starting at x = 984.
        val window = Rect(984, 0, 1968, 2184)
        val f = CaptureFrame.of(window, 984, 2184)
        assertEquals(CaptureFrame(984, 0, 984, 2184), f)
        assertEquals(listOf(16, 2000, 916, 2100), f.toCapture(Rect(1000, 2000, 1900, 2100)))
    }

    @Test
    fun splitScreenBottomPaneOnTheInnerDisplay() {
        // Top/bottom split: the app below the divider at y = 1100.
        val window = Rect(0, 1100, 1968, 2184)
        val f = CaptureFrame.of(window, 1968, 1084)
        assertEquals(listOf(40, 900, 1900, 1000), f.toCapture(Rect(40, 2000, 1900, 2100)))
    }

    @Test
    fun freeformWindowOffsetOnBothAxes() {
        val f = CaptureFrame.of(Rect(300, 450, 1300, 1850), 1000, 1400)
        assertEquals(listOf(20, 1300, 980, 1380), f.toCapture(Rect(320, 1750, 1280, 1830)))
    }

    @Test
    fun displayCaptureFallbackKeepsScreenCoordinates() {
        // Error 5 -> takeScreenshot(display): the capture is the display, not the (right-pane) window.
        val f = CaptureFrame.of(Rect(984, 0, 1968, 2184), 1968, 2184)
        assertEquals(CaptureFrame(0, 0, 1968, 2184), f)
        assertEquals(listOf(1000, 2000, 1900, 2100), f.toCapture(Rect(1000, 2000, 1900, 2100)))
    }

    @Test
    fun unknownWindowMeansScreenCoordinates() {
        assertEquals(CaptureFrame(0, 0, 1080, 2520), CaptureFrame.of(null, 1080, 2520))
    }

    @Test
    fun boxIsClippedToTheCapture() {
        val f = CaptureFrame.of(Rect(984, 0, 1968, 2184), 984, 2184)
        // Field crossing the split divider and the bottom edge.
        assertEquals(listOf(0, 2100, 500, 2184), f.toCapture(Rect(900, 2100, 1484, 2300)))
    }

    @Test
    fun boxOutsideTheCaptureIsDropped() {
        val f = CaptureFrame.of(Rect(984, 0, 1968, 2184), 984, 2184)
        assertNull(f.toCapture(Rect(40, 2000, 940, 2100)))
        assertNull(f.toCapture(Rect(984, 500, 984, 600))) // zero width
    }

    @Test
    fun frameOfTheWindowWithoutAScreenshot() {
        assertEquals(CaptureFrame(984, 0, 984, 2184), CaptureFrame.ofWindow(Rect(984, 0, 1968, 2184)))
        assertNull(CaptureFrame.ofWindow(null))
        assertNull(CaptureFrame.ofWindow(Rect(10, 10, 10, 50)))
    }
}
