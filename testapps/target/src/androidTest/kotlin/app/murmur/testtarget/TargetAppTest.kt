package app.murmur.testtarget

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The receiving side must be trustworthy before any end-to-end test relies on it: every target opens with its field
 * focused, announces itself (`TGT open=<target>`) and logs the field's full text after each change (`TGT text=...`),
 * for typed text (`input text`, real key events through the IME) and for prefilled text.
 */
@RunWith(AndroidJUnit4::class)
class TargetAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** TGT lines logged after the latest `open=<target>`. */
    private fun linesSinceOpen(target: String): List<String>? {
        val lines = shell("logcat -d -v raw -s ${Tgt.TAG}:I").lines().map { it.trim() }
        val from = lines.indexOfLast { it == "open=$target" }
        return if (from < 0) null else lines.drop(from + 1)
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            probe()?.let { return it }
            SystemClock.sleep(100)
        }
        fail("timed out after $timeoutMs ms waiting for $what")
        error("unreachable")
    }

    /** A System UI "isn't responding" dialog (heavy host load) steals the focus: press Wait. */
    private fun dismissAnr() {
        UiDevice.getInstance(instrumentation).findObject(By.res("android", "aerr_wait"))?.click()
    }

    private fun start(target: String, extras: String = "") {
        dismissAnr()
        shell("logcat -c")
        val out = shell("am start -W --activity-clear-task -n ${Targets.amStartArgs(target)}$extras")
        assertTrue(out, out.contains("Status: ok"))
        waitFor("open=$target") { linesSinceOpen(target) }
        dismissAnr()
    }

    private fun resumed(): Activity = waitFor("resumed activity") {
        var a: Activity? = null
        instrumentation.runOnMainSync {
            a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        a
    }

    private fun typedTextIsLogged(target: String) {
        start(target)
        // The web targets focus their field 300 ms after load; key events before that would go nowhere.
        SystemClock.sleep(if (target.startsWith("web")) 1_500 else 500)
        shell("input text hello")
        val last = waitFor("text=hello from $target") { linesSinceOpen(target)?.lastOrNull { it == "text=hello" } }
        assertEquals("text=hello", last)
    }

    @Test fun plainLogsTypedText() = typedTextIsLogged(Tgt.PLAIN)
    @Test fun multilineLogsTypedText() = typedTextIsLogged(Tgt.MULTILINE)
    @Test fun passwordLogsTypedText() = typedTextIsLogged(Tgt.PASSWORD)
    @Test fun composeLogsTypedText() = typedTextIsLogged(Tgt.COMPOSE)
    @Test fun webInputLogsTypedText() = typedTextIsLogged(Tgt.WEB_INPUT)
    @Test fun webContentEditableLogsTypedText() = typedTextIsLogged(Tgt.WEB_CONTENTEDITABLE)
    @Test fun secureLogsTypedText() = typedTextIsLogged(Tgt.SECURE)

    @Test
    fun prefillAndCursorPutTheCaretWhereAsked() {
        start(Tgt.PLAIN, " --es ${Tgt.EXTRA_PREFILL} ab --ei ${Tgt.EXTRA_CURSOR} 1")
        SystemClock.sleep(500)
        shell("input text X")
        waitFor("text=aXb") { linesSinceOpen(Tgt.PLAIN)?.lastOrNull { it == "text=aXb" } }
    }

    @Test
    fun passwordFieldIsAPasswordAndMultilineIsMultiline() {
        start(Tgt.PASSWORD)
        val pw = waitFor("password activity") { resumed() as? PasswordActivity }.window.decorView.findViewWithTag<android.widget.EditText>("target-field")
        assertTrue("password variation", pw.inputType and android.text.InputType.TYPE_MASK_VARIATION ==
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        start(Tgt.MULTILINE)
        val ml = waitFor("multiline activity") { resumed() as? MultilineActivity }
            .window.decorView.findViewWithTag<android.widget.EditText>("target-field")
        assertTrue("multi-line flag", ml.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
    }

    @Test
    fun secureWindowHasFlagSecure() {
        start(Tgt.SECURE)
        val activity = waitFor("secure activity") { resumed() as? SecureActivity }
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun everyTargetHasAFocusedField() {
        for (target in listOf(Tgt.PLAIN, Tgt.MULTILINE, Tgt.PASSWORD, Tgt.SECURE, Tgt.COMPOSE)) {
            start(target)
            val activity = waitFor("$target activity") { resumed().takeIf { it.javaClass == Targets.ALL[target] } }
            waitFor("focused view in $target") {
                var focused: android.view.View? = null
                instrumentation.runOnMainSync { focused = activity.currentFocus }
                focused
            }
        }
    }
}
