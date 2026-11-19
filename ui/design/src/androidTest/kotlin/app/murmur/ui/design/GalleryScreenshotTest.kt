package app.murmur.ui.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.ui.design.gallery.ComponentGallery
import app.murmur.ui.design.gallery.GALLERY_DISABLED_CHIP_TAG
import app.murmur.ui.design.gallery.GALLERY_ENABLED_CHIP_TAG
import app.murmur.ui.design.gallery.GALLERY_PRESSED_TAG
import app.murmur.ui.design.gallery.GALLERY_PRIMARY_TAG
import app.murmur.ui.design.gallery.GALLERY_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * WP-01 acceptance on the cover and the inner AVD: renders [ComponentGallery] light and dark, writes a stitched
 * full-length screenshot to `<externalFiles>/gallery-{cover,inner}-{light,dark}.png` (pulled into
 * `.cache/spikes/wp-01/`), asserts every clickable node is at least 48 × 48 dp (layout size, stricter than the
 * touch bounds Compose would extend) and that no text overflows (`TextLayoutResult.hasVisualOverflow`).
 */
@RunWith(AndroidJUnit4::class)
class GalleryScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = context.resources.displayMetrics.density
    private val device = if (context.resources.configuration.screenWidthDp >= 600) "inner" else "cover"

    @Test
    fun galleryLight() = check(dark = false)

    @Test
    fun galleryDark() = check(dark = true)

    /**
     * DESIGN 8: the UI works up to 200 % font scale. Same checks at fontScale 2 (layout does not depend on the theme,
     * so light only): nothing is cut or ellipsized, every clickable keeps 48 dp, every tab is named.
     */
    @Test
    fun galleryLightFontScale200() = check(dark = false, fontScale = 2f)

    /** The two checks below must catch real violations, or a green gallery run would prove nothing. */
    @Test
    fun checksDetectViolations() {
        rule.setContent {
            MurmurTheme {
                Column {
                    Text("A sentence far too long for forty dp", Modifier.width(40.dp).testTag("ellipsized"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Clipped without wrapping", Modifier.width(40.dp).testTag("clipped"), softWrap = false, overflow = TextOverflow.Clip)
                    Text("Two lines\nin one", Modifier.height(10.dp).testTag("short"))
                    Text("Fits", Modifier.testTag("fits"))
                    Box(Modifier.size(24.dp).clickable {}.testTag("small"))
                }
            }
        }
        fun layout(tag: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.first()
        }
        assertTrue(visualOverflow(layout("ellipsized")))
        assertTrue(visualOverflow(layout("clipped")))
        assertTrue(visualOverflow(layout("short")))
        assertFalse(visualOverflow(layout("fits")))
        val small = rule.onNodeWithTag("small").fetchSemanticsNode()
        assertTrue(small.size.width < floor(Sizes.touchTargetMin.value * density) - 0.5f)
    }

    private fun check(dark: Boolean, fontScale: Float = 1f) {
        lateinit var scroll: ScrollState
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MurmurTheme(darkTheme = dark) {
                    scroll = rememberScrollState()
                    ComponentGallery(scrollState = scroll)
                }
            }
        }
        rule.waitForIdle()
        val name = "gallery-$device-${if (dark) "dark" else "light"}" + if (fontScale != 1f) "-fs${(fontScale * 100).toInt()}" else ""
        assertTouchTargets(name)
        assertNoTextOverflow(name)
        assertNamedControls(name)
        assertTouchBoundsWhileScrolling(scroll, name)
        val file = captureFullLength(scroll, name)
        assertTrue("$file written", file.length() > 0)
        assertPressedAndDisabledStates(name, if (dark) TokenColors.dark else TokenColors.light)
    }

    /** TalkBack names every tab (nav destinations, segments) and every button: text or a content description. */
    private fun assertNamedControls(name: String) {
        val roles = setOf(Role.Tab, Role.Button, Role.DropdownList)
        val nodes = rule.onAllNodes(SemanticsMatcher("has a role") { it.config.getOrNull(SemanticsProperties.Role) in roles })
            .fetchSemanticsNodes()
        val tabs = nodes.count { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertTrue("tabs found in $name", tabs >= 13)
        val unnamed = nodes.filter { n ->
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            text.isBlank() && cd.isBlank()
        }.map { "#${it.id} ${it.config.getOrNull(SemanticsProperties.Role)} ${it.size.width / density}x${it.size.height / density} dp" }
        Log.i(TAG, "$name: ${nodes.size} tabs and buttons named ($tabs tabs)")
        assertTrue("unnamed controls in $name:\n" + unnamed.joinToString("\n"), unnamed.isEmpty())
    }

    /**
     * The gallery's static states are really drawn: the held "Pressed" primary button is accentPressed while the
     * plain one is accent, and the disabled chip is dimmed (its strongest pixel is far closer to the background).
     */
    private fun assertPressedAndDisabledStates(name: String, c: MurmurColors) {
        fun fill(tag: String): Int {
            rule.onNodeWithTag(tag).performScrollTo()
            rule.waitForIdle()
            val img = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            return img.getPixel((8 * density).toInt(), img.height / 2)
        }
        val plain = fill(GALLERY_PRIMARY_TAG)
        val pressed = fill(GALLERY_PRESSED_TAG)
        Log.i(TAG, "$name: primary #%08X, pressed #%08X".format(plain, pressed))
        assertColor("$name primary", c.accent.toArgb(), plain)
        assertColor("$name pressed primary", c.accentPressed.toArgb(), pressed)
        fun strongest(tag: String): Int {
            rule.onNodeWithTag(tag).performScrollTo()
            rule.waitForIdle()
            val img = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            val bg = c.bg.toArgb()
            var best = 0
            for (y in 0 until img.height) for (x in 0 until img.width) best = maxOf(best, channelDistance(img.getPixel(x, y), bg))
            return best
        }
        val enabled = strongest(GALLERY_ENABLED_CHIP_TAG)
        val disabled = strongest(GALLERY_DISABLED_CHIP_TAG)
        Log.i(TAG, "$name: chip contrast to bg enabled $enabled, disabled $disabled")
        assertTrue("$name disabled chip dimmed ($disabled vs $enabled)", disabled < enabled * 0.6)
    }

    private fun channelDistance(a: Int, b: Int): Int = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
    )

    private fun assertColor(what: String, expected: Int, actual: Int) =
        assertTrue("$what: expected #%08X, got #%08X".format(expected, actual), channelDistance(expected, actual) <= 4)

    private fun assertTouchTargets(name: String) {
        val min = floor(Sizes.touchTargetMin.value * density) - 0.5f
        val nodes = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("clickables found", nodes.size > 60)
        val small = nodes.filter { it.size.width < min || it.size.height < min }.map { describe(it) }
        Log.i(TAG, "$name: ${nodes.size} clickable nodes, min layout ${nodes.minOf { min(it.size.width, it.size.height) } / density} dp")
        assertTrue("clickables below 48 dp in $name:\n" + small.joinToString("\n"), small.isEmpty())

    }

    /**
     * Touch bounds (what the hit test uses) are clipped by the scroll viewport, so they are checked page by page for
     * the clickables fully on screen. Returns the ids checked.
     */
    private fun checkVisibleTouchBounds(name: String, checked: MutableSet<Int>) {
        val min = floor(Sizes.touchTargetMin.value * density) - 0.5f
        val visible = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().filter {
            abs(it.boundsInRoot.width - it.size.width) < 1f && abs(it.boundsInRoot.height - it.size.height) < 1f
        }
        val small = visible.filter { it.touchBoundsInRoot.width < min || it.touchBoundsInRoot.height < min }.map { describe(it) }
        assertTrue("touch bounds below 48 dp in $name:\n" + small.joinToString("\n"), small.isEmpty())
        visible.forEach { checked += it.id }
    }

    private fun assertNoTextOverflow(name: String) {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("text nodes found", nodes.size > 100)
        val overflowing = mutableListOf<String>()
        var checked = 0
        rule.runOnIdle {
            for (n in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                val ok = n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) ?: false
                if (!ok || results.isEmpty()) continue
                checked++
                if (visualOverflow(results.first())) overflowing += describe(n)
            }
        }
        Log.i(TAG, "$name: $checked text layouts checked")
        assertEquals("text layouts checked", nodes.size, checked)
        assertTrue("text overflows in $name:\n" + overflowing.joinToString("\n"), overflowing.isEmpty())
    }

    /**
     * `hasVisualOverflow` as the semantics action reports it cannot be used as is: for wrap-content text the action
     * returns a MultiParagraph laid out at the parent's max width while `size` is the wrapped width, so
     * `didOverflowWidth` (size.width < multiParagraph.width) is true for every short text (seen on API 36, Compose
     * 1.12.1: "Type" 82 px wide, paragraph 974 px). This applies the same definition per line: a text overflows when
     * it is cut vertically, exceeds maxLines, is ellipsized, or a line is wider than the laid-out text.
     */
    private fun visualOverflow(r: TextLayoutResult): Boolean =
        r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
            (0 until r.lineCount).any { i -> r.isLineEllipsized(i) || r.getLineRight(i) - r.getLineLeft(i) > r.size.width + 1f }

    /** Scrolls in half-viewport steps so every clickable is fully on screen at least once. */
    private fun assertTouchBoundsWhileScrolling(scroll: ScrollState, name: String) {
        val viewport = rule.onNodeWithTag(GALLERY_TAG).fetchSemanticsNode().size.height
        val max = rule.runOnIdle { scroll.maxValue }
        val checked = mutableSetOf<Int>()
        var y = 0
        while (true) {
            rule.runOnIdle { scroll.dispatchRawDelta((y - scroll.value).toFloat()) }
            rule.waitForIdle()
            checkVisibleTouchBounds(name, checked)
            if (y >= max) break
            y = min(y + viewport / 2, max)
        }
        rule.runOnIdle { scroll.dispatchRawDelta(-scroll.value.toFloat()) }
        rule.waitForIdle()
        val all = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.id }.toSet()
        Log.i(TAG, "$name: touch bounds checked for ${checked.intersect(all).size} of ${all.size} clickables")
        assertEquals("every clickable was fully on screen once", all, checked.intersect(all))
    }

    /** Scrolls the gallery one viewport at a time and stitches the captures into one tall PNG. */
    private fun captureFullLength(scroll: ScrollState, name: String): File {
        val node = rule.onNodeWithTag(GALLERY_TAG)
        val first = node.captureToImage().asAndroidBitmap()
        val viewport = first.height
        val max = rule.runOnIdle { scroll.maxValue }
        val total = viewport + max
        val out = Bitmap.createBitmap(first.width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(first, 0f, 0f, null)
        var covered = viewport
        while (covered < total) {
            val target = min(covered, max)
            rule.runOnIdle { scroll.dispatchRawDelta((target - scroll.value).toFloat()) }
            rule.waitForIdle()
            val y = rule.runOnIdle { scroll.value }
            val page = node.captureToImage().asAndroidBitmap()
            // Rows [covered, min(y + viewport, total)) of the full gallery are rows [covered - y, …) of this page.
            val srcTop = covered - y
            val rows = min(viewport - srcTop, total - covered)
            check(srcTop in 0 until viewport && rows > 0) { "bad scroll step y=$y covered=$covered" }
            canvas.drawBitmap(page, Rect(0, srcTop, page.width, srcTop + rows), Rect(0, covered, page.width, covered + rows), null)
            covered += rows
        }
        rule.runOnIdle { scroll.dispatchRawDelta(-scroll.value.toFloat()) }
        val dir = requireNotNull(context.getExternalFilesDir(null))
        val file = File(dir, "$name.png")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$name: ${out.width}x${out.height} px (${out.width / density}x${out.height / density} dp) -> $file")
        return file
    }

    private fun describe(n: SemanticsNode): String {
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: n.children.firstNotNullOfOrNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        val at = n.positionInRoot
        return "#${n.id} '${text}' ${n.size.width / density}x${n.size.height / density} dp at ${at.x / density},${at.y / density} dp"
    }

    private companion object {
        const val TAG = "GalleryScreenshotTest"
    }
}
