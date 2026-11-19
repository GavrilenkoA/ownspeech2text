package app.murmur

import android.app.ApplicationExitInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-083: only an exit record with reason low memory makes a death during a load OutOfMemory. */
class AndroidProcessExitsTest {
    @Test fun aLowMemoryRecordThatArrivesLateIsFound() = runBlocking {
        var reads = 0
        val exits = AndroidProcessExits(reasonOf = { if (++reads < 3) null else ApplicationExitInfo.REASON_LOW_MEMORY }, waitMs = 50, stepMs = 1)
        assertTrue(exits.killedForMemory(4242))
        assertEquals(3, reads)
    }

    @Test fun anyOtherReasonNoRecordOrAFailingReadIsNotForMemory() = runBlocking {
        assertFalse(AndroidProcessExits(reasonOf = { ApplicationExitInfo.REASON_SIGNALED }, waitMs = 50, stepMs = 1).killedForMemory(1))
        assertFalse(AndroidProcessExits(reasonOf = { ApplicationExitInfo.REASON_CRASH_NATIVE }, waitMs = 50, stepMs = 1).killedForMemory(1))
        var reads = 0
        assertFalse(AndroidProcessExits(reasonOf = { reads++; null }, waitMs = 10, stepMs = 1).killedForMemory(1))
        assertEquals(11, reads)
        assertFalse(AndroidProcessExits(reasonOf = { error("binder") }, waitMs = 5, stepMs = 1).killedForMemory(1))
    }
}
