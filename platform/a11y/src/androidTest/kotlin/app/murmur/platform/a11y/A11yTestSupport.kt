package app.murmur.platform.a11y

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.context.A11ySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail

/**
 * Shared steps of the instrumented tests. The UiAutomation is always taken with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES:
 * the default flags would suppress (unbind) the service under test.
 */
object A11yTestSupport {
    const val TAG = "A11yTest"
    const val SERVICE_SETTING = "enabled_accessibility_services"
    const val OTHER_APP = "app.murmur.testtarget"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val host: TestHostApp get() = context.applicationContext as TestHostApp
    val uiAutomation: UiAutomation by lazy { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }

    val serviceComponent get() = "${context.packageName}/${MurmurAccessibilityService::class.java.name}"

    fun shell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** The adb recipe of the brief, from inside the test (README: enable with settings put secure ...). */
    fun enableService(): MurmurAccessibilityService {
        A11yBridge.service.value?.let { return it }
        shell("settings put secure show_ime_with_hard_keyboard 1")
        // A fresh instrumentation run killed the previous process: the system lists the service as crashed and binds it
        // again only after the setting changes, so turn it off before turning it on.
        shell("settings delete secure $SERVICE_SETTING")
        shell("settings put secure $SERVICE_SETTING $serviceComponent")
        shell("settings put secure accessibility_enabled 1")
        return waitFor("service connected", 10_000) { A11yBridge.service.value }
    }

    fun service(): MurmurAccessibilityService = A11yBridge.service.value ?: enableService()

    fun <T : Any> waitFor(what: String, timeoutMs: Long, pollMs: Long = 50, probe: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.uptimeMillis() > end) fail("timed out after $timeoutMs ms waiting for $what")
            SystemClock.sleep(pollMs)
        }
    }

    /** Application windows that existed before the last [launch]: a snapshot of them is stale (same title, old field). */
    @Volatile private var staleWindows: Set<Int> = emptySet()

    private fun appWindowIds(): Set<Int> = A11yBridge.service.value?.let { s ->
        runBlocking { kotlinx.coroutines.withContext(s.ioDispatcher) { s.nodes.windows() } }.filter { it.isApplication }.map { it.id }.toSet()
    } ?: emptySet()

    fun launch(activity: Class<out Activity>, extras: Intent.() -> Unit = {}) {
        staleWindows = appWindowIds()
        context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).apply(extras))
    }

    fun launchOtherApp() {
        staleWindows = appWindowIds()
        // -S: a fresh window each time; a TargetActivity left from an earlier run would come back with a window id
        // that is already in staleWindows.
        val out = shell("am start -S -W -n $OTHER_APP/.TargetActivity")
        if (!out.contains("Status: ok")) fail("$OTHER_APP is not installed (platform/a11y/tools/run_emulator_tests.sh installs it): $out")
    }

    /** Snapshot once the target window with [title] is in front with a focused field. */
    fun snapshotOf(title: String, timeoutMs: Long = 10_000): A11ySnapshot = waitFor("field in '$title'", timeoutMs, pollMs = 100) {
        runBlocking { service().targetReader.snapshot() }?.takeIf { it.windowTitle == title && it.field != null && it.windowId !in staleWindows }
    }

    fun snapshotOfPackage(pkg: String, timeoutMs: Long = 10_000): A11ySnapshot = waitFor("field in $pkg", timeoutMs, pollMs = 100) {
        runBlocking { service().targetReader.snapshot() }?.takeIf { it.pkg == pkg && it.field != null && it.windowId !in staleWindows }
    }

    /** Marks the log: [targetTexts] only returns lines logged after the latest mark (the log itself is kept as evidence). */
    fun clearTargetLog() { Log.i(Tgt.TAG, "mark=${SystemClock.uptimeMillis()}") }

    /** The texts the targets logged ("TGT text=...") since the last mark, oldest first. */
    fun targetTexts(): List<String> {
        val lines = shell("logcat -d -v raw -s ${Tgt.TAG}:I").lines()
        val from = lines.indexOfLast { it.startsWith("mark=") } + 1
        return lines.drop(from).filter { it.startsWith("text=") }.map { it.removePrefix("text=") }
    }

    fun awaitTargetText(expected: String, timeoutMs: Long = 5_000): String =
        waitFor("TGT text containing '$expected'", timeoutMs, pollMs = 100) { targetTexts().lastOrNull { it.contains(expected) } }

    fun note(msg: String) = Log.i(TAG, msg)
}
