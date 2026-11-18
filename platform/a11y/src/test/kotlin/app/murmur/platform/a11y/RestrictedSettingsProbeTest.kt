package app.murmur.platform.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsProbeTest {
    @Test
    fun sideloadedAndStillOffAfterReturningFromSettingsIsLikelyBlocked() {
        val p = RestrictedSettingsProbe
        assertTrue(p.likelyBlocked(returnedFromA11ySettings = true, connectedNow = false, packageSource = p.PACKAGE_SOURCE_LOCAL_FILE))
        assertTrue(p.likelyBlocked(true, false, p.PACKAGE_SOURCE_DOWNLOADED_FILE))
        assertFalse(p.likelyBlocked(true, false, p.PACKAGE_SOURCE_STORE))
        assertFalse(p.likelyBlocked(true, connectedNow = true, packageSource = p.PACKAGE_SOURCE_LOCAL_FILE))
        assertFalse(p.likelyBlocked(false, false, p.PACKAGE_SOURCE_LOCAL_FILE))
        assertTrue(p.enabledButDead(enabledInSettings = true, everConnected = false))
    }
}
