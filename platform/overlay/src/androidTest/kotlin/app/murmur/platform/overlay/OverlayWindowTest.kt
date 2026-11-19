package app.murmur.platform.overlay

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.dictation.UserInput
import app.murmur.testing.InMemorySettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * WP-03 acceptance "Overlay on the emulator": a test accessibility service hosts the real [OverlayHost] over
 * :testapps:target. Window type, drag/snap/hide, size (wm size cover <-> inner), posture (hinge, driven from the host by
 * tools/run_emulator_tests.sh) and rotation 0-3, with the same edge and proportional y. Positions are logged with the
 * tag WP03 (and MurmurBubble by the host).
 *
 * Needs murmur_fold7_inner (1968 x 2184) and app.murmur.testtarget installed.
 */
@RunWith(AndroidJUnit4::class)
class OverlayWindowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ua = inst.uiAutomation
    private val ctx = inst.targetContext
    private val settings = InMemorySettingsRepository()
    private val dictation = MutableStateFlow<DictationBubble>(DictationBubble.Idle)
    private val levels = MutableStateFlow(FloatArray(7))
    private val inputs = CopyOnWriteArrayList<UserInput>()
    private lateinit var service: OverlayTestService
    private lateinit var host: OverlayHost
    private var previousServices = ""
    private var previousAutoRotate = ""

    private fun sh(cmd: String): String =
        ua.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }

    private fun log(step: String, s: OverlayHost.Snapshot) =
        Log.i(TAG, "step=$step w=${s.screenW} h=${s.screenH} rot=${s.rotation} right=${s.rightEdge} y=${s.circleTop} " +
            "frac=${s.yFraction?.let { "%.3f".format(it) }} visual=${s.visual}")

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        inst.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun snap(): OverlayHost.Snapshot = onMain { host.snapshot() }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(50)
        }
        fail("timed out waiting for $what")
    }

    private fun focusedInput(fieldTop: Int? = null, imeTop: Int? = null) = VisibilityInput(
        editableFocused = true, bubbleAllowedForField = true, appHidden = false, hiddenUntilMs = null,
        nowMs = System.currentTimeMillis(), showAlways = false, idleSinceMs = null, tuckIn = true, talkBackOn = false,
        fieldTop = fieldTop, imeTop = imeTop,
    )

    @Before
    fun setUp() {
        assertTrue("install :testapps:target first (tools/run_emulator_tests.sh does)", sh("pm path $TARGET_PKG").contains("package:"))
        previousServices = sh("settings get secure enabled_accessibility_services").trim()
        previousAutoRotate = sh("settings get system accelerometer_rotation").trim()
        OverlayTestService.reset()
        sh("settings put secure enabled_accessibility_services ${ctx.packageName}/${OverlayTestService::class.java.name}")
        sh("settings put secure accessibility_enabled 1")
        service = OverlayTestService.await(20) ?: error("test accessibility service did not connect")
        sh("am start -W -n $TARGET_PKG/.TargetActivity")
        waitUntil("target window") { service.windows.any { it.root?.packageName == TARGET_PKG } }
        onMain {
            host = OverlayHost(service, Display.DEFAULT_DISPLAY, dictation, levels, { inputs += it }, settings)
            host.attach()
        }
    }

    @After
    fun tearDown() {
        if (::host.isInitialized) onMain { host.detach() }
        sh("wm size reset")
        sh("settings put system user_rotation 0")
        if (previousAutoRotate.isNotEmpty() && previousAutoRotate != "null") sh("settings put system accelerometer_rotation $previousAutoRotate")
        if (previousServices.isEmpty() || previousServices == "null") sh("settings delete secure enabled_accessibility_services")
        else sh("settings put secure enabled_accessibility_services $previousServices")
    }

    @Test
    fun bubbleIsAnAccessibilityOverlayOverTheTargetApp_placedAboveTheKeyboard() {
        val (fieldTop, imeTop) = service.fieldAndIme()
        Log.i(TAG, "target fieldTop=$fieldTop imeTop=$imeTop")
        assertNotNull("the target's EditText has input focus", fieldTop)
        onMain { host.updateVisibility(focusedInput(fieldTop, imeTop)) }
        waitUntil("Ready") { snap().visual == BubbleVisual.Ready }
        SystemClock.sleep(500)
        val s = snap()
        log("over-target", s)

        // 1. Window type, from WindowManager's own dump.
        val dump = sh("dumpsys window windows")
        val block = checkNotNull(dump.split("Window #").firstOrNull { it.contains("MurmurBubble}") }) { "MurmurBubble not in dumpsys window" }
        val typeLine = block.lines().firstOrNull { it.contains(" ty=") }.orEmpty().trim()
        Log.i(TAG, "dumpsys: ${block.lineSequence().first().trim()} | $typeLine")
        assertTrue(typeLine, typeLine.contains("ty=ACCESSIBILITY_OVERLAY"))

        // 2. No SYSTEM_ALERT_WINDOW anywhere in this package.
        val requested = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("android.permission.SYSTEM_ALERT_WINDOW"))

        // 3. Over the target app: the overlay window is above the app's window.
        val windows = service.windows
        val overlay = checkNotNull(windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) { "no overlay window" }
        val app = windows.first { it.root?.packageName == TARGET_PKG }
        assertTrue("overlay layer ${overlay.layer} > app layer ${app.layer}", overlay.layer > app.layer)

        // 4. DESIGN 5.4: the target's field sits near the top, so the bubble goes 12 dp above the keyboard (or the bottom
        //    of the usable area when no keyboard is up), and never below imeTop - 12 dp.
        val d = ctx.resources.displayMetrics.density
        val top = s.circleTop!!
        if (imeTop != null) assertEquals(imeTop - (12 * d).roundToInt() - s.diameterPx, top)
        assertTrue(top >= (16 * d).roundToInt())

        // 5. Pixels: the mark is drawn where the bubble is.
        val shot: Bitmap = checkNotNull(ua.takeScreenshot()) { "no screenshot" }
        Shots.save("overlay-over-target", shot)
        val cx = s.screenW - (8 * d).roundToInt() - s.diameterPx / 2
        val cy = top + s.diameterPx / 2
        var ink = 0
        for (dx in -s.diameterPx / 3..s.diameterPx / 3) for (dy in -s.diameterPx / 3..s.diameterPx / 3) {
            val p = shot.getPixel(cx + dx, cy + dy)
            if (((p shr 16) and 0xFF) < 80 && ((p shr 8) and 0xFF) < 80 && (p and 0xFF) < 80) ink++
        }
        assertTrue("ink pixels of the mark: $ink", ink > 20)
    }

    @Test
    fun dragSnapsToTheNearerEdge_flingDecides_hideAndUndo() {
        onMain { host.updateVisibility(focusedInput()) }
        waitUntil("Ready") { snap().visual == BubbleVisual.Ready }
        val d = ctx.resources.displayMetrics.density
        var s = snap(); log("start", s)
        assertTrue(s.rightEdge)

        // Slow drag to 30% of the width: nearer edge = left.
        val start = centre(s)
        swipe(start, Pair(s.screenW * 0.3f, start.second + 200f), steps = 20, durationMs = 600, holdAtEndMs = 200)
        waitUntil("snapped left") { snap().let { !it.rightEdge && it.visual == BubbleVisual.Ready } }
        s = snap(); log("snap-left", s)
        val expectedTop = (start.second + 200f - s.diameterPx / 2f).roundToInt()
        assertTrue("y ${s.circleTop} vs $expectedTop", abs(s.circleTop!! - expectedTop) <= (2 * d).roundToInt())
        val key = BubblePlacement.displayKey(Display.DEFAULT_DISPLAY, s.screenW, s.screenH, d)
        val stored = settings.settings.value.bubblePositions[key]
        assertNotNull("stored for $key", stored)
        assertFalse(stored!!.rightEdge)
        assertEquals(s.yFraction!!, stored.yFraction, 0.01f)

        // Slow drag to 70%: right.
        swipe(centre(s), Pair(s.screenW * 0.7f, centre(s).second), steps = 20, durationMs = 600, holdAtEndMs = 200)
        waitUntil("snapped right") { snap().let { it.rightEdge && it.visual == BubbleVisual.Ready } }
        s = snap(); log("snap-right", s)

        // Fast flick toward the left that ends right of the centre: the fling (> 1000 dp/s) wins.
        val c = centre(s)
        swipe(c, Pair(s.screenW * 0.6f, c.second), steps = 4, durationMs = 60, holdAtEndMs = 0)
        waitUntil("flung left") { snap().let { !it.rightEdge && it.visual == BubbleVisual.Ready } }
        s = snap(); log("fling-left", s)

        // Drag onto the hide target (bottom centre): hidden for an hour, snackbar with Undo.
        val before = System.currentTimeMillis()
        val targetY = s.screenH - ((56 + 28 + 10 + 32) * d)
        swipe(centre(s), Pair(s.screenW / 2f, targetY), steps = 25, durationMs = 900, holdAtEndMs = 400)
        waitUntil("hidden") { snap().visual == BubbleVisual.Hidden }
        s = snap(); log("hidden", s)
        val until = settings.settings.value.hiddenUntilEpochMs
        assertNotNull(until)
        assertTrue(until!! - before in 3_590_000L..3_610_000L)
        assertTrue(s.snackbarShown)
        assertTrue("no input was sent to the orchestrator by dragging", inputs.isEmpty())

        // Undo through the accessibility tree (TalkBack's path): the bubble comes back, the setting is cleared.
        val undo = ctx.getString(R.string.bubble_action_undo)
        val node = checkNotNull(service.windows.mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByText(undo) }.firstOrNull()) {
            "Undo not found in the overlay windows"
        }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil("unhidden") { snap().visual == BubbleVisual.Ready }
        waitUntil("setting cleared") { settings.settings.value.hiddenUntilEpochMs == null }
        log("undo", snap())
    }

    @Test
    fun tapsReachTheOrchestrator_listeningShowsTheCancelCircle() {
        onMain { host.updateVisibility(focusedInput()) }
        waitUntil("Ready") { snap().visual == BubbleVisual.Ready }
        tap(centre(snap()))
        waitUntil("Tap sent") { inputs == listOf(UserInput.Tap) }
        dictation.value = DictationBubble.Listening(startedAtMs = System.currentTimeMillis(), hold = false, warn = false)
        waitUntil("cancel shown") { snap().cancelShown }
        val s = snap()
        Shots.save("overlay-listening", checkNotNull(ua.takeScreenshot()))
        // The cancel circle: 40 dp, its centre 50 dp - 20 dp above the pill top, 12 dp + 20 dp from the docked edge.
        val d = ctx.resources.displayMetrics.density
        val cancel = Pair(s.screenW - (32 * d), s.circleTop!! - 30 * d)
        tap(cancel)
        waitUntil("Cancel sent") { inputs.lastOrNull() == UserInput.Cancel }
        // Hold to talk on Ready: press >= 250 ms, release.
        dictation.value = DictationBubble.Idle
        waitUntil("Ready again") { snap().visual == BubbleVisual.Ready && !snap().cancelShown }
        val c = centre(snap())
        swipe(c, c, steps = 1, durationMs = 0, holdAtEndMs = 450)
        waitUntil("hold start/release") { inputs.takeLast(2) == listOf(UserInput.HoldStart, UserInput.HoldRelease) }
        Log.i(TAG, "inputs=$inputs")
    }

    @Test
    fun survivesSizeAndRotationChangesWithTheSameEdgeAndProportionalY() {
        onMain { host.updateVisibility(focusedInput()) }
        waitUntil("Ready") { snap().visual == BubbleVisual.Ready }
        val s0 = snap(); log("inner-0", s0)
        assertNull("no stored position yet", settings.settings.value.bubblePositions["inner"])

        fun check(step: String, expectRight: Boolean, frac: Float) {
            val s = snap(); log(step, s)
            assertEquals("$step edge", expectRight, s.rightEdge)
            assertEquals("$step fraction", frac, s.yFraction!!, 0.02f)
            assertEquals("$step visual", BubbleVisual.Ready, s.visual)
        }

        // Cover size and back (fold / unfold metrics, TOOLCHAIN 4.5).
        sh("wm size 1080x2520")
        waitUntil("cover size") { snap().screenW == 1080 }
        check("cover", true, s0.yFraction!!)
        sh("wm size 1968x2184")
        waitUntil("inner size") { snap().screenW == 1968 }
        check("inner", true, s0.yFraction)

        // Rotation 0-3 (a 180° flip arrives only through DisplayListener.onDisplayChanged).
        sh("settings put system accelerometer_rotation 0")
        for (r in listOf(1, 2, 3, 0)) {
            sh("settings put system user_rotation $r")
            waitUntil("rotation $r") { snap().rotation == r }
            SystemClock.sleep(300)
            check("rot$r", true, s0.yFraction)
        }

        // Left edge, with a stored position on the inner display only: the cover display reprojects it.
        val c = centre(snap())
        swipe(c, Pair(snap().screenW * 0.2f, c.second), steps = 20, durationMs = 600, holdAtEndMs = 200)
        waitUntil("left") { snap().let { !it.rightEdge && it.visual == BubbleVisual.Ready } }
        val left = snap(); log("left", left)
        sh("wm size 1080x2520")
        waitUntil("cover size") { snap().screenW == 1080 }
        check("cover-left", false, left.yFraction!!)
        for (r in listOf(1, 2, 3, 0)) {
            sh("settings put system user_rotation $r")
            waitUntil("rotation $r") { snap().rotation == r }
            SystemClock.sleep(300)
            check("cover-left-rot$r", false, left.yFraction)
        }
        sh("wm size reset")
        waitUntil("inner size") { snap().screenW == 1968 }
        check("inner-left", false, left.yFraction)
    }

    /**
     * Posture CLOSED / OPENED through the emulator's hinge sensor (TOOLCHAIN 4.5). The console command runs on the
     * host: tools/run_emulator_tests.sh passes `-e hinge host` and answers the WP03_HINGE requests in logcat.
     */
    @Test
    fun survivesPostureChanges() {
        assumeTrue("needs the host-side hinge driver (-e hinge host)", InstrumentationRegistry.getArguments().getString("hinge") == "host")
        onMain { host.updateVisibility(focusedInput()) }
        waitUntil("Ready") { snap().visual == BubbleVisual.Ready }
        val s0 = snap(); log("posture-open", s0)
        for ((angle, state) in listOf(0 to "CLOSED", 180 to "OPENED")) {
            Log.i(TAG, "WP03_HINGE $angle")
            waitUntil("device_state $state", 30_000) { committedState().contains(state) }
            SystemClock.sleep(500)
            val s = snap(); log("posture-$state", s)
            assertEquals(s0.rightEdge, s.rightEdge)
            assertEquals(s0.yFraction!!, s.yFraction!!, 0.02f)
            assertEquals(BubbleVisual.Ready, s.visual)
        }
    }

    private fun committedState(): String =
        sh("dumpsys device_state").lines().firstOrNull { it.contains("mCommittedState") }.orEmpty()

    // --- input injection (display coordinates)

    private fun centre(s: OverlayHost.Snapshot): Pair<Float, Float> {
        val d = ctx.resources.displayMetrics.density
        val x = if (s.rightEdge) s.screenW - 8 * d - s.diameterPx / 2f else 8 * d + s.diameterPx / 2f
        return Pair(x, s.circleTop!! + s.diameterPx / 2f)
    }

    private fun inject(action: Int, down: Long, x: Float, y: Float) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(ua.injectInputEvent(ev, true))
        ev.recycle()
    }

    private fun tap(p: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, p.first, p.second)
        SystemClock.sleep(60)
        inject(MotionEvent.ACTION_UP, down, p.first, p.second)
    }

    private fun swipe(from: Pair<Float, Float>, to: Pair<Float, Float>, steps: Int, durationMs: Long, holdAtEndMs: Long) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, from.first, from.second)
        for (i in 1..steps) {
            SystemClock.sleep(durationMs / steps)
            val t = i / steps.toFloat()
            inject(MotionEvent.ACTION_MOVE, down, from.first + (to.first - from.first) * t, from.second + (to.second - from.second) * t)
        }
        if (holdAtEndMs > 0) {
            // Still finger: the release velocity falls to zero.
            val end = SystemClock.uptimeMillis() + holdAtEndMs
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(50)
                inject(MotionEvent.ACTION_MOVE, down, to.first, to.second)
            }
        }
        inject(MotionEvent.ACTION_UP, down, to.first, to.second)
    }

    private companion object {
        const val TAG = "WP03"
        const val TARGET_PKG = "app.murmur.testtarget"
    }
}
