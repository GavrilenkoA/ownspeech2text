package app.murmur.core.benchmark

import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

class BenchInputsTest {
    private val inputs = BenchRig.INPUTS

    @Test
    fun clipsAreTenToTwelveSecondsOfSpeechWithReferences() {
        assertEquals(listOf("ru", "en", "mixed"), inputs.clips.map { it.id })
        assertEquals(listOf(setOf("ru"), setOf("en"), setOf("ru", "en")), inputs.clips.map { it.languages })
        assertEquals(listOf(BenchStep.SAMPLE_RU, BenchStep.SAMPLE_EN, BenchStep.SAMPLE_MIXED), inputs.clips.map { it.step })
        for (c in inputs.clips) {
            assertTrue("${c.id}: ${c.audioMs} ms", c.audioMs in 10_000..12_100)
            assertTrue(c.reference.isNotBlank())
            assertTrue("${c.id} is not silent", c.pcm16k.maxOf { kotlin.math.abs(it.toInt()) } > 5_000)
        }
        assertTrue(inputs.clips[2].reference.contains("Как только") && inputs.clips[2].reference.contains("Byzantine"))
        assertTrue(inputs.clips[0].runsOn(listOf("ru")) && !inputs.clips[1].runsOn(listOf("ru")) && !inputs.clips[2].runsOn(listOf("ru")))
    }

    @Test
    fun clipFilesMatchTheChecksumsInSources() {
        val sources = resource("bench/SOURCES.md").readText()
        for (id in listOf("ru", "en", "mixed")) {
            val sha = MessageDigest.getInstance("SHA-256").digest(resource("bench/asr/$id.pcm").readBytes()).joinToString("") { "%02x".format(it) }
            assertTrue("$id.pcm sha256 $sha not recorded in SOURCES.md", sources.contains("`$id.pcm` `$sha`"))
        }
        for (page in listOf("cover", "inner")) {
            val sha = MessageDigest.getInstance("SHA-256").digest(resource("bench/ocr/$page.png").readBytes()).joinToString("") { "%02x".format(it) }
            assertTrue("$page.png sha256 not recorded", sources.contains(sha))
        }
    }

    @Test
    fun bundledResourcesStayUnderThreeMegabytes() {
        val total = resource("bench").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("bench resources are $total bytes", total < 3 * 1024 * 1024)
    }

    @Test
    fun pngDecoderMatchesImageIoPixelForPixel() {
        for (page in inputs.pages) {
            val file = resource("bench/ocr/${page.id}.png")
            val image = page.image()
            val reference = ImageIO.read(file)
            assertEquals(page.golden.size, listOf(image.width, image.height))
            assertEquals(reference.width, image.width)
            assertEquals(reference.height, image.height)
            val expected = reference.getRGB(0, 0, reference.width, reference.height, null, 0, reference.width)
            assertArrayEquals("pixels of ${page.id}", expected, image.argb)
        }
        assertEquals(listOf(1080, 2520), inputs.pages.single { it.id == "cover" }.golden.size)
        assertEquals(listOf(1968, 2184), inputs.pages.single { it.id == "inner" }.golden.size)
        // The capture's exclusion rectangles travel with the image (status bar first in both goldens).
        assertEquals(Rect(0, 0, 1080, 79), inputs.pages.single { it.id == "cover" }.image().exclude.first())
    }

    @Test
    fun goldenLinesAreThePipelineOutputOfTheirPages() {
        for (page in inputs.pages) {
            assertTrue(page.golden.lines.size >= 10)
            assertNull(OcrGoldenMatch.check(page.golden, page.golden))
        }
    }

    @Test
    fun cleanupCaseIsASixtyWordRussianDictationWithAChatContext() {
        val case = inputs.cleanupCase
        assertEquals(60, case.transcript.split(' ').size)
        assertTrue(case.transcript.all { it == ' ' || it in 'а'..'я' || it == 'ё' })
        assertEquals("org.telegram.messenger", case.context.app.pkg)
        val screenChars = case.context.screen!!.lines.sumOf { it.text.length }
        assertTrue("screen text $screenChars chars", screenChars in 300..700) // about 200 tokens rendered
    }

    @Test
    fun ocrGoldenToleranceFollowsTheMlOcrReadmeForSmallSamples() {
        val golden = ScreenText(listOf(1080, 2520), List(50) { i -> OcrLine("строка номер $i", 0.95, listOf(10, 100 * i, 500, 100 * i + 40)) })
        assertNull(OcrGoldenMatch.check(golden, golden))
        fun with(i: Int, line: OcrLine, base: ScreenText = golden) = base.copy(lines = base.lines.toMutableList().also { it[i] = line })
        val l0 = golden.lines[0]
        assertNull(OcrGoldenMatch.check(golden, with(0, l0.copy(box = listOf(12, 0, 498, 42), conf = 0.97))))
        assertEquals("ocr_box", OcrGoldenMatch.check(golden, with(0, l0.copy(box = listOf(13, 0, 500, 40)))))
        assertEquals("ocr_conf", OcrGoldenMatch.check(golden, with(0, l0.copy(conf = 0.92))))
        assertEquals("ocr_line_count", OcrGoldenMatch.check(golden, golden.copy(lines = golden.lines.drop(1))))
        assertEquals("ocr_no_boxes", OcrGoldenMatch.check(golden, golden.copy(lines = emptyList())))
        // ADR-080: 1 of 50 lines (ceil(2 % x 50) = 1) may differ; 1 edit over the run's ~720 code points is <= 0.5 %.
        assertEquals(1, OcrGoldenMatch.allowedDifferent(50))
        assertNull(OcrGoldenMatch.check(golden, with(0, l0.copy(text = "строка намер 0"))))
        // One line rewritten: within the line allowance but 20 edits / ~720 code points = 2.8 % > 0.5 %.
        assertEquals("ocr_text", OcrGoldenMatch.check(golden, with(0, l0.copy(text = "совсем другая строка"))))
        val long = "a".repeat(400)
        val g2 = with(0, l0.copy(text = long))
        assertNull(OcrGoldenMatch.check(g2, with(0, l0.copy(text = long.dropLast(1) + "b"), g2)))
        // Two differing lines: more than ceil(2 % x 50) = 1.
        assertEquals("ocr_text", OcrGoldenMatch.check(g2, with(1, golden.lines[1].copy(text = "строка номер 1."), with(0, l0.copy(text = long.dropLast(1) + "b"), g2))))
    }

    @Test
    fun ocrTextRuleIsAppliedToAllPagesOfTheRunTogether() {
        fun page(n: Int, first: String) = ScreenText(listOf(1080, 2520),
            List(n) { i -> OcrLine(if (i == 0) first else "строка номер $i", 0.95, listOf(10, 100 * i, 500, 100 * i + 40)) })
        val long = "б".repeat(400)
        val a = page(25, long)
        val b = page(25, "заголовок")
        val aOff = a.copy(lines = a.lines.toMutableList().also { it[0] = it[0].copy(text = long.dropLast(1) + "в") })
        val bOff = b.copy(lines = b.lines.toMutableList().also { it[0] = it[0].copy(text = "заголовак") })
        // Each page alone may have one differing line (ceil(2 % x 25) = 1); the run of 50 lines may have one, not two.
        assertNull(OcrGoldenMatch.check(a, aOff))
        assertNull(OcrGoldenMatch.check(b, bOff))
        assertNull(OcrGoldenMatch.check(listOf(a to aOff, b to b)))
        assertEquals("ocr_text", OcrGoldenMatch.check(listOf(a to aOff, b to bOff)))
        // The bundled pages have 14 + 29 = 43 lines and 691 code points: one character off on one line is still valid
        // (a rounding difference on the NPU, ADR-080); a second differing line, or one line rewritten, is not.
        val (cover, inner) = inputs.pages.map { it.golden }
        assertEquals(43, cover.lines.size + inner.lines.size)
        fun off(p: ScreenText, i: Int, text: String) = p.copy(lines = p.lines.toMutableList().also { it[i] = it[i].copy(text = text) })
        val coverOff = off(cover, 3, cover.lines[3].text + ".")
        assertNull(OcrGoldenMatch.check(listOf(cover to coverOff, inner to inner)))
        assertEquals("ocr_text", OcrGoldenMatch.check(listOf(cover to coverOff, inner to off(inner, 5, inner.lines[5].text + "."))))
        assertEquals("ocr_text", OcrGoldenMatch.check(listOf(cover to cover, inner to off(inner, 2, "Совсем не то"))))
        assertNull(OcrGoldenMatch.check(listOf(cover to cover, inner to inner)))
    }

    private fun resource(path: String): File = File(javaClass.getResource("/$path")!!.toURI())
}
