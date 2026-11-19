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
    }

    /** README: CER <= 0.5 % "on the rest", i.e. over the non-identical lines, not diluted by the identical ones. */
    @Test
    fun cerIsGatedOnTheNonIdenticalLines() {
        // One edit in one short line of 51: identical 98.04 %, CER over all lines 1/558 = 0.18 %, but on the rest
        // 1/len(line 0) (several %): fails.
        val short = suite(want.mapIndexed { i, l -> if (i == 0) l.copy(text = l.text + "x") else l })
        assertTrue(short.cer < OcrTolerance.MAX_CER)
        assertTrue(short.restCer > OcrTolerance.MAX_CER)
        assertFalse(short.toString(), short.ok)

        // One edit in a line of 200 code points: rest CER exactly 0.5 %: within. 199 code points: 0.503 %: fails.
        fun oneEditIn(len: Int): OcrTolerance.Suite {
            val long = "ж".repeat(len)
            val ref = want.mapIndexed { i, l -> if (i == 0) l.copy(text = long) else l }
            val got = ref.mapIndexed { i, l -> if (i == 0) l.copy(text = "x" + long.drop(1)) else l }
            return OcrTolerance.Suite(listOf(OcrTolerance.compare("004", ref, got)))
        }
        assertTrue(oneEditIn(200).toString(), oneEditIn(200).ok)
        assertEquals(0.005, oneEditIn(200).restCer, 0.0)
        assertFalse(oneEditIn(199).ok)
    }
}
