package app.murmur.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class ResamplerTest {
    private fun sine(rate: Int, seconds: Double, hz: Double, amp: Double = 10_000.0) =
        ShortArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    @Test
    fun fortyEightKiloHertzDecoderOutputLinesUpWithSixteenKiloHertz() {
        // What the Opus decoder hands back (48 kHz) must come back as the 16 kHz signal, sample for sample (zero lag).
        val src = sine(48_000, 1.0, 440.0)
        val out = Resampler.resample(src, 48_000, 16_000)
        assertEquals(16_000, out.size)
        val ref = sine(16_000, 1.0, 440.0)
        val snr = PcmMath.snr(ref, out, maxLag = 10, edge = 200)
        assertEquals(0, snr.lag)
        assertTrue("snr ${snr.db}", snr.db > 50)
    }

    @Test
    fun removesContentAboveTheNewNyquist() {
        // A 12 kHz tone at 48 kHz cannot exist at 16 kHz (Nyquist 8 kHz): it must be filtered, not aliased to 4 kHz.
        val out = Resampler.resample(sine(48_000, 1.0, 12_000.0), 48_000, 16_000)
        val rms = PcmMath.rms(out, 200, out.size - 200)
        assertTrue("alias rms $rms", rms < 10_000 / Math.sqrt(2.0) * 0.01) // < -40 dB
    }

    @Test
    fun otherRatesAndRoundTrip() {
        val src = sine(16_000, 1.0, 1000.0)
        for (rate in listOf(8_000, 22_050, 24_000, 44_100, 48_000)) {
            val there = Resampler.resample(src, 16_000, rate)
            assertTrue("length at $rate", kotlin.math.abs(there.size - rate) <= 1)
            val back = Resampler.resample(there, rate, 16_000)
            val snr = PcmMath.snr(src, back, maxLag = 4, edge = 400)
            assertEquals("lag at $rate", 0, snr.lag)
            assertTrue("round trip via $rate Hz: ${snr.db} dB", snr.db > 40)
        }
        assertArrayEquals(src, Resampler.resample(src, 16_000, 16_000))
    }

    @Test
    fun downmixAveragesChannels() {
        assertArrayEquals(shortArrayOf(2, -3), Resampler.downmix(shortArrayOf(1, 3, -2, -4), 2))
        val mono = shortArrayOf(5, 6)
        assertArrayEquals(mono, Resampler.downmix(mono, 1))
    }
}

class WavPcmTest {
    @Test
    fun roundTripMono16k() {
        val pcm = ShortArray(1600) { (it * 7 - 3000).toShort() }
        val clip = WavPcm.decode(WavPcm.encode(pcm))
        assertEquals(16_000, clip.sampleRate)
        assertEquals(1, clip.channels)
        assertArrayEquals(pcm, clip.samples)
        assertArrayEquals(pcm, clip.toCaptureFormat())
    }

    @Test
    fun stereo48kBecomesMono16k() {
        val frames = 4800
        val stereo = ShortArray(frames * 2) { i -> (8000 * sin(2 * PI * 300 * (i / 2) / 48_000)).toInt().toShort() }
        val clip = WavPcm.decode(WavPcm.encode(stereo, 48_000, 2))
        assertEquals(frames, clip.frames)
        val mono = clip.toCaptureFormat()
        assertEquals(1600, mono.size)
        val ref = ShortArray(1600) { (8000 * sin(2 * PI * 300 * it / 16_000)).toInt().toShort() }
        assertTrue(PcmMath.snr(ref, mono, 2, edge = 100).db > 40)
    }

    @Test
    fun skipsUnknownChunksAndRejectsNonPcm() {
        val pcm = shortArrayOf(1, 2, 3)
        val plain = WavPcm.encode(pcm)
        // Insert a "LIST" chunk (odd size, padded) between fmt and data.
        val list = ByteBuffer.allocate(8 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)).array()
        val withList = plain.copyOfRange(0, 36) + list + plain.copyOfRange(36, plain.size)
        assertArrayEquals(pcm, WavPcm.decode(withList).samples)

        val eightBit = plain.copyOf().also { it[34] = 8 } // bits per sample
        assertThrows(WavPcm.FormatException::class.java) { WavPcm.decode(eightBit) }
        assertThrows(WavPcm.FormatException::class.java) { WavPcm.decode("not a wav".toByteArray()) }
    }
}
