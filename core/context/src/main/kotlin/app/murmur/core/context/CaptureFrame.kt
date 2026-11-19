package app.murmur.core.context

import app.murmur.core.ocr.Rect

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
         * [windowBounds] (screen pixels). A capture of exactly the window's size is a window capture (origin = the
         * window's top-left); any other size is the display-capture fallback (origin (0, 0)). The two cannot be
         * confused: a window as large as its display starts at (0, 0) as well.
         */
        fun of(windowBounds: Rect?, captureWidth: Int, captureHeight: Int): CaptureFrame =
            if (windowBounds != null && windowBounds.width == captureWidth && windowBounds.height == captureHeight) {
                CaptureFrame(windowBounds.x0, windowBounds.y0, captureWidth, captureHeight)
            } else {
                CaptureFrame(0, 0, captureWidth, captureHeight)
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
