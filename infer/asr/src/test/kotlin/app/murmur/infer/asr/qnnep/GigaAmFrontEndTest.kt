package app.murmur.infer.asr.qnnep

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * The Kotlin GigaAM front end against its Python reference (native/qnn-contexts/gigaam_frontend.py, which reproduces
 * onnx-asr 0.12.0's GigaAM v3 preprocessor to 1.2e-6) on the 12 FLEURS dev ru clips of the asr-ocr spike
 * (fixtures/fleurs-ru: the WAVs and the reference features, written by `gigaam_frontend.py fixtures`).
 */
class GigaAmFrontEndTest {
    @Test
    fun matchesThePythonReferenceOnTheFleursClipsWithin1e4() {
        val ref = Fixtures.melRef
        assertEquals(12, Fixtures.clips.size)
        var worst = 0.0
        var frames = 0
        for (c in Fixtures.clips) {
            val samples = Fixtures.readWav(c.wav)
            assertEquals(c.id, c.frames, GigaAmFrontEnd.frames(samples.size))
            val got = GigaAmFrontEnd.features(samples)
            assertEquals(c.id, 64 * c.frames, got.size)
            var clipWorst = 0.0
            for (i in got.indices) clipWorst = maxOf(clipWorst, abs(got[i].toDouble() - ref[c.offsetFloats + i]))
            println(String.format(Locale.ROOT, "mel %s frames=%d max|d|=%.2e", c.id, c.frames, clipWorst))
            worst = maxOf(worst, clipWorst)
            frames += c.frames
        }
        assertEquals("every reference value compared", ref.size, 64 * frames)
        println(String.format(Locale.ROOT, "mel all %d clips, %d frames: max|d|=%.2e (limit 1e-4)", Fixtures.clips.size, frames, worst))
        assertTrue("max |d| $worst > 1e-4", worst <= 1e-4)
    }

    @Test
    fun constantsAreTheBf16TablesOfTheCheckpoint() {
        // bf16 values the v3 checkpoint holds (onnx-asr 0.12.0 fbanks.npz gigaam_v3_window / gigaam_v3, bit-equal to
        // the Python reference): the window rounds 0.5878981 to 0.58984375 at index 89 and peaks at exactly 1.0.
        val w = GigaAmFrontEnd.windowValues()
        assertEquals(320, w.size)
        assertEquals(0.0, w[0], 0.0)
        assertEquals(9.632110595703125E-5, w[1], 0.0)
        assertEquals(0.58984375, w[89], 0.0)
        assertEquals(1.0, w[160], 0.0)
        assertEquals(0.99609375, w[155], 0.0)
        val fb = GigaAmFrontEnd.filterbank()
        assertEquals(161, fb.size)
        assertEquals(0.8828125, fb[73][46], 0.0)
        assertEquals(0.2236328125, fb[1][0], 0.0)
        for (row in fb) for (v in row) assertEquals("bf16", v, GigaAmFrontEnd.bf16(v), 0.0)
        assertEquals(1.0, GigaAmFrontEnd.bf16(1.0 + 1.0 / 256), 0.0) // ties round to even
        assertEquals(1.015625, GigaAmFrontEnd.bf16(1.0 + 3.0 / 256), 0.0)
    }

    @Test
    fun framesPaddingAndRanges() {
        assertEquals(0, GigaAmFrontEnd.frames(319))
        assertEquals(1, GigaAmFrontEnd.frames(320))
        assertEquals(1999, GigaAmFrontEnd.frames(320_000))
        assertEquals(2000, GigaAmFrontEnd.frames(GigaAmFrontEnd.samplesFor(2000)))
        assertEquals(320_160, GigaAmFrontEnd.samplesFor(2000))

        val tone = FloatArray(16_000) { (0.3 * sin(2 * Math.PI * 440 * it / 16_000.0)).toFloat() }
        val plain = GigaAmFrontEnd.features(tone)
        val n = GigaAmFrontEnd.frames(tone.size)
        val padded = GigaAmFrontEnd.features(tone, outFrames = 200)
        for (m in 0 until 64) {
            assertArrayEquals(plain.copyOfRange(m * n, (m + 1) * n), padded.copyOfRange(m * 200, m * 200 + n), 0f)
            for (t in n until 200) assertEquals(GigaAmFrontEnd.SILENCE, padded[m * 200 + t], 0f)
        }
        // a sub-range is the features of that slice; digital silence is ln(1e-9) everywhere
        val slice = GigaAmFrontEnd.features(tone, 4_000, 12_000)
        assertArrayEquals(GigaAmFrontEnd.features(tone.copyOfRange(4_000, 12_000)), slice, 0f)
        assertTrue(GigaAmFrontEnd.features(FloatArray(1_600)).all { it == GigaAmFrontEnd.SILENCE })
        // a truncated output reads only the frames it holds
        assertEquals(64 * 10, GigaAmFrontEnd.features(tone, outFrames = 10).size)
    }
}
