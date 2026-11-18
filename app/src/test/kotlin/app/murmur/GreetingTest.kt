package app.murmur

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun coverScreenIsCompactWidth() {
        assertEquals("Murmur - cover screen", Greeting.title(widthDp = 411))
    }

    @Test
    fun innerScreenIsExpandedWidth() {
        assertEquals("Murmur - unfolded", Greeting.title(widthDp = 840))
    }
}
