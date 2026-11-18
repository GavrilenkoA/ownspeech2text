package app.murmur.platform.a11y

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.UserInput
import app.murmur.platform.a11y.A11yTestSupport.context
import app.murmur.platform.a11y.A11yTestSupport.enableService
import app.murmur.platform.a11y.A11yTestSupport.host
import app.murmur.platform.a11y.A11yTestSupport.launch
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.service
import app.murmur.platform.a11y.A11yTestSupport.shell
import app.murmur.platform.a11y.A11yTestSupport.snapshotOf
import app.murmur.platform.a11y.A11yTestSupport.waitFor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** snapshotAfterShade (ADR-056) and the notification "Start dictation" receiver. */
@RunWith(AndroidJUnit4::class)
class ShadeTest {
    @Before fun setUp() {
        enableService()
        launch(PlainTargetActivity::class.java) { putExtra("prefill", "Shade") }
        snapshotOf("Plain target")
    }

    @After fun collapse() { shell("cmd statusbar collapse") }

    /** The shade really took the focus away from the app (otherwise the test would prove nothing). */
    private fun expandAndAwaitShade() {
        shell("cmd statusbar expand-notifications")
        waitFor("shade has focus", 5_000) {
            runBlocking { withContext(service().ioDispatcher) { service().nodes.windows() } }
                .takeIf { WindowChoice.afterShade(it) == null }
        }
    }

    @Test
    fun returnsTheAppAfterTheShadeCollapses() = runBlocking {
        expandAndAwaitShade()
        val t0 = SystemClock.uptimeMillis()
        val pending = async { service().targetReader.snapshotAfterShade(1_000) }
        delay(300)
        val collapseAt = SystemClock.uptimeMillis()
        shell("cmd statusbar collapse")
        val s = pending.await()
        val done = SystemClock.uptimeMillis()
        note("after_shade pkg=${s?.pkg} waited_ms=${done - t0} collapse_at_ms=${collapseAt - t0}")
        assertNotNull(s)
        assertEquals(context.packageName, s!!.pkg)
        assertNotEquals(WindowChoice.SYSTEM_UI, s.pkg)
        assertEquals("Plain target", s.windowTitle)
        assertTrue("returned only after the collapse", done >= collapseAt)
    }

    @Test
    fun timesOutWithNullWhileTheShadeStaysDown() = runBlocking {
        expandAndAwaitShade()
        val t0 = SystemClock.uptimeMillis()
        val s = service().targetReader.snapshotAfterShade(500)
        val waited = SystemClock.uptimeMillis() - t0
        note("after_shade_timeout result_null=${s == null} waited_ms=$waited")
        assertEquals(null, s)
        assertTrue(waited in 450..1_500)
    }

    @Test
    fun notificationStartClosesTheShadeAndForwardsTheInput() = runBlocking {
        expandAndAwaitShade()
        host.inputs.clear()
        context.sendBroadcast(Intent(A11yIntents.ACTION_START_DICTATION).setClassName(context, A11yIntents.RECEIVER_CLASS))
        val s = service().targetReader.snapshotAfterShade(1_000)
        note("notification_start inputs=${host.inputs.map { it.javaClass.simpleName }} pkg=${s?.pkg}")
        waitFor("NotificationStart forwarded", 2_000) { host.inputs.firstOrNull() }
        assertEquals(listOf<UserInput>(UserInput.NotificationStart), host.inputs.toList())
        assertEquals(context.packageName, s?.pkg)
    }
}
