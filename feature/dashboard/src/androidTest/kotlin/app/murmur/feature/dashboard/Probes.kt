package app.murmur.feature.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Pixel comparison of a framed screenshot with its design frame (`docs/design/screens/<name>.png`, packaged as the
 * androidTest asset `frames/<name>.png` by the module's build). Both are 1 dp = 2 px. The status bar (36 dp) and the
 * gesture bar (20 dp) are masked: the frames draw a fake clock and handle there. A pixel differs when one of its
 * channels is more than [LEVEL] levels apart.
 */
object FrameDiff {
    const val LEVEL = 40
    const val STATUS_PX = 72
    const val GESTURE_PX = 40

    class Result(val share: Float, val heat: Bitmap) {
        val percent: Float get() = share * 100f
    }

    /** The frame [name] (without .png), or null when the design has no frame for it. */
    fun frame(name: String): Bitmap? = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open("frames/$name.png").use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
    } catch (e: FileNotFoundException) {
        null
    }

    /** Share of differing pixels in the app area, and a heat map (shot dimmed, differing pixels red). */
    fun compare(shot: Bitmap, frame: Bitmap): Result {
        require(shot.width == frame.width && shot.height == frame.height) {
            "shot ${shot.width}x${shot.height} vs frame ${frame.width}x${frame.height}"
        }
        val w = shot.width
        val h = shot.height
        val a = IntArray(w * h).also { shot.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
        val heat = IntArray(w * h)
        var differing = 0
        var counted = 0
        for (i in a.indices) {
            val y = i / w
            val p = a[i]
            val q = b[i]
            val d = max(abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)), max(abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)), abs((p and 0xFF) - (q and 0xFF))))
            val inside = y >= STATUS_PX && y < h - GESTURE_PX
            if (inside) counted++
            heat[i] = if (inside && d > LEVEL) {
                differing++
                0xFFFF0000.toInt()
            } else {
                val g = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 12 + 160
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return Result(differing.toFloat() / counted, Bitmap.createBitmap(heat, w, h, Bitmap.Config.ARGB_8888))
    }
}

/**
 * Every clickable matched by [matcher] (in every window: a sheet is a dialog) is at least 48 × 48 dp at the device's
 * real density.
 */
fun ComposeTestRule.assertTouchTargets(name: String, density: Float, matcher: SemanticsMatcher = hasClickAction()) {
    val min = floor(48 * density) - 0.5f
    val nodes = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    val small = nodes.filter { it.size.width < min || it.size.height < min || it.touchBoundsInRoot.height < min }
        .map { describe(it, density) }
    org.junit.Assert.assertTrue("no clickables found in $name", nodes.isNotEmpty())
    org.junit.Assert.assertTrue("clickables below 48 dp in $name:\n" + small.joinToString("\n"), small.isEmpty())
}

fun describe(n: SemanticsNode, density: Float): String {
    val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: n.config.getOrNull(SemanticsProperties.TestTag)
    return "#${n.id} '$text' ${n.size.width / density}x${n.size.height / density} dp"
}
