package app.murmur.platform.a11y

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.InsertResult
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.platform.a11y.A11yTestSupport.SERVICE_SETTING
import app.murmur.platform.a11y.A11yTestSupport.context
import app.murmur.platform.a11y.A11yTestSupport.enableService
import app.murmur.platform.a11y.A11yTestSupport.host
import app.murmur.platform.a11y.A11yTestSupport.launch
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.shell
import app.murmur.platform.a11y.A11yTestSupport.snapshotOf
import app.murmur.platform.a11y.A11yTestSupport.waitFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disabling the service reaches the host (onServiceState(false)) within 2 s and the bridged ports fail cleanly. */
@RunWith(AndroidJUnit4::class)
class ServiceStateTest {
    @After fun reEnable() { enableService() }

    @Test
    fun disablingTheServiceCallsOnServiceStateFalseWithin2s() {
        enableService()
        launch(PlainTargetActivity::class.java)
        val snap = snapshotOf("Plain target")
        val status = A11yStatus.get(context).read()
        note("status before enabled=${status.enabledInSettings} connected=${status.connectedNow} ever=${status.everConnected} source=${status.packageSource}")
        assertTrue(status.enabledInSettings && status.connectedNow && status.everConnected)

        val mark = host.serviceStates.size
        val t0 = SystemClock.uptimeMillis()
        // Same effect as `settings put secure enabled_accessibility_services ''` (no shell quoting inside the test).
        shell("settings delete secure $SERVICE_SETTING")
        val (at, connected) = waitFor("onServiceState(false)", 5_000, pollMs = 10) { host.serviceStates.drop(mark).firstOrNull() }
        note("service_state connected=$connected latency_ms=${at - t0}")
        assertFalse(connected)
        assertTrue("latency ${at - t0} ms", at - t0 <= 2_000)
        assertNull(A11yBridge.service.value)

        val after = A11yStatus.get(context).read()
        assertFalse(after.enabledInSettings || after.connectedNow)
        assertTrue(after.everConnected)
        assertEquals(Outcome.Err(MurmurError.AccessibilityOff), runBlocking { BridgedTargetReader().screenshot(snap) })
        assertEquals(InsertResult.Failed(MurmurError.AccessibilityOff), runBlocking { BridgedInserter().insert(snap, "x") })
        assertNull(runBlocking { BridgedTargetReader().snapshot() })
    }
}
