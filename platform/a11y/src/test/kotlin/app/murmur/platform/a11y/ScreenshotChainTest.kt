package app.murmur.platform.a11y

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.CaptureKind
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotChainTest {
    private companion object {
        const val PKG = "app.target"
        const val OTHER = "com.other.app"
    }

    private fun frame(w: Int = 8, h: Int = 4, color: Int = 0xFFF4F1EA.toInt()) = ScreenImage(w, h, IntArray(w * h) { color })

    /** Scripted platform: per window id / display id a queue of results; records the call times. */
    private class FakeShooter(
        private val clock: () -> Long,
        val windowResults: MutableMap<Int, ArrayDeque<ShotResult>> = mutableMapOf(),
        val displayResults: ArrayDeque<ShotResult> = ArrayDeque(),
        var resolved: Int? = 10,
        var focus: FocusWindow? = null,
        var front: String? = PKG,
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
        override suspend fun inputFocus() = focus
        override suspend fun frontPackage(displayId: Int) = front
        override suspend fun exclusionRects(displayId: Int, width: Int, height: Int) = rects
    }

    private fun TestScope.chain(s: FakeShooter, limiter: RateLimiter = RateLimiter { testScheduler.currentTime }) =
        ScreenshotChain(s, limiter) { testScheduler.currentTime }

    private fun TestScope.shooter(rects: List<Rect> = listOf(Rect(0, 2, 8, 4))) = FakeShooter({ testScheduler.currentTime }, rects = rects)

    @Test
    fun windowCaptureNeedsNoExclusionRects() = runTest {
        val s = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Frame(frame()))) }
        val (o, rec) = chain(s).capture(PKG, snapshotWindowId = 10, displayId = 0)
        val img = (o as Outcome.Ok).value
        assertTrue(img.exclude.isEmpty())
        assertEquals(ShotPath.WINDOW, rec.path)
        assertEquals(listOf("w10"), s.calls.map { it.first })
    }

    /** The capture path reaches the context assembler as ScreenImage.kind (CaptureFrame places field.box with it). */
    @Test
    fun theFrameCarriesTheCapturePath() = runTest {
        val w = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Frame(frame()))) }
        assertEquals(CaptureKind.WINDOW, (chain(w).capture(PKG, 10, 0).first as Outcome.Ok).value.kind)
        val d = shooter().apply { resolved = null; focus = FocusWindow(10, PKG); displayResults += ShotResult.Frame(frame()) }
        val (o, rec) = chain(d).capture(PKG, -1, 0)
        assertEquals(ShotPath.DISPLAY, rec.path)
        assertEquals(CaptureKind.DISPLAY, (o as Outcome.Ok).value.kind)
    }

    @Test
    fun intervalErrorIsRetriedOnceAfter340ms() = runTest {
        val s = shooter().apply {
            windowResults[10] = ArrayDeque(listOf(ShotResult.Failed(ShotError.INTERVAL_TIME_SHORT), ShotResult.Frame(frame())))
        }
        val (o, rec) = chain(s).capture(PKG, 10, 0)
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
        c.capture(PKG, 10, 0)
        testScheduler.advanceTimeBy(200)
        val (o, _) = c.capture(PKG, 10, 0)
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
        val s = shooter().apply { focus = FocusWindow(12, PKG); windowResults[12] = ArrayDeque(listOf(ShotResult.Frame(frame()))) }
        val (o, rec) = chain(s).capture(PKG, 10, 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(ShotPath.FOCUS_WINDOW, rec.path)
        assertEquals(listOf("w10", "w12"), s.calls.map { it.first })

        val s2 = shooter().apply { displayResults += ShotResult.Frame(frame()) }
        val (o2, rec2) = chain(s2).capture(PKG, 10, 0)
        val img = (o2 as Outcome.Ok).value
        assertEquals(ShotPath.DISPLAY, rec2.path)
        assertEquals(listOf(Rect(0, 2, 8, 4)), img.exclude) // display capture carries IME/overlay/bar rects
        assertEquals(listOf("w10", "d0"), s2.calls.map { it.first })
    }

    @Test
    fun missingWindowInTheListUsesTheSnapshotWindowThenTheDisplay() = runTest {
        // getWindows() transiently omitted the app (platform spike c): the snapshot's own window id is still captured.
        val s = shooter().apply { resolved = null; windowResults[10] = ArrayDeque(listOf(ShotResult.Frame(frame()))) }
        val (o, rec) = chain(s).capture(PKG, snapshotWindowId = 10, displayId = 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(ShotPath.WINDOW, rec.path)
        assertEquals(listOf("w10"), s.calls.map { it.first })

        // No window id at all, the target is in front: display capture.
        val s2 = shooter().apply { resolved = null; focus = FocusWindow(12, PKG); displayResults += ShotResult.Frame(frame()) }
        val (o2, rec2) = chain(s2).capture(PKG, snapshotWindowId = -1, displayId = 0)
        assertTrue(o2 is Outcome.Ok)
        assertEquals(listOf("d0"), s2.calls.map { it.first })
        assertEquals(ShotPath.DISPLAY, rec2.path)
    }

    @Test
    fun anotherAppsFocusWindowIsNeverCaptured() = runTest {
        // The user switched apps: the snapshot window is gone (error 5) and the input focus is in another package.
        val s = shooter().apply { resolved = null; focus = FocusWindow(12, OTHER); front = OTHER
            windowResults[12] = ArrayDeque(listOf(ShotResult.Frame(frame()))); displayResults += ShotResult.Frame(frame()) }
        val (o, rec) = chain(s).capture(PKG, 10, 0)
        assertEquals(Outcome.Err(MurmurError.TargetChanged), o)
        assertEquals(listOf("w10"), s.calls.map { it.first }) // neither the other app's window nor the display
        assertEquals(listOf(ShotError.INVALID_WINDOW), rec.errors)
    }

    @Test
    fun displayIsCapturedOnlyWhileTheTargetIsInFront() = runTest {
        suspend fun case(focus: FocusWindow?, front: String?): Pair<Outcome<ScreenImage>, List<String>> {
            val s = shooter().apply { resolved = null; this.focus = focus; this.front = front; displayResults += ShotResult.Frame(frame()) }
            return chain(s).capture(PKG, -1, 0).first to s.calls.map { it.first }
        }
        val gone = Outcome.Err(MurmurError.TargetChanged)
        assertEquals(gone to emptyList<String>(), case(focus = null, front = OTHER))
        assertEquals(gone to emptyList<String>(), case(focus = FocusWindow(5, OTHER), front = null))
        assertEquals(gone to emptyList<String>(), case(focus = FocusWindow(5, PKG), front = OTHER)) // split screen, other app on top
        assertEquals(gone to emptyList<String>(), case(focus = null, front = null)) // nothing confirms the target
        assertEquals(listOf("d0"), case(focus = null, front = PKG).second)
        assertEquals(listOf("d0"), case(focus = FocusWindow(5, PKG), front = PKG).second)
    }

    @Test
    fun secureWindowIsNoScreenAndTheBlackDisplayFrameToo() = runTest {
        val s = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Failed(ShotError.SECURE_WINDOW))) }
        val (o, rec) = chain(s).capture(PKG, 10, 0)
        assertEquals(Outcome.Err(MurmurError.SecureWindow), o)
        assertEquals(listOf("w10"), s.calls.map { it.first }) // no display capture of a secure window
        assertEquals(MurmurError.SecureWindow.code, rec.error)

        val s2 = shooter().apply { resolved = null; displayResults += ShotResult.Frame(frame(color = 0xFF050505.toInt())) }
        assertEquals(Outcome.Err(MurmurError.SecureWindow), chain(s2).capture(PKG, -1, 0).first)

        // As on the emulator: the secure app's area is black, the status bar (an exclusion rectangle) is drawn.
        val shot = frame(8, 4, 0xFF000000.toInt()).also { for (x in 0 until 8) it.argb[x] = 0xFFE0E0E0.toInt() }
        val s3 = shooter(rects = listOf(Rect(0, 0, 8, 1))).apply { resolved = null; displayResults += ShotResult.Frame(shot) }
        assertEquals(Outcome.Err(MurmurError.SecureWindow), chain(s3).capture(PKG, -1, 0).first)
    }

    @Test
    fun aBlackWindowFrameIsAnAppNotASecureWindow() = runTest {
        // True-black (AMOLED) dark theme: the window capture is all but black; FLAG_SECURE would have been error 6.
        val s = shooter().apply { windowResults[10] = ArrayDeque(listOf(ShotResult.Frame(frame(color = 0xFF000000.toInt())))) }
        val (o, rec) = chain(s).capture(PKG, 10, 0)
        assertTrue(o is Outcome.Ok)
        assertEquals(ShotPath.WINDOW, rec.path)
    }

    @Test
    fun concurrentCapturesGetSlotsOneIntervalApart() = runTest {
        val limiter = RateLimiter { testScheduler.currentTime }
        limiter.awaitSlot("w1") // slot at 0
        testScheduler.advanceTimeBy(100)
        val woke = mutableListOf<Long>()
        val a = launch { limiter.awaitSlot("w1"); woke += testScheduler.currentTime }
        val b = launch { limiter.awaitSlot("w1"); woke += testScheduler.currentTime }
        a.join(); b.join()
        assertEquals(listOf(340L, 680L), woke.sorted())
    }

    @Test
    fun displayFailureReportsThePlatformCode() = runTest {
        val s = shooter().apply { resolved = null; displayResults += ShotResult.Failed(ShotError.INTERNAL) }
        assertEquals(Outcome.Err(MurmurError.ScreenshotFailed(ShotError.INTERNAL)), chain(s).capture(PKG, -1, 0).first)
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
        // One lit pixel anywhere counts: every pixel is checked, not a sampling grid.
        val sparse = frame(960, 960, 0xFF000000.toInt())
        sparse.argb[403 * 960 + 517] = 0xFFE0E0E0.toInt()
        assertFalse(FrameCheck.isBlack(sparse))
        // Pixels inside the exclusion rectangles (bars, keyboard, other apps) do not count; the rest does.
        assertTrue(FrameCheck.isBlack(sparse, ignore = listOf(Rect(500, 400, 520, 410))))
        assertFalse(FrameCheck.isBlack(sparse, ignore = listOf(Rect(500, 400, 517, 410), Rect(518, 0, 960, 960))))
    }
}
