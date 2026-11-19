package app.murmur.platform.audio

import app.murmur.platform.audio.MicReadWatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicReadWatchTest {
    @Test
    fun samplesAreUsedAndANegativeReadIsALossWithItsCode() {
        val w = MicReadWatch()
        assertEquals(Verdict.Samples, w.onRead(320, 0))
        assertEquals(Verdict.Lost("read:-6"), w.onRead(-6, 20)) // AudioRecord.ERROR_DEAD_OBJECT
        assertEquals(Verdict.Lost("read:-3"), MicReadWatch().onRead(-3, 0)) // ERROR_INVALID_OPERATION
    }

    @Test
    fun emptyReadsBackOffAndBecomeALossAfterTheGap() {
        val w = MicReadWatch(maxGapMs = 1_000, emptyBackoffMs = 5)
        assertEquals(Verdict.Samples, w.onRead(320, 0))
        var t = 20L
        var retries = 0
        var v: Verdict
        while (true) {
            v = w.onRead(0, t)
            if (v !is Verdict.Retry) break
            assertEquals(5L, v.ms)
            retries++
            t += 5
        }
        assertEquals(Verdict.Lost("read:0"), v)
        assertEquals("lost exactly when the reads stayed empty for 1 s", 1_020L, t)
        assertEquals(200, retries)
    }

    @Test
    fun samplesBetweenEmptyReadsResetTheGap() {
        val w = MicReadWatch(maxGapMs = 1_000)
        assertTrue(w.onRead(0, 0) is Verdict.Retry)
        assertTrue(w.onRead(0, 900) is Verdict.Retry)
        assertEquals(Verdict.Samples, w.onRead(320, 950))
        assertTrue("the gap starts again", w.onRead(0, 1_500) is Verdict.Retry)
        assertTrue(w.onRead(0, 2_499) is Verdict.Retry)
        assertEquals(Verdict.Lost("read:0"), w.onRead(0, 2_500))
    }

    @Test
    fun aReadThatBlocksIsAStallOnlyOnceArmedAndAfterTheGap() {
        val w = MicReadWatch(maxGapMs = 1_000)
        assertFalse("not armed: the start check owns the first second", w.stalled(5_000))
        w.armed(10_000)
        assertFalse(w.stalled(10_999))
        assertTrue(w.stalled(11_000))
        w.onRead(320, 11_100)
        assertFalse("samples came again", w.stalled(12_000))
        assertTrue(w.stalled(12_100))
    }
}
