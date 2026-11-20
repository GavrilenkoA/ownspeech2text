package app.murmur.infer.asr.qnnep

import app.murmur.infer.asr.qnnep.QnnContexts.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CtcAndContextsTest {
    private val vocab = CtcVocabulary(listOf("<unk>", "▁", "п", "р", "и", "▁в", ".", "<blk>"))

    /** One-hot log-probabilities for a list of class ids. */
    private fun logp(ids: List<Int>, classes: Int = vocab.tokens.size) = FloatArray(ids.size * classes) { -10f }.also { a ->
        ids.forEachIndexed { t, k -> a[t * classes + k] = 0f }
    }

    @Test
    fun greedyCollapsesRepeatsDropsBlanksAndMarksWords() {
        val b = vocab.blank
        assertEquals(7, b)
        // "▁ п п <blk> п р и и ▁в ." -> " пприв." with the repeat before the blank kept apart
        val ids = listOf(1, 2, 2, b, 2, 3, 4, 4, 5, 6, b, b)
        assertEquals("ппри в.", CtcGreedy.decode(logp(ids), ids.size, 8, vocab))
        // only the valid steps are read: the padded tail of a 20 s window never adds text
        assertEquals("пп", CtcGreedy.decode(logp(ids), 5, 8, vocab))
        assertEquals("", CtcGreedy.decode(logp(listOf(b, b, b)), 3, 8, vocab))
        // two word starts in a row give one space
        assertEquals("п в", CtcGreedy.decode(logp(listOf(2, 1, 5)), 3, 8, vocab))
        assertThrows(IllegalArgumentException::class.java) { CtcGreedy.decode(logp(ids), ids.size, 9, vocab) }
        assertThrows(IllegalArgumentException::class.java) { CtcGreedy.decode(logp(ids), ids.size + 1, 8, vocab) }
    }

    @Test
    fun vocabularyFileOfTheExport() {
        val v = CtcVocabulary.parse(File(Fixtures.repoRoot, "native/qnn-contexts/fixtures/gigaam_v3_e2e_ctc_vocab.txt").readText())
        assertEquals(257, v.tokens.size)
        assertEquals("<blk>", v.tokens[v.blank])
        assertEquals("▁", v.tokens[1])
        assertThrows(IllegalArgumentException::class.java) { CtcVocabulary.parse("a 0\nb 2\n") }
        assertThrows(IllegalArgumentException::class.java) { CtcVocabulary.parse("a0\n") }
    }

    @Test
    fun validStepsFollowTheSubsampling() {
        assertEquals(0, GigaAmCtcModel.validSteps(0))
        assertEquals(1, GigaAmCtcModel.validSteps(1))
        assertEquals(1, GigaAmCtcModel.validSteps(4))
        assertEquals(2, GigaAmCtcModel.validSteps(5))
        assertEquals(500, GigaAmCtcModel.validSteps(2000))
        assertEquals(488, GigaAmCtcModel.validSteps(1949)) // the Segmenter's 19.5 s hard cut
    }

    @Test
    fun layoutRolesNameTheContextsByShape() {
        val layout = mapOf("ctx.h960w416" to "det_h960_w416.onnx", "ctx.h416w960" to "det_h416_w960.onnx", "dict" to "rec.yml",
            "ctx.w320" to "rec_w320.onnx", "ctx.w3200" to "rec_w3200.onnx", "model" to "x.onnx")
        assertEquals(mapOf(Shape(960, 416) to File("/a", "det_h960_w416.onnx"), Shape(416, 960) to File("/a", "det_h416_w960.onnx")),
            QnnContexts.detector("/a", layout))
        assertEquals(mapOf(320 to File("/a", "rec_w320.onnx"), 3200 to File("/a", "rec_w3200.onnx")), QnnContexts.recogniser("/a", layout))
    }

    @Test
    fun adspPathPutsTheAppLibrariesFirstOnce() {
        val vendor = "/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"
        assertEquals("/data/app/x/lib/arm64;$vendor", QnnEpAndroid.adspPath("/data/app/x/lib/arm64", null))
        assertEquals("/data/app/x/lib/arm64;/odm/lib/rfsa/adsp", QnnEpAndroid.adspPath("/data/app/x/lib/arm64", "/odm/lib/rfsa/adsp"))
        assertEquals("/data/app/x/lib/arm64;/odm", QnnEpAndroid.adspPath("/data/app/x/lib/arm64", "/data/app/x/lib/arm64;/odm"))
    }
}
