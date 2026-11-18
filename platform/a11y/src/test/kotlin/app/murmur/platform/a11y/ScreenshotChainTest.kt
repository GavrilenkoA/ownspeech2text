package app.murmur.platform.a11y

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotChainTest {
    private fun frame(w: Int = 8, h: Int = 4, color: Int = 0xFFF4F1EA.toInt()) = ScreenImage(w, h, IntArray(w * h) { color })

    /** Scripted platform: per window id / display id a queue of results; records the call times. */
    private class FakeShooter(
        private val clock: () -> Long,
        val windowResults: MutableMap<Int, ArrayDeque<ShotResult>> = mutableMapOf(),
        val displayResults: ArrayDeque<ShotResult> = ArrayDeque(),
        var resolved: Int? = 10,
        var focusId: Int? = null,
        val rects: List<Rect> = listOf(Rect(0, 2, 8, 4)),
    ) : Shooter {
        val calls = mutableListOf<Pair<String, Long>>()
        override suspend fun window(windowId: Int): ShotResult {
            calls += "w$windowId" to clock()
            return windowResults[windowId]?.removeFirstOrNull() ?: ShotResult.Failed(ShotError.INVALID_WINDOW)
        }
        override suspend fun display(displayId: Int): ShotResult {
            calls += "d$displayId" to clock()
            return displayResults.removeFirstOrNull() ?: ShotResult.Failed(ShotError.INTERNAL)
        }
        override suspend fun resolveWindow() = resolved
        override suspend fun inputFocusWindow() = focusId
        override suspend fun exclusionRects(displayId: Int, width: Int, height: Int) = rects
    }

    private fun TestScope.chain(s: FakeShooter, limiter: RateLimiter = RateLimiter { testScheduler.currentTime }) =
        ScreenshotChain(s, limiter) { testScheduler.currentTime }

    private fun TestScope.shooter() = FakeShooter({ testScheduler.currentTime })

    @Test
    fun windowCaptureNeedsNoExclusionRects() = runTest {
        val s = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Frame(frame()))) }
        val (o, rec) = chain(s).capture(fallbackWindowId = 10, displayId = 0)
        val img = (o as Outcome.Ok).value
        assertTrue(img.exclude.isEmpty())
        assertEquals(ShotPath.WINDOW, rec.path)
        assertEquals(listOf("w10"), s.calls.map { it.first })
    }

    @Test
    fun intervalErrorIsRetriedOnceAfter340ms() = runTest {
        val s = shooter().apply {
            windowResults[10] = ArrayDeque(listOf(ShotResult.Failed(ShotError.INTERVAL_TIME_SHORT), ShotResult.Frame(frame())))
        }
        val (o, rec) = chain(s).capture(10, 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(ShotPath.WINDOW_RETRY, rec.path)
        assertEquals(listOf(ShotError.INTERVAL_TIME_SHORT), rec.errors)
        assertTrue(s.calls[1].second - s.calls[0].second >= 340)
    }

    @Test
    fun twoCapturesTwoHundredMsApartWaitInsteadOfFailing() = runTest {
        val s = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Frame(frame()), ShotResult.Frame(frame()))) }
        val limiter = RateLimiter { testScheduler.currentTime }
        val c = chain(s, limiter)
        c.capture(10, 0)
        testScheduler.advanceTimeBy(200)
        val (o, _) = c.capture(10, 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(340L, s.calls[1].second - s.calls[0].second)
    }

    @Test
    fun windowAndDisplayLimitsAreIndependent() = runTest {
        val limiter = RateLimiter { testScheduler.currentTime }
        limiter.awaitSlot(RateLimiter.windowKey(10))
        assertEquals(0L, limiter.waitFor(RateLimiter.displayKey(0)))
        assertEquals(340L, limiter.waitFor(RateLimiter.windowKey(10)))
        assertEquals(0L, limiter.waitFor(RateLimiter.windowKey(11)))
    }

    @Test
    fun goneWindowFallsBackToTheInputFocusWindowThenTheDisplay() = runTest {
        val s = shooter().apply { focusId = 12; windowResults[12] = ArrayDeque(listOf(ShotResult.Frame(frame()))) }
        val (o, rec) = chain(s).capture(10, 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(ShotPath.FOCUS_WINDOW, rec.path)
        assertEquals(listOf("w10", "w12"), s.calls.map { it.first })

        val s2 = shooter().apply { displayResults += ShotResult.Frame(frame()) }
        val (o2, rec2) = chain(s2).capture(10, 0)
        val img = (o2 as Outcome.Ok).value
        assertEquals(ShotPath.DISPLAY, rec2.path)
        assertEquals(listOf(Rect(0, 2, 8, 4)), img.exclude) // display capture carries IME/overlay/bar rects
        assertEquals(listOf("w10", "d0"), s2.calls.map { it.first })
    }

    @Test
    fun missingWindowInTheListUsesFocusThenDisplay() = runTest {
        // getWindows() transiently omitted the app (platform spike c): no resolved window, no focus.
        val s = shooter().apply { resolved = null; focusId = null; displayResults += ShotResult.Frame(frame()) }
        val (o, rec) = chain(s).capture(fallbackWindowId = -1, displayId = 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(listOf("d0"), s.calls.map { it.first })
        assertEquals(ShotPath.DISPLAY, rec.path)
    }

    @Test
    fun secureWindowIsNoScreenAndTheBlackDisplayFrameToo() = runTest {
        val s = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Failed(ShotError.SECURE_WINDOW))) }
        val (o, rec) = chain(s).capture(10, 0)
        assertEquals(Outcome.Err(MurmurError.SecureWindow), o)
        assertEquals(listOf("w10"), s.calls.map { it.first }) // no display capture of a secure window
        assertEquals(MurmurError.SecureWindow.code, rec.error)

        val s2 = shooter().apply { resolved = null; displayResults += ShotResult.Frame(frame(color = 0xFF050505.toInt())) }
        assertEquals(Outcome.Err(MurmurError.SecureWindow), chain(s2).capture(-1, 0).first)
    }

    @Test
    fun displayFailureReportsThePlatformCode() = runTest {
        val s = shooter().apply { resolved = null; displayResults += ShotResult.Failed(ShotError.INTERNAL) }
        assertEquals(Outcome.Err(MurmurError.ScreenshotFailed(ShotError.INTERNAL)), chain(s).capture(-1, 0).first)
    }

    @Test
    fun blackFrameDetection() {
        assertTrue(FrameCheck.isBlack(frame(100, 100, 0xFF000000.toInt())))
        assertTrue(FrameCheck.isBlack(frame(100, 100, 0xFF101010.toInt())))
        assertFalse(FrameCheck.isBlack(frame(100, 100, 0xFFF4F1EA.toInt())))
        // A dark-theme screen with some light text is not black.
        val dark = frame(96, 96, 0xFF000000.toInt())
        for (x in 0 until 96) dark.argb[1 * 96 + x] = 0xFFE0E0E0.toInt()
        assertFalse(FrameCheck.isBlack(dark))
    }
}
