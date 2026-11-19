package app.murmur.core.context

import app.murmur.core.ocr.Rect

/** Which screenshot path produced the pixels (ARCHITECTURE 9.1), when the caller knows it. */
enum class CaptureKind {
    /** takeScreenshotOfWindow of the target window: pixel (0, 0) is the window's top-left. */
    WINDOW,
    /** takeScreenshot of the display (fallback after error 5): pixel (0, 0) is the display's top-left. */
    DISPLAY,
}

/**
 * The pixel frame of a screenshot, expressed in screen coordinates: pixel (0, 0) of the capture is screen point
 * ([originX], [originY]) and the capture is [width] x [height] pixels (ARCHITECTURE 9.1).
 *
 * - Window capture (`takeScreenshotOfWindow`, the normal path): the capture has the window's size and its origin is
 *   the window's top-left (`AccessibilityWindowInfo.getBoundsInScreen`). In split screen or on the inner display the
 *   window does not start at (0, 0), so screen coordinates must be shifted.
 * - Display capture (fallback after error 5): the capture is the whole display, origin (0, 0).
 *
 * Accessibility bounds and screenshots share one pixel scale (both are physical display pixels), so the conversion is
 * a translation plus a clip to the capture, never a scale.
 */
data class CaptureFrame(val originX: Int, val originY: Int, val width: Int, val height: Int) {
    init { require(width >= 0 && height >= 0) { "negative frame size" } }

    /**
     * [r] (screen pixels) as `[x0, y0, x1, y1]` in capture pixels, clipped to the capture (half-open, like
     * getBoundsInScreen). null when nothing of [r] lies inside the capture.
     */
    fun toCapture(r: Rect): List<Int>? {
        val x0 = (r.x0 - originX).coerceIn(0, width)
        val y0 = (r.y0 - originY).coerceIn(0, height)
        val x1 = (r.x1 - originX).coerceIn(0, width)
        val y1 = (r.y1 - originY).coerceIn(0, height)
        return if (x1 > x0 && y1 > y0) listOf(x0, y0, x1, y1) else null
    }

    companion object {
        /**
         * Frame of a capture of [captureWidth] x [captureHeight] pixels taken for a target window with
         * [windowBounds] (screen pixels).
         *
         * [kind] as the capturer reports it wins: [CaptureKind.WINDOW] puts the origin at the window's top-left even
         * when the bitmap's size differs from the bounds (edge clipping, insets), [CaptureKind.DISPLAY] at (0, 0).
         * Without it the kind is inferred: a capture of exactly the window's size is a window capture, any other size
         * the display fallback. The inference cannot confuse a full-screen window (it starts at (0, 0) as well), but
         * a window capture that is off by a pixel would be taken for the display, so capturers pass [kind].
         */
        fun of(windowBounds: Rect?, captureWidth: Int, captureHeight: Int, kind: CaptureKind? = null): CaptureFrame {
            val window = windowBounds?.takeIf {
                when (kind) {
                    CaptureKind.WINDOW -> true
                    CaptureKind.DISPLAY -> false
                    null -> it.width == captureWidth && it.height == captureHeight
                }
            }
            return CaptureFrame(window?.x0 ?: 0, window?.y0 ?: 0, captureWidth, captureHeight)
        }

        /**
         * Frame of the window capture that would have been taken when there is no screenshot (privacy, secure window,
         * error): field.box then stays in the coordinates the training data uses. null when the window is unknown
         * (the box is then given in screen pixels, unclipped).
         */
        fun ofWindow(windowBounds: Rect?): CaptureFrame? =
            windowBounds?.takeIf { it.width > 0 && it.height > 0 }?.let { CaptureFrame(it.x0, it.y0, it.width, it.height) }
    }
}
