package app.murmur.core.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WerTest {
    @Test
    fun werIgnoresCaseAndPunctuation() {
        assertEquals(0.0, WordErrorRate.wer("Привет, мир!", "привет мир"), 1e-9)
        assertEquals(0.5, WordErrorRate.wer("one two", "one three"), 1e-9)
    }

    @Test
    fun validityGateRejectsFastButWrongResults() {
        assertNull(BenchValidity.asr(0.074, cpuWer = 0.076))
        assertEquals("wer_high", BenchValidity.asr(0.9, cpuWer = null))
        assertEquals("wer_above_cpu", BenchValidity.asr(0.15, cpuWer = 0.07))
        assertNull(BenchValidity.llm("Привет, как дела?", guardAccepted = true, stopReason = 0, expectCyrillic = true))
        assertEquals("empty", BenchValidity.llm(" ", true, 0, true))
        assertEquals("early_stop", BenchValidity.llm("Привет", true, 1, true))
        assertEquals("wrong_script", BenchValidity.llm("Hello, how are you?", true, 0, true))
    }
}
