package app.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogTest {
    @Test
    fun namesTheCauseChainAndFrameButNoMessage() {
        val secret = "привет мир 555-1234"
        val t = IllegalStateException("outer $secret", IllegalArgumentException("inner $secret"))
        val line = internalErrorLine(t)
        assertFalse(line, line.contains("привет") || line.contains("555") || line.contains("outer") || line.contains("inner"))
        assertTrue(line, line.startsWith("internal_error java.lang.IllegalStateException <- java.lang.IllegalArgumentException at "))
        assertTrue(line, line.contains("ErrorLogTest.namesTheCauseChainAndFrameButNoMessage:"))
    }

    @Test
    fun stopsOnACauseCycleAndWithoutAStack() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        a.stackTrace = emptyArray()
        assertEquals(
            "internal_error java.lang.RuntimeException <- java.lang.RuntimeException <- java.lang.RuntimeException" +
                " <- java.lang.RuntimeException <- java.lang.RuntimeException at ?",
            internalErrorLine(a),
        )
    }
}
