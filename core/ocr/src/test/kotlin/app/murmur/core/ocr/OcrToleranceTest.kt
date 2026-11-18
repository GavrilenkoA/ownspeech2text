package app.murmur.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The README tolerance gate itself: a perturbed copy of a golden passes or fails where it should. */
class OcrToleranceTest {
    private val want = Golden.all().first { it.name == "004" }.ocr.lines

    private fun suite(got: List<OcrLine>) = OcrTolerance.Suite(listOf(OcrTolerance.compare("004", want, got)))

    @Test
    fun identicalOutputIsExact() {
        val s = suite(want)
        assertTrue(s.ok)
        assertTrue(s.pages.single().exact)
        assertEquals(0.0, s.cer, 0.0)
    }

    @Test
    fun smallBoxAndConfDriftIsWithin() {
        val got = want.map { it.copy(conf = it.conf + 0.015, box = it.box.map { v -> v + 2 }) }
        assertTrue(suite(got).toString(), suite(got).ok)
        assertFalse(suite(got).pages.single().exact)
    }

    @Test
    fun violationsFail() {
        assertFalse(suite(want.drop(1)).ok)                                                  // line count
        assertFalse(suite(want.map { it.copy(box = it.box.map { v -> v + 3 }) }).ok)         // box > 2 px
        assertFalse(suite(want.map { it.copy(conf = it.conf - 0.03) }).ok)                   // conf > 0.02
        assertFalse(suite(want.mapIndexed { i, l -> if (i < 2) l.copy(text = l.text + "x") else l }).ok) // 2/51 differ: < 98 %
        // One edit in one line of 51: identical 98.04 %, CER 1/558 = 0.18 %: within.
        assertTrue(suite(want.mapIndexed { i, l -> if (i == 0) l.copy(text = l.text + "x") else l }).ok)
    }
}
