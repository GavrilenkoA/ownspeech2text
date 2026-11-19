package app.murmur.ui.design

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    /**
     * Geometry, element by element, against the prototype's own SVG elements: every generated path traces the same
     * outline as its source element. rect: every flattened point lies on the rounded rectangle (x, y, width, height,
     * rx) and the bounds are the rectangle's; circle / ellipse: every point lies on the ellipse and the bounds are
     * its box; path: the bounds of the prototype's d (parsed by Compose) equal the generated path's. Also checks fill
     * vs stroke per element, the element opacity, and the non-zero fill rule (SVG default; the prototype sets none).
     */
    @Test
    fun geometryMatchesThePrototypeElements() {
        val tol = 0.01
        val failures = mutableListOf<String>()
        val elements = DesignSource.iconElements
        assertEquals(DesignSource.iconNames, elements.keys.toList())
        for ((name, els) in elements) {
            val paths = MurmurIcons.all.getValue(name).paths()
            if (paths.size != els.size) { failures += "$name: ${paths.size} paths for ${els.size} elements"; continue }
            els.zip(paths).forEachIndexed { i, (el, p) ->
                val where = "$name[$i] <${el.tag}>"
                val pts = PathGeometry.flatten(p.pathData)
                val b = pts.bounds()
                if (p.pathFillType != PathFillType.NonZero) failures += "$where fill type ${p.pathFillType}"
                val filled = el.attrs["class"]?.split(' ')?.contains("f") == true
                if (filled != (p.fill != null)) failures += "$where filled=${p.fill != null}, prototype class=${el.attrs["class"]}"
                val alpha = el.attrs["opacity"]?.toFloat() ?: 1f
                val actualAlpha = if (p.fill != null) p.fillAlpha else p.strokeAlpha
                if (abs(alpha - actualAlpha) > 1e-6f) failures += "$where alpha $actualAlpha, prototype $alpha"
                when (el.tag) {
                    "rect" -> {
                        val x = el.num("x", 0.0); val y = el.num("y", 0.0)
                        val w = el.num("width"); val h = el.num("height")
                        val r = minOf(el.attrs["rx"]?.toDouble() ?: el.attrs["ry"]?.toDouble() ?: 0.0, w / 2, h / 2)
                        val expected = Bounds(x, y, x + w, y + h)
                        if (!b.near(expected, tol)) failures += "$where bounds $b, prototype $expected"
                        val off = pts.maxOf { PathGeometry.distanceToRoundRect(it, x, y, w, h, r) }
                        if (off > tol) failures += "$where leaves the rounded rect by $off"
                    }
                    "circle", "ellipse" -> {
                        val cx = el.num("cx", 0.0); val cy = el.num("cy", 0.0)
                        val rx = if (el.tag == "circle") el.num("r") else el.num("rx")
                        val ry = if (el.tag == "circle") el.num("r") else el.num("ry")
                        val expected = Bounds(cx - rx, cy - ry, cx + rx, cy + ry)
                        if (!b.near(expected, tol)) failures += "$where bounds $b, prototype $expected"
                        val off = pts.maxOf { PathGeometry.ellipseError(it, cx, cy, rx, ry) }
                        if (off > tol) failures += "$where leaves the ellipse by $off"
                    }
                    "path" -> {
                        val expected = PathGeometry.flatten(addPathNodes(requireNotNull(el.attrs["d"]))).bounds()
                        if (!b.near(expected, 1e-6)) failures += "$where bounds $b, prototype d $expected"
                    }
                    else -> failures += "$where: element the icon set does not support"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The geometry check itself: a swapped rect and a shifted circle are caught; the flattener is exact on a box. */
    @Test
    fun geometryCheckDetectsWrongShapes() {
        val square = PathGeometry.flatten(addPathNodes("M2 3H10V7H2Z")).bounds()
        assertTrue(square.near(Bounds(2.0, 3.0, 10.0, 7.0), 1e-9))
        val arcCircle = PathGeometry.flatten(addPathNodes("M4 11A7 7 0 1 0 18 11A7 7 0 1 0 4 11Z"))
        assertTrue(arcCircle.bounds().near(Bounds(4.0, 4.0, 18.0, 18.0), 1e-3))
        assertTrue(arcCircle.all { PathGeometry.ellipseError(it, 11.0, 11.0, 7.0, 7.0) < 1e-6 })
        // A rect converted with width and height swapped (x=9 y=3 w=6 h=11.5 drawn as 11.5 × 6).
        val swapped = PathGeometry.flatten(addPathNodes("M9 3H20.5V9H9Z"))
        assertTrue(swapped.any { PathGeometry.distanceToRoundRect(it, 9.0, 3.0, 6.0, 11.5, 0.0) > 0.01 })
        // A circle centred 0.5 off.
        val shifted = PathGeometry.flatten(addPathNodes("M4.5 11A7 7 0 1 0 18.5 11A7 7 0 1 0 4.5 11Z"))
        assertTrue(shifted.any { PathGeometry.ellipseError(it, 11.0, 11.0, 7.0, 7.0) > 0.01 })
    }
}
