package app.murmur.e2e

import android.os.SystemClock
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emulator console commands (`adb emu ...`) exist only on the host. A test asks for one on logcat
 * (`E2E-HOST: req=<id> emu <args>`); tools/e2e/host_bridge.sh, started by run_emulator_e2e.sh, runs it if it is on its
 * allowlist (hinge sensor, gsm call/accept/cancel) and answers with `<DEVICE_DIR>/ack-<id>` = `rc=<exit code>`.
 * The bridge announces itself in `<DEVICE_DIR>/bridge` (removed when it stops).
 */
object HostBridge {
    private const val TAG = "E2E-HOST"
    private val seq = AtomicInteger()

    val running: Boolean get() = E2e.shell("ls ${E2e.DEVICE_DIR}/bridge").trim() == "${E2e.DEVICE_DIR}/bridge"

    fun requireRunning() {
        if (!running) fail("the host bridge is not running: start the suite with tools/e2e/run_emulator_e2e.sh")
    }

    /** Runs `adb emu <args>` on the host and fails the test unless it exits 0 within [timeoutMs]. */
    fun emu(args: String, timeoutMs: Long = 15_000) {
        requireRunning()
        val id = "${SystemClock.uptimeMillis()}${seq.incrementAndGet()}"
        Log.i(TAG, "req=$id emu $args")
        val ack = "${E2e.DEVICE_DIR}/ack-$id"
        val reply = E2e.waitFor("host bridge ack for 'emu $args'", timeoutMs, pollMs = 100) {
            E2e.shell("cat $ack").trim().takeIf { it.startsWith("rc=") }
        }
        E2e.shell("rm $ack")
        assertEquals("adb emu $args", "rc=0", reply.lineSequence().first())
    }

    fun hingeAngle(degrees: Int) = emu("sensor set hinge-angle0 $degrees")
    fun gsmCall(number: String) = emu("gsm call $number")
    fun gsmAccept(number: String) = emu("gsm accept $number")
    fun gsmCancel(number: String) = emu("gsm cancel $number")

    /** `dumpsys device_state` committed state name ("CLOSED", "OPENED", "HALF_OPENED", ...), or null. */
    fun deviceState(): String? {
        val out = E2e.shell("dumpsys device_state")
        return Regex("""mCommittedState=Optional\[DeviceState\{identifier=\d+, name='([A-Z_]+)'""").find(out)?.groupValues?.get(1)
            ?: Regex("""committed state: DeviceState\{identifier=\d+, name='([A-Z_]+)'""", RegexOption.IGNORE_CASE).find(out)?.groupValues?.get(1)
    }

    fun awaitDeviceState(name: String, timeoutMs: Long = 10_000) {
        E2e.waitUntil("device_state $name (now ${deviceState()})", timeoutMs, pollMs = 200) { deviceState() == name }
        assertTrue(deviceState() == name)
    }
}
