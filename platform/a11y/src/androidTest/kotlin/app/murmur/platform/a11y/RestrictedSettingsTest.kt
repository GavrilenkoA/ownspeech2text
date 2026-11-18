package app.murmur.platform.a11y

import android.app.UiAutomation
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import app.murmur.platform.a11y.A11yTestSupport.SERVICE_SETTING
import app.murmur.platform.a11y.A11yTestSupport.context
import app.murmur.platform.a11y.A11yTestSupport.instrumentation
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.shell
import app.murmur.platform.a11y.A11yTestSupport.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * ADR-054 on the emulator: Murmur's screen sends the user to the accessibility settings, the user tries the toggle,
 * comes back, and A11yStatus says whether Android blocked it. The expectation follows from this install's
 * packageSource: a file install (3 / 4) -> restrictedLikely, an adb install -> not. platform/a11y/tools/run_emulator_tests.sh
 * runs it once per install kind. The screen text shown after the toggle is logged and saved as a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class RestrictedSettingsTest {
    private val label = context.packageManager.getServiceInfo(
        android.content.ComponentName(context, MurmurAccessibilityService::class.java), 0,
    ).loadLabel(context.packageManager).toString()

    @Test
    fun returnFromSettingsWithTheServiceOffReportsRestrictedOnlyForFileInstalls() {
        shell("settings delete secure $SERVICE_SETTING")
        waitFor("service off", 5_000) { if (A11yBridge.service.value == null) Unit else null }
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val device = UiDevice.getInstance(instrumentation)
        val status = A11yStatus.get(context)
        val source = status.packageSource()
        val expectBlocked = source in RestrictedSettingsProbe.SIDELOAD_SOURCES

        context.startActivity(Intent(context, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val host = waitFor("host activity", 5_000) { HostActivityTracker.resumed }
        instrumentation.runOnMainSync { host.openSettings() }

        // Accessibility settings -> our service row.
        val list = waitFor("accessibility settings list", 10_000) { device.findObject(By.scrollable(true)) }
        val row = device.findObject(By.text(label))
            ?: list.scrollUntil(Direction.DOWN, Until.findObject(By.text(label)))
            ?: error("service row '$label' not found")
        row.click()
        device.waitForIdle()
        Thread.sleep(1_500)
        // A blocked service shows "App was denied access" on the row itself (API 36); an allowed one opens its page,
        // where the "Use <label>" switch asks for full control.
        device.findObject(By.text(Pattern.compile("Use .*")))?.let { it.click(); device.waitForIdle(); Thread.sleep(1_500) }
        val shown = device.findObjects(By.text(Pattern.compile(".+")))
            .mapNotNull { it.text }.filter { it.isNotBlank() }.distinct()
        shell("screencap -p /sdcard/Download/a11y_toggle_source$source.png")
        note("restricted toggle source=$source dialog_text=$shown")

        // Decline whatever is shown (never grant), and come back to Murmur's screen.
        for (i in 0 until 6) {
            if (HostActivityTracker.resumed != null) break
            device.pressBack(); device.waitForIdle()
        }
        waitFor("back on the host screen", 10_000) { HostActivityTracker.resumed }
        val state = status.read()
        note("restricted state source=${state.packageSource} returned=${state.returnedFromA11ySettings} connected=${state.connectedNow} enabled=${state.enabledInSettings} restrictedLikely=${state.restrictedLikely}")
        assertFalse(state.connectedNow)
        assertEquals(true, state.returnedFromA11ySettings)
        assertEquals(expectBlocked, state.restrictedLikely)
    }
}
