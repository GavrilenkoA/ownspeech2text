package app.murmur.feature.dashboard

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.ui.design.MurmurColors
import app.murmur.ui.design.MurmurTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * dash, dash-empty, dash-loading, dash-off and dash-dark inside the prototype frame at 1 dp = 2 px (same size as
 * `docs/design/screens/dash*--{cover,inner}.png`), compared pixel by pixel with their frames ([FrameDiff], limits in
 * [FRAME_LIMITS]) and written to `<externalFiles>/dash-shots/`. Every state also checks: clickables ≥ 48 × 48 dp (at
 * the device's real density, outside the frame), no text overflow, and, where the chart is shown, its full
 * description and the bar colours (today accent, the others surface3).
 */
@RunWith(AndroidJUnit4::class)
class DashboardScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wide = context.resources.configuration.screenWidthDp >= 600
    private val device = if (wide) Device.Inner else Device.Cover
    private val suffix = if (wide) "inner" else "cover"

    @Test
    fun dash() = shot("dash", FrameData.deps())

    @Test
    fun dashDark() = shot("dash-dark", FrameData.deps(), dark = true)

    @Test
    fun dashEmpty() = shot("dash-empty", FrameData.deps(metrics = FrameData.store(emptyList()), calculator = DefaultStatsCalculator()))

    @Test
    fun dashLoading() = shot("dash-loading", FrameData.deps(metrics = FrameData.neverLoads))

    @Test
    fun dashOff() = shot("dash-off", FrameData.deps(connected = false))

    /** No frame: the error state (info banner + Retry), for the design owner. */
    @Test
    fun dashError() = shot("dash-error", FrameData.deps(metrics = FrameData.broken))

    /** No frame: the real calculator on the WP-12 fixture (small numbers, 3 others, a 0-word Sunday). */
    @Test
    fun dashFixture() = shot(
        "dash-fixture",
        FrameData.deps(metrics = FrameData.store(Wp12MetricsFixture.week), calculator = DefaultStatsCalculator()),
    )

    private fun shot(name: String, deps: DashboardDeps, dark: Boolean = false) {
        // One composition per test (the rule allows one setContent): first the 2x frame for the screenshot, then
        // the screen alone at the device's real density for the touch-target check.
        var framed by mutableStateOf(true)
        var colors: MurmurColors? = null
        rule.setContent {
            if (framed) {
                DashboardFrame(device, dark) {
                    colors = MurmurTheme.colors
                    DashboardScreen(deps, onTryDictation = {})
                }
            } else {
                MurmurTheme(darkTheme = dark) { DashboardScreen(deps, onTryDictation = {}) }
            }
        }
        rule.waitForIdle()
        val file = "$name--$suffix" + if (dark) "--dark" else ""
        val bmp = capture()
        save(bmp, file)
        compareWithFrame(file, bmp)
        assertNoTextOverflow(name)
        val chart = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, DashboardTags.CHART)).fetchSemanticsNodes()
        if (chart.isNotEmpty()) {
            val cd = chart.single().config[SemanticsProperties.ContentDescription].joinToString()
            Log.i(TAG, "$name chart: $cd")
            if (deps.stats === FrameData.fixedCalculator) {
                assertEquals(
                    "Words per day, 11,374 in total: Thursday 2,140, Friday 1,860, Saturday 420, Sunday 310, Monday 2,950, " +
                        "Tuesday 2,410, today 1,284.",
                    cd,
                )
            } else {
                assertTrue(cd, cd.startsWith("Words per day, "))
            }
            assertBarColours(name, bmp, chart.single(), requireNotNull(colors))
        }
        rule.runOnIdle { framed = false }
        rule.waitForIdle()
        rule.assertTouchTargets(name, context.resources.displayMetrics.density)
    }

    private fun capture(): Bitmap {
        val bmp: Bitmap = rule.onNodeWithTag(FRAME_TAG).captureToImage().asAndroidBitmap()
        assertEquals("frame width px", device.width * 2, bmp.width)
        assertEquals("frame height px", device.height * 2, bmp.height)
        return bmp
    }

    private fun save(bmp: Bitmap, name: String): File {
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "dash-shots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$name: ${bmp.width}x${bmp.height} -> $file")
        return file
    }

    /**
     * The screenshot against docs/design/screens/[name].png: the share of differing pixels may not exceed the
     * state's limit. What remains at the limits is text rasterisation (the frames come from a browser) and the
     * letter tiles (the frames use made-up colours); a moved section, a changed colour or a different line break adds
     * whole glyph rows and bars and fails. A failing comparison leaves `<name>-heat.png` next to the shot.
     */
    private fun compareWithFrame(name: String, bmp: Bitmap) {
        val frame = FrameDiff.frame(name) ?: run {
            assertTrue("$name has a frame in docs/design/screens but no limit", name !in FRAME_LIMITS)
            Log.i(TAG, "$name: no design frame")
            return
        }
        val limit = requireNotNull(FRAME_LIMITS[name]) { "no diff limit for $name" }
        val r = FrameDiff.compare(bmp, frame)
        Log.i(TAG, "$name: %.2f %% of pixels differ from the frame (limit %.2f %%)".format(r.percent, limit))
        if (r.percent > limit) save(r.heat, "$name-heat")
        assertTrue("$name differs from its frame in %.2f %% of pixels (limit %.2f %%)".format(r.percent, limit), r.percent <= limit)
    }

    /**
     * DESIGN 6.2: today's bar (the last column) is the accent, the six others surface3. Each column is scanned at its
     * centre inside the chart's bounds in the framed screenshot.
     */
    private fun assertBarColours(name: String, bmp: Bitmap, chart: SemanticsNode, c: MurmurColors) {
        val frameNode = rule.onNodeWithTag(FRAME_TAG).fetchSemanticsNode()
        val b = chart.boundsInRoot.translate(-frameNode.boundsInRoot.left, -frameNode.boundsInRoot.top)
        val gap = 20f
        val col = (b.width - gap * 6) / 7
        val top = b.top.toInt().coerceAtLeast(0)
        // The day labels hang 10 dp below the chart's reported bounds (ChartSpec.bottomOverhang).
        val bottom = (b.bottom + 20).toInt().coerceAtMost(bmp.height)
        for (i in 0 until 7) {
            val x = (b.left + i * (col + gap) + col / 2).toInt()
            val column = (top until bottom).map { y -> bmp.getPixel(x, y) }
            val accent = column.count { near(it, c.accent) }
            val bar = column.count { near(it, c.surface3) }
            if (i == 6) {
                assertTrue("$name: today's bar is the accent ($accent px)", accent >= 6)
            } else {
                assertEquals("$name: no accent in column $i", 0, accent)
                assertTrue("$name: column $i is surface3 ($bar px)", bar >= 6)
            }
        }
    }

    private fun near(argb: Int, color: Color): Boolean {
        val t = color.toArgb()
        return abs((argb shr 16 and 0xFF) - (t shr 16 and 0xFF)) <= 4 &&
            abs((argb shr 8 and 0xFF) - (t shr 8 and 0xFF)) <= 4 &&
            abs((argb and 0xFF) - (t and 0xFF)) <= 4
    }

    private fun assertNoTextOverflow(name: String) {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val bad = mutableListOf<String>()
        rule.runOnIdle {
            for (n in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val r = results.firstOrNull() ?: continue
                val overflow = r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
                    (0 until r.lineCount).any { i -> r.isLineEllipsized(i) || r.getLineRight(i) - r.getLineLeft(i) > r.size.width + 1f }
                if (overflow) bad += describe(n, 2f)
            }
        }
        Log.i(TAG, "$name: ${nodes.size} texts checked")
        assertTrue("text overflow in $name:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private companion object {
        const val TAG = "DashboardShot"

        /**
         * Per-frame limits in % of the app area's pixels: the share measured on the cover and inner AVDs (2026-11-19:
         * cover 3.04 / 3.12 / 2.46 / 0.63 / 3.80, inner 2.54 / 2.60 / 1.48 / 0.27) plus 0.5 percentage points. What
         * is left is text rasterisation and the letter tiles; a moved section adds whole rows of glyphs. Bar colours
         * are checked on their own (assertBarColours), since one bar is smaller than the margin.
         */
        val FRAME_LIMITS = mapOf(
            "dash--cover" to 3.54f,
            "dash-dark--cover--dark" to 3.62f,
            "dash-empty--cover" to 2.96f,
            "dash-loading--cover" to 1.13f,
            "dash-off--cover" to 4.30f,
            "dash--inner" to 3.04f,
            "dash-dark--inner--dark" to 3.10f,
            "dash-empty--inner" to 1.98f,
            "dash-loading--inner" to 0.77f,
        )
    }
}
