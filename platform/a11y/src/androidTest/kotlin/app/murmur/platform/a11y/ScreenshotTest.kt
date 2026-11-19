package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.ScreenImage
import app.murmur.platform.a11y.A11yTestSupport.enableService
import app.murmur.platform.a11y.A11yTestSupport.launch
import app.murmur.platform.a11y.A11yTestSupport.launchOtherApp
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.service
import app.murmur.platform.a11y.A11yTestSupport.snapshotOf
import app.murmur.platform.a11y.A11yTestSupport.snapshotOfPackage
import app.murmur.platform.a11y.A11yTestSupport.waitFor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** screenshot(): ARCHITECTURE 9.1 on the emulator (window capture, FLAG_SECURE, rate limit). */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @Before fun setUp() { enableService() }

    private fun windows() = runBlocking { withContext(service().ioDispatcher) { service().nodes.windows() } }

    private fun close(a: Int, b: Int) = (0..2).all { abs((a shr (it * 8) and 0xff) - (b shr (it * 8) and 0xff)) <= 3 }

    @Test
    fun windowFrameHasTheWindowSizeAndNoKeyboardPixels() = runBlocking {
        launch(PlainTargetActivity::class.java) { putExtra("prefill", "Frame") }
        val snap = snapshotOf("Plain target")
        val ime = waitFor("keyboard shown", 10_000) { windows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        val app = windows().first { it.id == snap.windowId }
        delay(400) // first frame after the keyboard animation; also clears the rate limit of the snapshot polling
        val img = (service().targetReader.screenshot(snap) as Outcome.Ok<ScreenImage>).value
        val rec = service().targetReader.lastShot!!
        note("screenshot window=${app.bounds} frame=${img.width}x${img.height} ime=${ime.bounds} path=${rec.path} ms=${rec.finishedAtMs - rec.calledAtMs}")
        assertEquals(ShotPath.WINDOW, rec.path)
        assertEquals(app.bounds.width, img.width)
        assertEquals(app.bounds.height, img.height)
        assertTrue(img.exclude.isEmpty())
        // The keyboard covers this part of the window on screen; in the window capture it is the app's background.
        val x0 = (ime.bounds.x0 - app.bounds.x0).coerceAtLeast(0)
        val y0 = (ime.bounds.y0 - app.bounds.y0).coerceAtLeast(0)
        val x1 = (ime.bounds.x1 - app.bounds.x0).coerceAtMost(img.width)
        val y1 = (ime.bounds.y1 - app.bounds.y0).coerceAtMost(img.height)
        assertTrue("keyboard overlaps the window", x1 > x0 && y1 > y0)
        var same = 0; var total = 0
        for (y in y0 until y1 step 8) for (x in x0 until x1 step 8) {
            total++
            if (close(img.argb[y * img.width + x], Tgt.BACKGROUND)) same++
        }
        note("screenshot ime_region background_share=${same * 1000 / total}/1000 samples=$total")
        assertTrue("background share $same/$total", same >= total * 99 / 100)
    }

    @Test
    fun secureWindowIsSecureWindow() = runBlocking {
        launch(SecureTargetActivity::class.java)
        val snap = snapshotOf("Secure target")
        delay(400)
        val r = service().targetReader.screenshot(snap)
        val rec = service().targetReader.lastShot!!
        note("screenshot secure result=${(r as? Outcome.Err)?.error?.code} errors=${rec.errors} path=${rec.path}")
        assertEquals(Outcome.Err(MurmurError.SecureWindow), r)
        assertEquals(listOf(ShotError.SECURE_WINDOW), rec.errors)
    }

    /** The display capture of a FLAG_SECURE window is black (FrameCheck, the display path's rule); a normal one is not. */
    @Test
    fun displayCaptureOfASecureWindowIsBlack() = runBlocking {
        launch(SecureTargetActivity::class.java)
        val secure = snapshotOf("Secure target")
        delay(400)
        val secureFrame = displayFrame(secure.displayId)
        launch(PlainTargetActivity::class.java) { putExtra("prefill", "Frame") }
        val plain = snapshotOf("Plain target")
        delay(400)
        val plainFrame = displayFrame(plain.displayId)
        note("display_capture secure=${secureFrame.width}x${secureFrame.height} black=${FrameCheck.isBlack(secureFrame)} plain_black=${FrameCheck.isBlack(plainFrame)}")
        assertTrue(FrameCheck.isBlack(secureFrame))
        assertFalse(FrameCheck.isBlack(plainFrame))
    }

    /** F1: after a switch to another app, the snapshot's app is captured or nothing is; never the app in front. */
    @Test
    fun anotherAppInFrontIsNeverCaptured() = runBlocking {
        launch(PlainTargetActivity::class.java) { putExtra("prefill", "Mine") }
        val mine = snapshotOf("Plain target")
        launchOtherApp()
        snapshotOfPackage(A11yTestSupport.OTHER_APP)
        delay(400)
        val r = service().targetReader.screenshot(mine)
        val rec = service().targetReader.lastShot!!
        note("screenshot after_app_switch result=${(r as? Outcome.Err)?.error?.code ?: "ok"} errors=${rec.errors} path=${rec.path}")
        assertTrue("path ${rec.path}", rec.path != ShotPath.FOCUS_WINDOW && rec.path != ShotPath.DISPLAY)
        if (r is Outcome.Ok) assertEquals(ShotPath.WINDOW, rec.path) // the snapshot's own window, still alive
        else assertEquals(Outcome.Err(MurmurError.TargetChanged), r)
    }

    @Test
    fun secondCapture200msLaterWaitsAndSucceeds() = runBlocking {
        launch(PlainTargetActivity::class.java)
        val snap = snapshotOf("Plain target")
        val reader = service().targetReader
        reader.screenshot(snap) // warm-up: the first capture after a launch can be slow on a loaded host
        delay(400)
        val t1 = SystemClock.uptimeMillis()
        val first = async { reader.screenshot(snap) }
        delay(200)
        val t2 = SystemClock.uptimeMillis()
        val second = async { reader.screenshot(snap) }
        val r1 = first.await()
        val r2 = second.await()
        val done2 = SystemClock.uptimeMillis()
        val (a, b) = reader.recentShots.takeLast(2).sortedBy { it.calledAtMs }
        note("screenshot pair requested_gap=${t2 - t1} second_done_after_first_request=${done2 - t1} " +
            "first=${a.path}${a.errors} second=${b.path}${b.errors} calls_apart=${b.calledAtMs - a.calledAtMs}")
        assertTrue(r1 is Outcome.Ok)
        assertTrue(r2 is Outcome.Ok)
        assertTrue(t2 - t1 < 333) // asked inside the platform's minimum interval
        assertTrue(b.calledAtMs - a.calledAtMs < 333)
        assertTrue(done2 - t1 >= RateLimiter.RETRY_AFTER_MS) // and served after it
        // Both from the window itself: the limiter waited (no error), or error 3 was answered by the one retry.
        // A display capture, or any other error, means the rate limit was not handled.
        for (rec in listOf(a, b)) {
            val waited = rec.path == ShotPath.WINDOW && rec.errors.isEmpty()
            val retried = rec.path == ShotPath.WINDOW_RETRY && rec.errors == listOf(ShotError.INTERVAL_TIME_SHORT)
            assertTrue("path=${rec.path} errors=${rec.errors}", waited || retried)
        }
    }

    private suspend fun displayFrame(displayId: Int): ScreenImage = suspendCancellableCoroutine { cont ->
        service().takeScreenshot(displayId, { it.run() }, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hb = result.hardwareBuffer
                val sw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)!!.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                val px = IntArray(sw.width * sw.height).also { sw.getPixels(it, 0, sw.width, 0, 0, sw.width, sw.height) }
                cont.resume(ScreenImage(sw.width, sw.height, px))
            }

            override fun onFailure(errorCode: Int) {
                cont.resumeWithException(AssertionError("display capture failed with $errorCode"))
            }
        })
    }
}
