package app.murmur.feature.dashboard

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.ui.design.MurmurTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.floor

/**
 * dash, dash-empty, dash-loading, dash-off and dash-dark inside the prototype frame at 1 dp = 2 px (same size as
 * `docs/design/screens/dash*--{cover,inner}.png`), written to `<externalFiles>/dash-shots/` for the pixel diff
 * (`.cache/spikes/wp-17/tools/diff.py`). Every state also checks: clickables ≥ 48 × 48 dp (at the device's real
 * density, outside the frame), no text overflow, and, where the chart is shown, its full description.
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
        rule.setContent {
            if (framed) {
                DashboardFrame(device, dark) { DashboardScreen(deps, onTryDictation = {}) }
            } else {
                MurmurTheme(darkTheme = dark) { DashboardScreen(deps, onTryDictation = {}) }
            }
        }
        rule.waitForIdle()
        val file = save("$name--$suffix" + if (dark) "--dark" else "")
        assertTrue("$file written", file.length() > 0)
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
        }
        rule.runOnIdle { framed = false }
        rule.waitForIdle()
        assertTouchTargets(name)
    }

    private fun save(name: String): File {
        val bmp: Bitmap = rule.onNodeWithTag(FRAME_TAG).captureToImage().asAndroidBitmap()
        assertEquals("frame width px", device.width * 2, bmp.width)
        assertEquals("frame height px", device.height * 2, bmp.height)
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "dash-shots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$name: ${bmp.width}x${bmp.height} -> $file")
        return file
    }

    private fun assertTouchTargets(name: String) {
        val density = context.resources.displayMetrics.density
        val min = floor(48 * density) - 0.5f
        val nodes = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        val small = nodes.filter { it.size.width < min || it.size.height < min || it.touchBoundsInRoot.height < min }.map { describe(it, density) }
        Log.i(TAG, "$name: ${nodes.size} clickables")
        assertTrue("clickables below 48 dp in $name:\n" + small.joinToString("\n"), small.isEmpty())
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

    private fun describe(n: SemanticsNode, density: Float): String {
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        return "#${n.id} '$text' ${n.size.width / density}x${n.size.height / density} dp"
    }

    private companion object {
        const val TAG = "DashboardShot"
    }
}
