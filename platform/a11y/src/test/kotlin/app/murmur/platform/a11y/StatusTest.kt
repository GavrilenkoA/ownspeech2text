package app.murmur.platform.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    private val pkg = "app.murmur"
    private val cls = "app.murmur.platform.a11y.MurmurAccessibilityService"

    @Test
    fun enabledServicesAcceptsFullAndShortFormsAmongOthers() {
        assertTrue(EnabledServices.contains("$pkg/$cls", pkg, cls))
        assertTrue(EnabledServices.contains("com.google.android.marvin.talkback/.TalkBackService:$pkg/.platform.a11y.MurmurAccessibilityService", pkg, cls))
        assertTrue(EnabledServices.contains("x/y: $pkg/$cls ", pkg, cls))
        assertFalse(EnabledServices.contains("", pkg, cls))
        assertFalse(EnabledServices.contains(null, pkg, cls))
        assertFalse(EnabledServices.contains("app.murmur.platform.a11y.test/$cls", pkg, cls))
        assertFalse(EnabledServices.contains("$pkg/app.murmur.Other", pkg, cls))
        assertFalse(EnabledServices.contains("garbage", pkg, cls))
    }

    @Test
    fun stateDerivesRestrictedLikelyAndEnabledButDead() {
        val p = RestrictedSettingsProbe
        val blocked = A11yState(enabledInSettings = false, connectedNow = false, everConnected = false,
            packageSource = p.PACKAGE_SOURCE_LOCAL_FILE, returnedFromA11ySettings = true)
        assertTrue(blocked.restrictedLikely)
        assertFalse(blocked.enabledButDead)
        assertFalse(blocked.copy(packageSource = p.PACKAGE_SOURCE_OTHER).restrictedLikely) // adb install
        assertFalse(blocked.copy(packageSource = p.PACKAGE_SOURCE_UNSPECIFIED).restrictedLikely)
        assertFalse(blocked.copy(returnedFromA11ySettings = false).restrictedLikely)
        assertFalse(blocked.copy(connectedNow = true).restrictedLikely)
        // A sideloaded install that connected once and is off again after settings (crash, switched off by hand).
        assertFalse(blocked.copy(everConnected = true).restrictedLikely)
        assertTrue(blocked.copy(enabledInSettings = true).enabledButDead)
        assertFalse(blocked.copy(enabledInSettings = true, everConnected = true).enabledButDead)
    }
}
