package app.murmur.ui.design.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTest {
    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:04", formatClock(4_000))
        assertEquals("0:12", formatClock(11_600))
        assertEquals("1:05", formatClock(65_000))
        assertEquals("5:00", formatClock(300_000))
        assertEquals("0:00", formatClock(-20))
    }

    @Test
    fun peaksAreDownsampledByBucketMaximum() {
        val peaks = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f, 0.5f, 0.4f)
        assertArrayEquals(floatArrayOf(0.9f, 0.3f, 0.5f), resamplePeaks(peaks, 3), 0f)
        assertArrayEquals(floatArrayOf(0.9f, 0.5f), resamplePeaks(peaks, 2), 0f)
        // Upsampling repeats values; out-of-range input is clamped.
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), resamplePeaks(floatArrayOf(1.4f, -0.2f), 4), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), resamplePeaks(FloatArray(0), 2), 0f)
        assertEquals(0, resamplePeaks(peaks, 0).size)
    }
}
