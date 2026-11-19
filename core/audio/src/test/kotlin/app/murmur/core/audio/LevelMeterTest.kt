package app.murmur.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class LevelMeterTest {
    private val frame = PcmFormat.FRAME_SAMPLES

    /** 1 kHz sine at [amplitude], continuous phase across calls (offset = absolute sample index). */
    private fun tone(n: Int, amplitude: Double, offset: Long = 0): ShortArray =
        ShortArray(n) { (amplitude * sin(2 * PI * 1000 * (offset + it) / PcmFormat.SAMPLE_RATE)).toInt().toShort() }

    /** Feeds [pcm] in 20 ms frames and returns the smoothed level after every 30 Hz tick. */
    private fun levelsPerTick(meter: LevelMeter, pcm: ShortArray): List<Double> {
        val out = ArrayList<Double>()
        var lastTick = meter.ticks
        // One sample at a time so that every tick is observed (a frame can contain one tick at most at 30 Hz / 50 Hz,
        // but this keeps the test independent of the frame size).
        for (i in pcm.indices) {
            meter.push(shortArrayOf(pcm[i]))
            if (meter.ticks != lastTick) { lastTick = meter.ticks; out += meter.level }
        }
        return out
    }

    @Test
    fun silenceKeepsEveryBarAtTheMinimum() {
        val meter = LevelMeter()
        val zeros = ShortArray(frame)
        repeat(250) { // 5 s
            val bars = meter.push(zeros)
            assertArrayEquals(FloatArray(7), bars, 0f)
        }
        assertEquals("5 s at 30 Hz", 150L, meter.ticks)
    }

    @Test
    fun ticksAtThirtyHertzOnTheSampleClock() {
        val meter = LevelMeter()
        val zeros = ShortArray(frame)
        repeat(50 * 60) { meter.push(zeros) } // 60 s in 20 ms frames
        assertEquals(30L * 60, meter.ticks)
    }

    @Test
    fun aToneRampsUpWithTheAttackConstantAndDownWithTheRelease() {
        val meter = LevelMeter()
        val amp = 8000.0
        val onset = PcmFormat.msToSamples(1000).toInt()
        val toneLen = PcmFormat.msToSamples(1000).toInt()
        val pcm = ShortArray(onset) + tone(toneLen, amp, onset.toLong()) + ShortArray(PcmFormat.msToSamples(1000).toInt())
        val levels = levelsPerTick(meter, pcm)
        // The 50 ms window holds exactly 50 periods of the 1 kHz tone, so its RMS is the same at every tick.
        val target = meter.levelOf(PcmMath.rms(tone(PcmFormat.msToSamples(50).toInt(), amp)))
        val tickMs = 1000.0 / 30

        // Silence before the onset: exactly zero.
        val onsetTick = (onset / meter.tickSamples).roundToInt()
        for (k in 0 until onsetTick) assertEquals(0.0, levels[k], 0.0)

        // Once the 50 ms window holds only the tone (2 ticks after the onset), each tick closes the remaining gap by
        // e^(-dt/40 ms): the attack constant of DESIGN 3.5.
        val expectedAttack = exp(-tickMs / 40)
        for (k in onsetTick + 3 until onsetTick + 8) {
            val ratio = (target - levels[k]) / (target - levels[k - 1])
            assertEquals("attack ratio at tick $k", expectedAttack, ratio, 1e-6)
        }
        // After ~1 s of tone the level has converged.
        val endOfTone = ((onset + toneLen) / meter.tickSamples).roundToInt() - 1
        assertEquals(target, levels[endOfTone], 1e-6)

        // Release: after the window emptied, each tick keeps e^(-dt/120 ms) of the level, slower than the attack.
        val expectedRelease = exp(-tickMs / 120)
        for (k in endOfTone + 3 until endOfTone + 8) {
            assertEquals("release ratio at tick $k", expectedRelease, levels[k] / levels[k - 1], 1e-6)
        }
        assertTrue(expectedRelease > expectedAttack)
        // ... and silence ends flat at zero, not at an invisible tail.
        assertEquals(0.0, levels.last(), 0.0)
    }

    @Test
    fun barsArePerBarWeightsOfOneLevel() {
        val meter = LevelMeter()
        val pcm = tone(PcmFormat.msToSamples(1000).toInt(), 6000.0)
        var bars = FloatArray(7)
        for (i in pcm.indices step frame) bars = meter.push(pcm.copyOfRange(i, minOf(pcm.size, i + frame)))
        val level = meter.level
        assertTrue("a 6000-amplitude tone lights the meter", level > 0.5)
        for (i in bars.indices) assertEquals("bar $i", (level * meter.weights[i]).toFloat(), bars[i], 1e-6f)
        // The middle bar has weight 1 and is the tallest.
        assertEquals(bars.max(), bars[2], 0f)
        assertArrayEquals(floatArrayOf(.35f, .7f, 1f, .6f, .85f, .45f, .25f), meter.weights, 0f)
    }

    @Test
    fun loudInputIsClampedToOne() {
        val meter = LevelMeter()
        val pcm = ShortArray(PcmFormat.msToSamples(1000).toInt()) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        var bars = FloatArray(7)
        for (i in pcm.indices step frame) bars = meter.push(pcm.copyOfRange(i, i + frame))
        assertEquals(1f, bars[2], 1e-6f)
        assertTrue(bars.all { it in 0f..1f })
    }

    @Test
    fun levelMappingIsLinearInDecibelsBetweenFloorAndCeiling() {
        val meter = LevelMeter()
        val fs = 32768.0
        assertEquals(0.0, meter.levelOf(0.0), 0.0)
        assertEquals(0.0, meter.levelOf(fs * Math.pow(10.0, -60.0 / 20)), 0.0) // below the -55 dBFS floor
        assertEquals(0.5, meter.levelOf(fs * Math.pow(10.0, -32.5 / 20)), 1e-9) // halfway between -55 and -10
        assertEquals(1.0, meter.levelOf(fs), 0.0)
    }
}
