package app.murmur.e2e

import android.app.UiAutomation
import android.os.Bundle
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import app.murmur.AppContainer
import app.murmur.MurmurApp
import org.junit.Assert.fail

/**
 * Shared plumbing of the end-to-end tests (ARCHITECTURE 16). The tests run inside :main (the instrumentation targets
 * app.murmur), so they read the orchestrator, history and engines straight from the [AppContainer], and they drive the
 * device as a user would: shell commands, UiAutomator taps on the bubble, the target app's `TGT` log.
 *
 * The UiAutomation is always taken with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES: the default flags unbind every other
 * accessibility service, the bubble included.
 *
 * Run them through `tools/e2e/run_emulator_e2e.sh` (installs the target, prepares the debug flags, runs the host bridge,
 * collects logs). Instrumentation arguments: `e2e.bridge=1` (the host bridge runs).
 *
 * Privacy (D9) applies to tests too: [note] logs codes, states and numbers, never transcripts or inserted text.
 */
object E2e {
    const val TAG = "E2E"
    const val PKG = "app.murmur"
    /** Shared with tools/e2e (host bridge acks, pushed fixtures); writable by the shell user, read through [shell]. */
    const val DEVICE_DIR = "/data/local/tmp/murmur-e2e"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val args: Bundle get() = InstrumentationRegistry.getArguments()

    val uiAutomation: UiAutomation by lazy {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    val device: UiDevice by lazy { uiAutomation; UiDevice.getInstance(instrumentation) }

    val app: MurmurApp get() = context.applicationContext as MurmurApp
    val container: AppContainer get() = checkNotNull(app.container) { "the instrumentation must run in :main" }

    /**
     * Runs [cmd] as the shell user (settings, wm, am, logcat, pidof, dumpsys) and returns stdout. No shell syntax: the
     * command is split on spaces (no quotes, pipes or `$(...)`).
     */
    fun shell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    fun <T : Any> waitFor(what: String, timeoutMs: Long, pollMs: Long = 50, probe: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.uptimeMillis() > end) fail("timed out after $timeoutMs ms waiting for $what")
            SystemClock.sleep(pollMs)
        }
    }

    fun waitUntil(what: String, timeoutMs: Long, pollMs: Long = 50, condition: () -> Boolean) {
        waitFor(what, timeoutMs, pollMs) { if (condition()) Unit else null }
    }

    /**
     * Closes an "isn't responding" dialog (it takes the focus from the target; System UI ANRs happen on this shared,
     * heavily loaded server) by pressing Wait. Returns true when one was closed.
     */
    fun dismissSystemDialogs(): Boolean {
        val wait = device.findObject(androidx.test.uiautomator.By.res("android", "aerr_wait")) ?: return false
        note("closing an ANR dialog")
        wait.click()
        device.waitForIdle(2_000)
        return true
    }

    fun note(msg: String) {
        Log.i(TAG, msg)
    }

    /** A measured number or code for the run report: tools/e2e collects `E2E report <key>=<value>` lines from logcat. */
    fun report(key: String, value: Any) = note("report $key=$value")
}
