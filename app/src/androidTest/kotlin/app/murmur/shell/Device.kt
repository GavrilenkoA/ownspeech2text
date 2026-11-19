package app.murmur.shell

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Shell commands, waits and screenshots for the shell / onboarding emulator tests. */
object Device {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val pkg: String get() = context.packageName
    const val SERVICE = "app.murmur/app.murmur.platform.a11y.MurmurAccessibilityService"

    /**
     * UiAutomation that leaves other accessibility services running: the default one suppresses them while it is
     * connected, so Murmur's service would never bind during a test.
     */
    val automation: android.app.UiAutomation
        get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** UiDevice on the same non-suppressing UiAutomation. */
    val ui: androidx.test.uiautomator.UiDevice
        get() {
            androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags =
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            return androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        }

    /** Runs [cmd] as the shell user (pm grant, settings put, wm size, dumpsys) and returns its output. */
    fun sh(cmd: String): String =
        automation.executeShellCommand(cmd).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

    private fun mains(stage: androidx.test.runner.lifecycle.Stage) =
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
            .filterIsInstance<app.murmur.MainActivity>()

    /** The resumed MainActivity (waits up to 5 s). */
    fun resumedMain(): app.murmur.MainActivity {
        var a: app.murmur.MainActivity? = null
        waitFor(5_000, "a resumed MainActivity") {
            instrumentation.runOnMainSync { a = mains(androidx.test.runner.lifecycle.Stage.RESUMED).singleOrNull() }
            a != null
        }
        return a!!
    }

    /** Finishes every MainActivity this test started outside ActivityScenario. */
    fun finishAll() {
        instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.Stage.entries.filter { it != androidx.test.runner.lifecycle.Stage.DESTROYED }
                .flatMap(::mains).forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    /** The window is medium width or wider (inner display layout), from the app's own configuration. */
    fun wide(): Boolean = context.resources.configuration.screenWidthDp >= 600

    fun waitFor(timeoutMs: Long = 10_000, what: String, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out after $timeoutMs ms waiting for $what")
    }

    fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) =
        waitUntil(timeoutMs) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    fun ComposeTestRule.hasTag(tag: String): Boolean =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Saves the whole screen as `<name>--cover.png` / `--inner.png` (by the current width) in the app's external files
     * dir (`/sdcard/Android/data/app.murmur/files/shots/`), pulled to .cache/spikes/wp-15/ by the runner script.
     */
    fun shot(name: String) {
        instrumentation.waitForIdleSync()
        // A cold start can take seconds to draw its first real frame on the emulator: wait until the screen below the
        // status bar shows more than the window background.
        var bmp: Bitmap? = null
        val end = SystemClock.uptimeMillis() + 15_000
        do {
            SystemClock.sleep(300)
            bmp = automation.takeScreenshot()
        } while (bmp != null && plain(bmp) && SystemClock.uptimeMillis() < end)
        val b = bmp ?: return
        val dir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name--${if (wide()) "inner" else "cover"}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Fewer than 3 colours on a coarse grid of the middle 80 % of the screen: nothing drawn yet. */
    private fun plain(shot: Bitmap): Boolean {
        val b = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        val colours = HashSet<Int>()
        for (y in (b.height / 10) until (b.height * 9 / 10) step 24) for (x in 0 until b.width step 24) colours += b.getPixel(x, y)
        return colours.size < 3
    }
}
