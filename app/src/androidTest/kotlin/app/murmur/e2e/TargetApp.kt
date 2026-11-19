package app.murmur.e2e

import android.os.SystemClock
import android.util.Log
import org.junit.Assert.assertTrue

/**
 * The "other app" (`:testapps:target`, installed by tools/e2e). Its targets log on tag `TGT`: `open=<target>` when they
 * come to the front and `text=<full field text>` after every change. Names and components mirror
 * testapps/target/.../Tgt.kt (a separate APK, so they are repeated here).
 */
object TargetApp {
    const val PKG = "app.murmur.testtarget"
    private const val TAG = "TGT"

    enum class Target(val id: String, val component: String) {
        PLAIN("plain", "$PKG/.TargetActivity"),
        MULTILINE("multiline", "$PKG/.MultilineActivity"),
        PASSWORD("password", "$PKG/.PasswordActivity"),
        COMPOSE("compose", "$PKG/.ComposeActivity"),
        WEB_INPUT("web-input", "$PKG/.WebActivity --es kind input"),
        WEB_CONTENTEDITABLE("web-contenteditable", "$PKG/.WebActivity --es kind contenteditable"),
        SECURE("secure", "$PKG/.SecureActivity"),
    }

    fun installed(): Boolean = E2e.shell("pm path $PKG").startsWith("package:")

    /** Starts [target] in a fresh task, waits until it announced itself and its field is focused. */
    fun open(target: Target = Target.PLAIN, extras: String = "") {
        assertTrue("$PKG is not installed (tools/e2e/run_emulator_e2e.sh installs it)", installed())
        E2e.dismissSystemDialogs()
        mark()
        val out = E2e.shell("am start -W --activity-clear-task -n ${target.component}$extras")
        assertTrue(out, out.contains("Status: ok"))
        E2e.waitFor("TGT open=${target.id}", 10_000, pollMs = 100) { linesSinceMark().takeIf { "open=${target.id}" in it } }
        // Web targets focus their field by script once the page loaded (`TGT focus=<target>`, seconds on a loaded
        // emulator); every target needs the IME up for the bubble.
        if (target.id.startsWith("web")) {
            E2e.waitFor("TGT focus=${target.id}", 20_000, pollMs = 100) { linesSinceMark().takeIf { "focus=${target.id}" in it } }
        }
        SystemClock.sleep(700)
        if (E2e.dismissSystemDialogs()) SystemClock.sleep(500)
        E2e.waitUntil("the target window has the focus (focused: ${focusedPackage()})", 5_000, pollMs = 100) { focusedPackage() == PKG }
    }

    /** Package of the focused window as accessibility sees it (an ANR dialog or the shade shows up here). */
    fun focusedPackage(): String? = E2e.uiAutomation.windows.firstOrNull { it.isFocused }?.root?.packageName?.toString()

    /** Marks the log: [texts] returns only what was logged after the latest mark (the log itself is kept as evidence). */
    fun mark() {
        Log.i(TAG, "mark=${SystemClock.uptimeMillis()}")
    }

    private fun linesSinceMark(): List<String> {
        val lines = E2e.shell("logcat -d -v raw -s $TAG:I").lines().map { it.trimEnd() }
        val from = lines.indexOfLast { it.startsWith("mark=") } + 1
        return lines.drop(from)
    }

    /** The field texts logged since the last mark, oldest first. */
    fun texts(): List<String> = linesSinceMark().filter { it.startsWith("text=") }.map { it.removePrefix("text=") }

    fun lastText(): String? = texts().lastOrNull()

    /** Waits until the field's latest text satisfies [ok]. */
    fun awaitText(what: String, timeoutMs: Long = 10_000, ok: (String) -> Boolean): String =
        E2e.waitFor("target field text: $what", timeoutMs, pollMs = 100) { lastText()?.takeIf(ok) }
}
