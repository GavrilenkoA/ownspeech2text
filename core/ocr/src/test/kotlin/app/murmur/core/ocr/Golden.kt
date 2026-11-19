package app.murmur.core.ocr

import org.junit.Assert.assertTrue
import java.io.File
import javax.imageio.ImageIO

/** ml/ocr/golden read in place (owned by ml/ocr; never copied), with the PNG decoded by ImageIO. */
object Golden {
    val root = File(System.getProperty("murmur.repoRoot") ?: ".")
    val dir = File(root, "ml/ocr/golden")

    fun all(): List<OcrGolden> {
        val files = dir.listFiles { f -> f.name.matches(Regex("\\d{3}\\.json")) }?.sortedBy { it.name }.orEmpty()
        check(files.isNotEmpty()) { "ml/ocr/golden not found under $root" }
        return files.map { OcrGolden.parse(it.nameWithoutExtension, it.readText()) }
    }

    fun png(g: OcrGolden) = File(dir, g.name + ".png")

    /** The PNG as RGB8 ints (no colour management; the goldens are plain 8-bit RGB without colour chunks). */
    fun image(g: OcrGolden): ScreenImage {
        val img = ImageIO.read(png(g))
        return g.image(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    fun assertWithin(suite: OcrTolerance.Suite): String {
        assertTrue(suite.toString(), suite.ok)
        return suite.toString()
    }
}
