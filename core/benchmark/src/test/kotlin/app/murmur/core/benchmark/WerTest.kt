package app.murmur.core.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class WerTest {
    @Test
    fun werIgnoresCaseAndPunctuation() {
        assertEquals(0.0, WordErrorRate.wer("Привет, мир!", "привет мир"), 1e-9)
        assertEquals(0.5, WordErrorRate.wer("one two", "one three"), 1e-9)
    }
}
