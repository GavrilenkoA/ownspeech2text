package app.murmur.ui.design

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconsTest {
    private fun ImageVector.paths(): List<VectorPath> {
        fun walk(n: VectorNode): List<VectorPath> = when (n) {
            is VectorPath -> listOf(n)
            is VectorGroup -> n.flatMap { walk(it) }
        }
        return walk(root)
    }

    @Test
    fun everyPrototypeIconExistsAsMurmurIconsMember() {
        val names = DesignSource.iconNames
        // DESIGN 3.4 counts 45 + the mark; the prototype map today holds more (keyboard glyphs, status bar).
        assertTrue("prototype icons: ${names.size}", names.size >= 46)
        assertTrue("mark" in names)
        assertEquals(names, MurmurIcons.all.keys.toList())
        val getters = MurmurIcons::class.java.methods.map { it.name }.toSet()
        for (n in names) {
            assertTrue("MurmurIcons.$n getter", "get" + n.replaceFirstChar { it.uppercase() } in getters)
        }
    }

    @Test
    fun iconsAreOn24dpGridWithDesignStrokes() {
        for ((name, icon) in MurmurIcons.all) {
            assertEquals(name, 24f, icon.viewportWidth, 0f)
            assertEquals(name, 24f, icon.viewportHeight, 0f)
            assertEquals(name, 24f, icon.defaultWidth.value, 0f)
            val paths = icon.paths()
            assertTrue("$name has paths", paths.isNotEmpty())
            for (p in paths) {
                assertTrue("$name path data", p.pathData.isNotEmpty())
                if (p.stroke != null) {
                    assertNull("$name stroked path has no fill", p.fill)
                    assertEquals(name, if (name == "mark") 2.25f else 1.75f, p.strokeLineWidth, 0f)
                    assertEquals(name, StrokeCap.Round, p.strokeLineCap)
                    assertEquals(name, StrokeJoin.Round, p.strokeLineJoin)
                } else {
                    assertNotNull("$name filled path", p.fill)
                }
            }
        }
    }

    @Test
    fun filledFormsOnlyWhereThePrototypeFillsThem() {
        // DESIGN 3.4: filled forms only for play/pause/stop and the "more" dots (plus the prototype's glyph details).
        val fullyFilled = MurmurIcons.all.filter { (_, v) -> v.paths().all { it.fill != null } }.keys
        assertEquals(setOf("stop", "play", "pause", "more", "signal"), fullyFilled)
    }

    @Test
    fun boldVariantUsesMarkStroke() {
        val p = MurmurIcons.bold("check").paths().single()
        assertEquals(2.25f, p.strokeLineWidth, 0f)
        assertTrue(MurmurIcons.bold("check") === MurmurIcons.bold("check"))
    }

    @Test
    fun shapesAreConvertedToClosedPaths() {
        // rect / circle / ellipse elements become path data; the converted outline must close (Z).
        val mic = MurmurIcons.mic.paths()
        assertEquals(2, mic.size)
        val storage = MurmurIcons.storage.paths()
        assertEquals(3, storage.size)
        assertTrue(storage.first().pathData.last() is androidx.compose.ui.graphics.vector.PathNode.Close)
    }
}
