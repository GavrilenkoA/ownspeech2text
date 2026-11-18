package app.murmur.platform.a11y

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import kotlinx.coroutines.delay

/** AccessibilityService.ERROR_TAKE_SCREENSHOT_* (API 30/34); duplicated as plain ints for the JVM tests. */
object ShotError {
    const val INTERNAL = 1
    const val NO_ACCESS = 2
    const val INTERVAL_TIME_SHORT = 3
    const val INVALID_DISPLAY = 4
    const val INVALID_WINDOW = 5
    const val SECURE_WINDOW = 6
}

sealed interface ShotResult {
    class Frame(val image: ScreenImage) : ShotResult
    data class Failed(val code: Int) : ShotResult
}

/** The platform calls behind [ScreenshotChain]; the Android implementation lives in [A11yTargetReader]. */
interface Shooter {
    suspend fun window(windowId: Int): ShotResult
    suspend fun display(displayId: Int): ShotResult
    /** The snapshot's window again, from a fresh window list (it can be transiently missing, platform spike c). */
    suspend fun resolveWindow(): Int?
    /** findFocus(FOCUS_INPUT).windowId, or null. */
    suspend fun inputFocusWindow(): Int?
    /** IME, own overlay and system bars of the display, clipped to the captured frame, for the display capture. */
    suspend fun exclusionRects(displayId: Int, width: Int, height: Int): List<Rect>
}

/** How the frame was obtained (logged as a code, kept for the call-time record). */
enum class ShotPath { WINDOW, WINDOW_RETRY, FOCUS_WINDOW, DISPLAY }

data class ShotRecord(val calledAtMs: Long, val finishedAtMs: Long, val path: ShotPath?, val errors: List<Int>, val error: String?)

/**
 * The capture rules of ARCHITECTURE 9.1, as a sequence over a [Shooter]:
 * window capture of the re-resolved target -> on error 3 one retry after >= 340 ms -> on error 5 (window gone)
 * findFocus(FOCUS_INPUT).windowId -> display capture + exclusion rects. Error 6 or a black frame anywhere -> SecureWindow.
 * The per-window and per-display minimum interval (333 ms, independent; platform spike 2) is respected *before* each
 * call by [RateLimiter], so a second capture 200 ms after the first waits instead of burning a request.
 */
class ScreenshotChain(
    private val shooter: Shooter,
    private val limiter: RateLimiter,
    private val now: () -> Long,
) {
    suspend fun capture(fallbackWindowId: Int, displayId: Int): Pair<Outcome<ScreenImage>, ShotRecord> {
        val calledAt = now()
        val errors = mutableListOf<Int>()
        fun done(o: Outcome<ScreenImage>, path: ShotPath?) =
            o to ShotRecord(calledAt, now(), path, errors.toList(), (o as? Outcome.Err)?.error?.code)

        val windowId = shooter.resolveWindow() ?: shooter.inputFocusWindow() ?: fallbackWindowId.takeIf { it >= 0 }
        if (windowId != null) {
            var r = windowShot(windowId)
            var path = ShotPath.WINDOW
            if (r is ShotResult.Failed && r.code == ShotError.INTERVAL_TIME_SHORT) {
                errors += r.code
                delay(RateLimiter.RETRY_AFTER_MS)
                r = windowShot(windowId); path = ShotPath.WINDOW_RETRY
            }
            if (r is ShotResult.Failed && r.code == ShotError.INVALID_WINDOW) {
                errors += r.code
                val focusId = shooter.inputFocusWindow()
                if (focusId != null && focusId != windowId) { r = windowShot(focusId); path = ShotPath.FOCUS_WINDOW }
            }
            when (r) {
                is ShotResult.Frame -> return done(frameOutcome(r.image), path)
                is ShotResult.Failed -> {
                    if (r.code == ShotError.SECURE_WINDOW) { errors += r.code; return done(Outcome.Err(MurmurError.SecureWindow), path) }
                    if (errors.lastOrNull() != r.code) errors += r.code
                }
            }
        }
        // Display capture: contains the keyboard, the bubble and the bars, so OCR gets their rectangles excluded.
        val d = displayShot(displayId)
        return when (d) {
            is ShotResult.Frame -> {
                val img = d.image
                done(frameOutcome(ScreenImage(img.width, img.height, img.argb, shooter.exclusionRects(displayId, img.width, img.height))), ShotPath.DISPLAY)
            }
            is ShotResult.Failed -> {
                errors += d.code
                val e = if (d.code == ShotError.SECURE_WINDOW) MurmurError.SecureWindow else MurmurError.ScreenshotFailed(d.code)
                done(Outcome.Err(e), ShotPath.DISPLAY)
            }
        }
    }

    private suspend fun windowShot(id: Int): ShotResult {
        limiter.awaitSlot(RateLimiter.windowKey(id))
        return shooter.window(id)
    }

    private suspend fun displayShot(id: Int): ShotResult {
        limiter.awaitSlot(RateLimiter.displayKey(id))
        return shooter.display(id)
    }

    private fun frameOutcome(img: ScreenImage): Outcome<ScreenImage> =
        if (FrameCheck.isBlack(img)) Outcome.Err(MurmurError.SecureWindow) else Outcome.Ok(img)
}

/** Minimum interval per window and per display, tracked separately (platform spike 2: 330 ms fails, 340 ms passes). */
class RateLimiter(private val now: () -> Long) {
    private val last = HashMap<String, Long>()

    fun waitFor(key: String): Long = last[key]?.let { (it + RETRY_AFTER_MS - now()).coerceAtLeast(0) } ?: 0L

    /** Waits until [key] may be captured again, then records the call time. */
    suspend fun awaitSlot(key: String) {
        val w = synchronized(last) { waitFor(key) }
        if (w > 0) delay(w)
        synchronized(last) { last[key] = now() }
    }

    companion object {
        const val RETRY_AFTER_MS = 340L
        fun windowKey(id: Int) = "w$id"
        fun displayKey(id: Int) = "d$id"
    }
}

/**
 * A FLAG_SECURE window shot through the display capture comes back black (platform spike c: centre luma 5). A frame
 * whose sampled pixels are all near black has no text for OCR either way, so it is treated as "no screen".
 */
object FrameCheck {
    private const val GRID = 48
    private const val MAX_CHANNEL = 16

    fun isBlack(img: ScreenImage): Boolean {
        if (img.width == 0 || img.height == 0) return true
        for (gy in 0 until GRID) {
            val y = ((gy * 2 + 1) * img.height) / (GRID * 2)
            for (gx in 0 until GRID) {
                val x = ((gx * 2 + 1) * img.width) / (GRID * 2)
                val p = img.argb[y * img.width + x]
                if ((p shr 16 and 0xff) > MAX_CHANNEL || (p shr 8 and 0xff) > MAX_CHANNEL || (p and 0xff) > MAX_CHANNEL) return false
            }
        }
        return true
    }
}
