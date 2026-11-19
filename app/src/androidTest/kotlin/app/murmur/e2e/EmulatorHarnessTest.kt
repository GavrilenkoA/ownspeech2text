package app.murmur.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.InferProcess
import app.murmur.infer.asr.AsrService
import app.murmur.infer.llm.LlmService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the harness itself on the emulator, independent of the dictation pipeline: every platform event the
 * end-to-end tests use can be produced and observed here (fold/unfold, rotation, incoming call, notification shade,
 * SIGKILL of :asr / :llm, service on/off), the host bridge answers, and the target app's log reaches this test.
 * An event this suite cannot produce is reported as UNVERIFIABLE-HERE and moved to DC5/DC10.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorHarnessTest {
    @After fun restore() = DeviceEvents.restore()

    @Test
    fun targetFieldsReportWhatIsTypedIntoThem() {
        for (t in listOf(TargetApp.Target.PLAIN, TargetApp.Target.COMPOSE, TargetApp.Target.WEB_CONTENTEDITABLE)) {
            TargetApp.open(t)
            E2e.shell("input text e2e")
            assertEquals(t.id, "e2e", TargetApp.awaitText("e2e in ${t.id}") { it == "e2e" })
        }
    }

    @Test
    fun accessibilityServiceFollowsTheSecureSetting() {
        Accessibility.enable()
        assertTrue(Accessibility.enabledInSettings())
        Accessibility.disable()
        Accessibility.enable()
        assertTrue(Accessibility.connected)
    }

    @Test
    fun foldAndUnfoldChangeDisplaySizeAndPosture() {
        DeviceEvents.requireHinge()
        TargetApp.open()
        DeviceEvents.fold()
        assertEquals(DeviceEvents.COVER_SIZE, DeviceEvents.overrideSize() ?: DeviceEvents.physicalSize())
        DeviceEvents.unfold()
        assertEquals(null, DeviceEvents.overrideSize())
    }

    @Test
    fun userRotationRotatesTheDisplay() {
        TargetApp.open()
        DeviceEvents.rotate(1)
        DeviceEvents.rotate(0)
    }

    @Test
    fun emulatorModemRingsAndHangsUp() {
        TargetApp.open()
        DeviceEvents.incomingCall()
        DeviceEvents.endCall()
    }

    @Test
    fun notificationShadeOpensAndCloses() {
        TargetApp.open()
        DeviceEvents.expandShade()
        DeviceEvents.collapseShade()
    }

    @Test fun asrProcessDiesOnSigkillFromTheApp() = killAndObserve(InferProcess.ASR, AsrService::class.java, Processes.ASR)
    @Test fun llmProcessDiesOnSigkillFromTheApp() = killAndObserve(InferProcess.LLM, LlmService::class.java, Processes.LLM)

    /** Binds the service the way the binder clients do, kills its process, and checks the client sees the death. */
    private fun killAndObserve(process: InferProcess, service: Class<*>, name: String) {
        val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) = connected.countDown()
            override fun onServiceDisconnected(n: ComponentName?) = died.countDown()
            override fun onBindingDied(n: ComponentName?) = died.countDown()
        }
        val ctx = E2e.context
        assertTrue(ctx.bindService(Intent(ctx, service), conn, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("$name bound", connected.await(10, TimeUnit.SECONDS))
            val pid = Processes.kill9(name)
            assertTrue("$process death seen by the client", died.await(5, TimeUnit.SECONDS))
            // BIND_AUTO_CREATE: the system restarts the service; the new process has a new pid (re-bind path, ARCH 4.4).
            val again = E2e.waitFor("$name restarted", 15_000, pollMs = 100) { Processes.pidOf(name) }
            assertNotEquals(pid, again)
            E2e.report("harness.kill.${process.name.lowercase()}", "pid $pid -> $again")
        } finally {
            ctx.unbindService(conn)
        }
        // The restarted process has no client any more; the real services exit on unbind / idle (D5), this skeleton-
        // independent probe just removes it so the next test starts clean.
        Processes.pidOf(name)?.let { Processes.kill9(name) }
        assertEquals(null, Processes.pidOf(name))
    }
}
