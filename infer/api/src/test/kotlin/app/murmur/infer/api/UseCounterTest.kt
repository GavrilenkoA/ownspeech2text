package app.murmur.infer.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCounterTest {
    @Test
    fun countsNestedUsesAndReportsOnlyTheLastEnd() {
        val c = UseCounter()
        c.begin()
        c.begin()
        assertFalse(c.end())
        assertEquals(1, c.count)
        assertTrue(c.end())
        assertEquals(0, c.count)
    }

    @Test
    fun anUnbalancedEndNeverGoesBelowZeroNorSwallowsALaterBegin() {
        val c = UseCounter()
        assertTrue(c.end())
        assertEquals(0, c.count)
        c.begin()
        assertEquals(1, c.count)
        assertTrue(c.end())
    }

    /**
     * The idle timer is armed when the count is 0 (ServiceConnector.armIdle checks it under its lock). While this
     * thread holds a use, another thread hammering begin/end must never make the count read 0.
     */
    @Test
    fun aHeldUseIsNeverLostToAConcurrentBeginAndEnd() {
        val c = UseCounter()
        val stop = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val other = Thread {
            started.countDown()
            while (!stop.get()) {
                c.begin()
                c.end()
            }
        }
        var zeroReads = 0
        var held = 0
        other.start()
        started.await()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline && held < 300_000) {
            c.begin()
            held++
            repeat(4) { if (c.count == 0) zeroReads++ }
            c.end()
        }
        stop.set(true)
        other.join()
        assertEquals("count read 0 while a use was held ($held held uses)", 0, zeroReads)
        assertEquals(0, c.count)
    }
}
