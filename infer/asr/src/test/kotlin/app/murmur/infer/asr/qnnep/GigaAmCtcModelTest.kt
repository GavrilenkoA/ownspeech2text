package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * [GigaAmCtcModel] end to end on ORT CPU with fixtures/tiny_ctc_20s.onnx (native/qnn-contexts/make_tiny_ctc.py): the
 * GigaAM graph's interface ([1, 64, 2000] + feature_lengths -> [1, 500, 257]) where mel band m votes for token 2 + m and
 * the blank wins in silence. Tones at band centres (600 / 900 / 1650 Hz = bands 15 / 20 / 30) must come out as those
 * tokens, in order, from the right 20 s window. The graph honours feature_lengths: its last valid step
 * ((len - 1) / 4) is a marker ("X" for a part window, "Q" when all 500 steps are valid) and every later step the PAD
 * token "Z". So a part window ends in "X" only when the model passes the real frame count (the padded 2000 makes it
 * "Q") and reads exactly the valid steps (reading on adds "Z").
 */
class GigaAmCtcModelTest {
    private val dir = File(Fixtures.repoRoot, "native/qnn-contexts/fixtures")
    private val spec = LoadSpec(ModelKind.ASR, ModelId("gigaam-v3-e2e-ctc"), ArtifactId("gigaam-v3-e2e-ctc@qnn-sm8750-20s"),
        RuntimeKeys.ORT_QNN_GIGAAM_CTC, ComputeUnit.NPU, dir.path,
        mapOf("model" to "tiny_ctc_20s.onnx", "vocab" to "gigaam_v3_e2e_ctc_vocab.txt"), 20.0)
    private val vocab = CtcVocabulary.parse(File(dir, "gigaam_v3_e2e_ctc_vocab.txt").readText())

    private fun model() = GigaAmCtcModel.withSession(spec,
        OrtEnvironment.getEnvironment().createSession(File(dir, "tiny_ctc_20s.onnx").path, OrtSession.SessionOptions()))

    /** [seconds] of silence with 0.5 s tones of (start s, Hz). */
    private fun audio(seconds: Double, vararg tones: Pair<Double, Double>): FloatArray {
        val x = FloatArray((seconds * 16_000).toInt())
        for ((start, hz) in tones) {
            val from = (start * 16_000).toInt()
            for (i in from until minOf(x.size, from + 8_000)) x[i] = (0.3 * sin(2 * PI * hz * (i - from) / 16_000)).toFloat()
        }
        return x
    }

    private fun tok(band: Int) = vocab.tokens[2 + band].replace('▁', ' ').trim()

    /** The stand-in's marker on the last valid step of a part window (make_tiny_ctc.py END) and of a full one (FULL). */
    private val end = "X"
    private val full = "Q"

    @Test
    fun tonesBecomeTheirTokensWithinOneWindow() {
        model().use { m ->
            assertEquals(320_160, m.windowSamples)
            assertEquals(tok(15) + tok(20) + tok(30) + end, m.transcribe(audio(6.0, 1.0 to 600.0, 2.5 to 900.0, 4.0 to 1650.0)))
            // silence: only the length marker, so the padded tail of the window adds nothing
            assertEquals(end, m.transcribe(audio(3.0)))
            assertEquals("", m.transcribe(FloatArray(100)))
            val d = m.decoder().decode(audio(2.0, 0.5 to 900.0), "ru")
            assertEquals(tok(20) + end, d.text)
            assertNull("GigaAM reports no language", d.language)
        }
    }

    @Test
    fun audioLongerThanTheWindowIsDecodedWindowByWindow() {
        model().use { m ->
            // 27 s: two tones in the first 20.01 s window (all 2000 frames valid), one in the second (shorter, so its
            // marker sits earlier); window texts are joined with a space
            val text = m.transcribe(audio(27.0, 1.0 to 600.0, 18.0 to 900.0, 23.0 to 1650.0))
            assertEquals(tok(15) + tok(20) + full + " " + tok(30) + end, text)
        }
    }
}
