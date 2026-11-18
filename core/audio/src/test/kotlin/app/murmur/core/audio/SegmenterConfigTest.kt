package app.murmur.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmenterConfigTest {
    @Test
    fun hardMaxFollowsTheModelWindow() {
        assertEquals(19_500, SegmenterConfig.forWindow(20.0).hardMaxMs)
        assertEquals(29_500, SegmenterConfig.forWindow(30.0).hardMaxMs)
        assertEquals(19_500, SegmenterConfig.forWindow(null).hardMaxMs)
        assertEquals(320, PcmFormat.FRAME_SAMPLES)
        assertEquals(1000L, PcmFormat.samplesToMs(16_000))
    }
}
