package app.murmur.platform.a11y

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.CaptureKind
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

/** The input-focused node's window and package (findFocus(FOCUS_INPUT)). */
data class FocusWindow(val windowId: Int, val pkg: String?)

/** The platform calls behind [ScreenshotChain]; the Android implementation lives in [A11yTargetReader]. */
interface Shooter {
    suspend fun window(windowId: Int): ShotResult
    suspend fun display(displayId: Int): ShotResult
    /** The snapshot's window again, from a fresh window list (it can be transiently missing, platform spike c). */
    suspend fun resolveWindow(): Int?
    /** findFocus(FOCUS_INPUT), or null. */
    suspend fun inputFocus(): FocusWindow?
    /** Package of the application window in front on [displayId] ([WindowChoice.target]), or null. */
    suspend fun frontPackage(displayId: Int): String?
    /**
     * IME, own overlay, system bars and other apps' windows of the display, clipped to the captured frame, for the
     * display capture.
     */
    suspend fun exclusionRects(displayId: Int, width: Int, height: Int): List<Rect>
}

/** How the frame was obtained (logged as a code, kept for the call-time record). */
enum class ShotPath { WINDOW, WINDOW_RETRY, FOCUS_WINDOW, DISPLAY }

data class ShotRecord(val calledAtMs: Long, val finishedAtMs: Long, val path: ShotPath?, val errors: List<Int>, val error: String?)

/**
 * The capture rules of ARCHITECTURE 9.1, as a sequence over a [Shooter]:
 * window capture of the re-resolved target -> on error 3 one retry after >= 340 ms -> on error 5 (window gone)
 * findFocus(FOCUS_INPUT).windowId -> display capture + exclusion rects. Error 6 anywhere, or a black display frame
 * (FLAG_SECURE content in a display capture, platform spike c) -> SecureWindow.
 *
 * Only the target app's pixels may come back (the privacy filters of 9.2 ran on the snapshot's package): the input-focus
 * window is used only when it belongs to [capture]'s package, and the display is captured only while that package is
 * the one in front; otherwise the result is Err(TargetChanged), i.e. no screen context.
 * The per-window and per-display minimum interval (333 ms, independent; platform spike 2) is respected *before* each
 * call by [RateLimiter], so a second capture 200 ms after the first waits instead of burning a request.
 */
class ScreenshotChain(
    private val shooter: Shooter,
    private val limiter: RateLimiter,
    private val now: () -> Long,
) {
    suspend fun capture(pkg: String, snapshotWindowId: Int, displayId: Int): Pair<Outcome<ScreenImage>, ShotRecord> {
        val calledAt = now()
        val errors = mutableListOf<Int>()
        fun done(o: Outcome<ScreenImage>, path: ShotPath?) =
            o to ShotRecord(calledAt, now(), path, errors.toList(), (o as? Outcome.Err)?.error?.code)

        // The snapshot's own window id is safe even when the list no longer shows it: a window capture holds only
        // that window's pixels, and a window that is gone answers error 5.
        val windowId = shooter.resolveWindow() ?: snapshotWindowId.takeIf { it >= 0 }
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
                val focusId = shooter.inputFocus()?.takeIf { it.pkg == pkg }?.windowId
                if (focusId != null && focusId != windowId) { r = windowShot(focusId); path = ShotPath.FOCUS_WINDOW }
            }
            when (r) {
                // A window capture holds only the app's pixels; a dark app is not a secure one (error 6 covers that).
                is ShotResult.Frame -> return done(Outcome.Ok(r.image.withKind(CaptureKind.WINDOW)), path)
                is ShotResult.Failed -> {
                    if (r.code == ShotError.SECURE_WINDOW) { errors += r.code; return done(Outcome.Err(MurmurError.SecureWindow), path) }
                    if (errors.lastOrNull() != r.code) errors += r.code
                }
            }
        }
        // Display capture: everything on the display, so only while the target app is the one in front.
        if (!targetInFront(pkg, displayId)) return done(Outcome.Err(MurmurError.TargetChanged), null)
        val d = displayShot(displayId)
        return when (d) {
            is ShotResult.Frame -> {
                val img = d.image.let { ScreenImage(it.width, it.height, it.argb, shooter.exclusionRects(displayId, it.width, it.height), CaptureKind.DISPLAY) }
                if (FrameCheck.isBlack(img)) done(Outcome.Err(MurmurError.SecureWindow), ShotPath.DISPLAY)
                else done(Outcome.Ok(img), ShotPath.DISPLAY)
            }
            is ShotResult.Failed -> {
                errors += d.code
                val e = if (d.code == ShotError.SECURE_WINDOW) MurmurError.SecureWindow else MurmurError.ScreenshotFailed(d.code)
                done(Outcome.Err(e), ShotPath.DISPLAY)
            }
        }
    }

    /** Every known signal (input focus, front application window) names [pkg], and at least one is known. */
    private suspend fun targetInFront(pkg: String, displayId: Int): Boolean {
        val known = listOfNotNull(shooter.inputFocus()?.pkg, shooter.frontPackage(displayId))
        return known.isNotEmpty() && known.all { it == pkg }
    }

    private suspend fun windowShot(id: Int): ShotResult {
        limiter.awaitSlot(RateLimiter.windowKey(id))
        return shooter.window(id)
    }

    private suspend fun displayShot(id: Int): ShotResult {
        limiter.awaitSlot(RateLimiter.displayKey(id))
        return shooter.display(id)
    }
}

/** Minimum interval per window and per display, tracked separately (platform spike 2: 330 ms fails, 340 ms passes). */
class RateLimiter(private val now: () -> Long) {
    private val last = HashMap<String, Long>()

    fun waitFor(key: String): Long = synchronized(last) { last[key]?.let { (it + RETRY_AFTER_MS - now()).coerceAtLeast(0) } ?: 0L }

    /**
     * Reserves the next free slot of [key] (now, or the previous slot + [RETRY_AFTER_MS]) and waits until it. The slot
     * is taken before waiting, so two concurrent callers get slots one interval apart instead of waking together.
     */
    suspend fun awaitSlot(key: String) {
        val w = synchronized(last) {
            val t = now()
            val slot = last[key]?.let { maxOf(t, it + RETRY_AFTER_MS) } ?: t
            last[key] = slot
            slot - t
        }
        if (w > 0) delay(w)
    }

    companion object {
        const val RETRY_AFTER_MS = 340L
        fun windowKey(id: Int) = "w$id"
        fun displayKey(id: Int) = "d$id"
    }
}

/**
 * A FLAG_SECURE window shot through the display capture comes back black where the window is (platform spike c: centre
 * luma 5), while the status and navigation bars are still drawn. So the check covers the pixels outside the frame's
 * exclusion rectangles (IME, bars, own overlay, other apps), i.e. the target app's part of the display, and applies to
 * display captures only. Every such pixel is checked (the scan stops at the first non-black one, so a normal frame costs
 * a few reads): a true-black dark theme with a single line of light text is not black.
 */
object FrameCheck {
    private const val MAX_CHANNEL = 16

    fun isBlack(img: ScreenImage, ignore: List<Rect> = img.exclude): Boolean {
        if (img.width == 0 || img.height == 0) return true
        val w = img.width
        for (y in 0 until img.height) {
            val spans = ignore.filter { y >= it.y0 && y < it.y1 }
            var x = 0
            while (x < w) {
                val skip = spans.firstOrNull { x >= it.x0 && x < it.x1 }
                if (skip != null) { x = skip.x1; continue }
                val p = img.argb[y * w + x]
                if ((p shr 16 and 0xff) > MAX_CHANNEL || (p shr 8 and 0xff) > MAX_CHANNEL || (p and 0xff) > MAX_CHANNEL) return false
                x++
            }
        }
        return true
    }
}
