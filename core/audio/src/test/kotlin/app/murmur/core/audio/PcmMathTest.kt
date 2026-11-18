package app.murmur.core.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PcmFormatTest {
    @Test
    fun frameAndTimeMath() {
        assertEquals(16_000, PcmFormat.SAMPLE_RATE)
        assertEquals(320L, PcmFormat.msToSamples(20)) // one 20 ms frame
        assertEquals(PcmFormat.FRAME_SAMPLES.toLong(), PcmFormat.msToSamples(20))
        assertEquals(20L, PcmFormat.samplesToMs(PcmFormat.FRAME_SAMPLES.toLong()))
        // 10 s of capture = 500 frames at 50 Hz (the instrumented acceptance count).
        assertEquals(500L, PcmFormat.msToSamples(10_000) / PcmFormat.FRAME_SAMPLES)
        // 5 min session buffer = 4.8 M samples = 9.6 MB of PCM16 (ARCHITECTURE 4.3).
        assertEquals(9_600_000L, PcmFormat.msToSamples(300_000) * PcmFormat.BYTES_PER_SAMPLE)
        assertEquals(32_000, PcmFormat.SAMPLE_RATE * PcmFormat.BYTES_PER_SAMPLE * PcmFormat.CHANNELS) // 32 KB/s pipe
    }

    @Test
    fun chunkEndIsStartPlusSize() {
        val c = PcmChunk(ShortArray(320), 640)
        assertEquals(960L, c.endSample)
    }
}

class PcmSessionBufferTest {
    private fun frame(v: Int) = ShortArray(PcmFormat.FRAME_SAMPLES) { v.toShort() }

    @Test
    fun lateCollectorsGetEverySampleFromZeroThenLiveFrames() = runTest {
        val buf = PcmSessionBuffer()
        repeat(3) { buf.append(frame(it)) }
        val early = async { buf.chunks().toList() }
        yield()
        repeat(2) { buf.append(frame(3 + it)) }
        val late = async { buf.chunks().toList() } // attaches after 5 frames, like a re-bound :asr
        yield()
        buf.append(frame(5))
        buf.close()
        for (list in listOf(early.await(), late.await())) {
            assertEquals(6, list.size)
            list.forEachIndexed { i, c ->
                assertEquals(i * 320L, c.startSample)
                assertEquals(i.toShort(), c.samples[0])
                assertEquals(320, c.samples.size)
            }
        }
        assertEquals(6 * 320L, buf.totalSamples)
    }

    @Test
    fun appendCopiesAndClosedBufferRefuses() {
        val buf = PcmSessionBuffer()
        val data = frame(7)
        buf.append(data, 100)
        data.fill(0)
        assertEquals(7.toShort(), buf.toShortArray()[99])
        assertEquals(100, buf.toShortArray().size)
        buf.close()
        assertFalse(buf.append(frame(1)))
        assertEquals(100L, buf.totalSamples)
    }

    @Test
    fun boundedAtMaxSamples() {
        val buf = PcmSessionBuffer(maxSamples = 1000)
        assertTrue(buf.append(frame(1))) // 320
        assertTrue(buf.append(frame(1))) // 640
        assertTrue(buf.append(frame(1))) // 960
        assertFalse(buf.append(frame(1))) // would be 1280
        assertEquals(960L, buf.totalSamples)
        assertEquals(320L, buf.droppedSamples)
        assertEquals(PcmFormat.msToSamples(302_000), PcmSessionBuffer.DEFAULT_MAX_SAMPLES)
    }

    @Test
    fun emptyClosedBufferCompletesImmediately() = runTest {
        val buf = PcmSessionBuffer()
        buf.close()
        assertEquals(0, buf.chunks().toList().size)
    }
}

class FrameAssemblerTest {
    @Test
    fun anyReadSizesBecomeWhole20msFrames() {
        val frames = ArrayList<ShortArray>()
        val asm = FrameAssembler { f, n -> frames += f.copyOf(n) }
        val src = ShortArray(1000) { it.toShort() }
        asm.push(src, 0, 100)
        asm.push(src, 100, 500)
        asm.push(src, 600, 400)
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == 320 })
        asm.flush()
        assertEquals(4, frames.size)
        assertEquals(40, frames[3].size)
        assertArrayEquals(src, frames.reduce { a, b -> a + b })
    }
}

class MicStartGateTest {
    @Test
    fun firstNonZeroSampleStarts() {
        val g = MicStartGate()
        assertEquals(MicStartGate.State.PENDING, g.push(ShortArray(320)))
        assertEquals(MicStartGate.State.STARTED, g.push(ShortArray(320).also { it[319] = 1 }))
        assertEquals(MicStartGate.State.STARTED, g.push(ShortArray(320)))
    }

    @Test
    fun oneSecondOfDigitalZerosIsSilenced() {
        val g = MicStartGate()
        repeat(49) { assertEquals(MicStartGate.State.PENDING, g.push(ShortArray(320))) }
        assertEquals(MicStartGate.State.SILENT, g.push(ShortArray(320))) // 50 x 20 ms = 1 s
        assertEquals(MicStartGate.State.SILENT, g.push(ShortArray(320).also { it[0] = 5 }))
    }
}

class PcmMathTest {
    @Test
    fun peakAndRms() {
        val s = shortArrayOf(1, -7, 3, Short.MIN_VALUE, 0)
        assertEquals(32768, PcmMath.peakAbs(s))
        assertEquals(7, PcmMath.peakAbs(s, 3))
        assertEquals(2.0, PcmMath.rms(shortArrayOf(2, -2, 2, -2)), 1e-12)
        assertEquals(0.0, PcmMath.rms(ShortArray(0)), 0.0)
    }

    @Test
    fun snrFindsTheLagAndMeasuresNoise() {
        val ref = ShortArray(32_000) { (8000 * sin(2 * PI * 440 * it / 16_000)).toInt().toShort() }
        val lag = 37
        val noise = 80 // 1 % of the amplitude -> ~37 dB
        val rnd = java.util.Random(1)
        val dec = ShortArray(ref.size + lag) { i ->
            val j = i - lag
            if (j in ref.indices) (ref[j] + rnd.nextInt(2 * noise + 1) - noise).toShort() else 0
        }
        val r = PcmMath.snr(ref, dec, maxLag = 100)
        assertEquals(lag, r.lag)
        assertTrue("snr ${r.db}", r.db in 30.0..45.0)
        assertEquals(Double.POSITIVE_INFINITY, PcmMath.snr(ref, ref, 5).db, 0.0)
    }
}
