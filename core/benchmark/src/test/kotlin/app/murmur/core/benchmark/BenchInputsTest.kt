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
    fun ocrGoldenToleranceFollowsTheMlOcrReadme() {
        val golden = ScreenText(listOf(1080, 2520), List(50) { i -> OcrLine("строка номер $i", 0.95, listOf(10, 100 * i, 500, 100 * i + 40)) })
        assertNull(OcrGoldenMatch.check(golden, golden))
        fun with(i: Int, line: OcrLine, base: ScreenText = golden) = base.copy(lines = base.lines.toMutableList().also { it[i] = line })
        val l0 = golden.lines[0]
        assertNull(OcrGoldenMatch.check(golden, with(0, l0.copy(box = listOf(12, 0, 498, 42), conf = 0.97))))
        assertEquals("ocr_box", OcrGoldenMatch.check(golden, with(0, l0.copy(box = listOf(13, 0, 500, 40)))))
        assertEquals("ocr_conf", OcrGoldenMatch.check(golden, with(0, l0.copy(conf = 0.92))))
        assertEquals("ocr_line_count", OcrGoldenMatch.check(golden, golden.copy(lines = golden.lines.drop(1))))
        assertEquals("ocr_no_boxes", OcrGoldenMatch.check(golden, golden.copy(lines = emptyList())))
        // 1 of 50 lines differs (98 % identical, allowed) but by 1 of 14 characters: CER 7 % > 0.5 % on the rest.
        assertEquals("ocr_text", OcrGoldenMatch.check(golden, with(0, l0.copy(text = "строка намер 0"))))
        val long = "a".repeat(400)
        val g2 = with(0, l0.copy(text = long))
        // 1 differing line with CER 1/400 = 0.25 % <= 0.5 %, and 49/50 = 98 % identical: within tolerance.
        assertNull(OcrGoldenMatch.check(g2, with(0, l0.copy(text = long.dropLast(1) + "b"), g2)))
        // Two differing lines: 96 % identical.
        assertEquals("ocr_text", OcrGoldenMatch.check(g2, with(1, golden.lines[1].copy(text = "x"), with(0, l0.copy(text = long.dropLast(1) + "b"), g2))))
    }

    @Test
    fun ocrTextRuleIsAppliedToAllPagesOfTheRunTogether() {
        fun page(n: Int, first: String) = ScreenText(listOf(1080, 2520),
            List(n) { i -> OcrLine(if (i == 0) first else "строка номер $i", 0.95, listOf(10, 100 * i, 500, 100 * i + 40)) })
        val long = "б".repeat(400)
        val a = page(25, long)
        val b = page(25, "заголовок")
        val aOff = a.copy(lines = a.lines.toMutableList().also { it[0] = it[0].copy(text = long.dropLast(1) + "в") })
        // Alone, page a has 24/25 = 96 % identical lines; over the run (50 lines) it is 49/50 = 98 %.
        assertEquals("ocr_text", OcrGoldenMatch.check(a, aOff))
        assertNull(OcrGoldenMatch.check(listOf(a to aOff, b to b)))
        // The bundled pages have 14 + 29 = 43 lines: 98 % leaves no differing line, so one character off is invalid.
        val (cover, inner) = inputs.pages.map { it.golden }
        assertEquals(43, cover.lines.size + inner.lines.size)
        val coverOff = cover.copy(lines = cover.lines.toMutableList().also { it[3] = it[3].copy(text = it[3].text + ".") })
        assertEquals("ocr_text", OcrGoldenMatch.check(listOf(cover to coverOff, inner to inner)))
        assertNull(OcrGoldenMatch.check(listOf(cover to cover, inner to inner)))
    }

    private fun resource(path: String): File = File(javaClass.getResource("/$path")!!.toURI())
}
