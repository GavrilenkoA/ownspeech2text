package app.murmur

import android.app.ApplicationExitInfo
import app.murmur.AndroidProcessExits.Exit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-083: only an exit record with reason low memory makes a death during a load OutOfMemory. */
class AndroidProcessExitsTest {
    private val lowMemory = Exit(ApplicationExitInfo.REASON_LOW_MEMORY, 0)

    @Test fun aLowMemoryRecordThatArrivesLateIsFound() = runBlocking {
        var reads = 0
        val lines = mutableListOf<String>()
        val exits = AndroidProcessExits(recordOf = { if (++reads < 3) null else lowMemory }, log = { lines += it }, waitMs = 50, stepMs = 1)
        assertTrue(exits.killedForMemory(4242))
        assertEquals(3, reads)
        assertEquals(listOf("load_death reason=${ApplicationExitInfo.REASON_LOW_MEMORY} status=0 lmk_report=true waited_ms=2 oom=true"), lines)
    }

    @Test fun anyOtherReasonNoRecordOrAFailingReadIsNotForMemory() = runBlocking {
        assertFalse(AndroidProcessExits(recordOf = { Exit(ApplicationExitInfo.REASON_SIGNALED, 9) }, waitMs = 50, stepMs = 1).killedForMemory(1))
        assertFalse(AndroidProcessExits(recordOf = { Exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 6) }, waitMs = 50, stepMs = 1).killedForMemory(1))
        var reads = 0
        val lines = mutableListOf<String>()
        assertFalse(AndroidProcessExits(recordOf = { reads++; null }, log = { lines += it }, waitMs = 10, stepMs = 1).killedForMemory(1))
        assertEquals(11, reads)
        assertEquals(listOf("load_death reason=none status=none lmk_report=true waited_ms=10 oom=false"), lines)
        assertFalse(AndroidProcessExits(recordOf = { error("binder") }, waitMs = 5, stepMs = 1).killedForMemory(1))
    }

    /** Verification F1/F2: without lmkd kill reports a kill is REASON_SIGNALED; no wait is spent, the raw record is logged. */
    @Test fun withoutLowMemoryKillReportsTheRecordIsReadOnceAndLogged() = runBlocking {
        var reads = 0
        val lines = mutableListOf<String>()
        val exits = AndroidProcessExits(recordOf = { reads++; null }, lowMemoryKillReported = false, log = { lines += it }, waitMs = 10_000, stepMs = 1)
        assertFalse(exits.killedForMemory(7))
        assertEquals(1, reads)
        assertEquals(listOf("load_death reason=none status=none lmk_report=false waited_ms=0 oom=false"), lines)
        val signaled = AndroidProcessExits(recordOf = { Exit(ApplicationExitInfo.REASON_SIGNALED, 9) }, lowMemoryKillReported = false, log = { lines += it })
        assertFalse(signaled.killedForMemory(7))
        assertEquals("load_death reason=${ApplicationExitInfo.REASON_SIGNALED} status=9 lmk_report=false waited_ms=0 oom=false", lines.last())
    }
}
