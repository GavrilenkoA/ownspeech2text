package app.murmur.platform.overlay

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.TokenColors
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.store.BubbleSize
import app.murmur.platform.overlay.ui.BubbleActions
import app.murmur.platform.overlay.ui.BubbleUi
import app.murmur.platform.overlay.ui.BubbleWindowContent
import app.murmur.platform.overlay.ui.TAG_VISUAL
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * WP-03 acceptance "Level bars: fed with a synthetic levels flow, silence renders flat bars (no fake animation)".
 * Reads the bar heights back from the rendered pixels of the Listening pill.
 */
@RunWith(AndroidJUnit4::class)
class LevelBarsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val levels = MutableStateFlow(FloatArray(7))
    private val m = BubbleMetrics.of(BubbleSize.STANDARD)

    private fun render(reducedMotion: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val ui = BubbleUi(BubbleVisual.Active(DictationBubble.Listening(0L, hold = false, warn = false)), shown = true,
                rightEdge = true, metrics = m, reducedMotion = reducedMotion, talkBack = false)
            // Away from the system bars, on the bubble's usual background.
            Box(Modifier.fillMaxSize().background(TokenColors.light.bg).padding(top = 160.dp, start = 40.dp)) {
                BubbleWindowContent(ui, dark = false, levels = levels.collectAsState(), clock = { 3_000L }, actions = object : BubbleActions {
                    override fun tap(region: TouchRegion) = Unit
                    override fun hide() = Unit
                })
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    /** The Listening pill (its semantics are merged into one node, so the bars are read from the pill's pixels). */
    private fun capture(): Bitmap = rule.onNodeWithTag(TAG_VISUAL).captureToImage().asAndroidBitmap()

    /** Height in dp of the light (onAccent) run in the middle column of bar [i]; the bars start 16 dp into the pill. */
    private fun barHeightDp(b: Bitmap, i: Int, density: Float): Float {
        val x = ((m.listenPadStart + i * (m.barWidth + m.barGap) + m.barWidth / 2f) * density).toInt()
        var rows = 0
        // Only the bars' own 24 dp band around the pill's centre line (the pill's rounded ends show the page behind).
        val mid = b.height / 2
        val half = (m.barMax / 2f * density).toInt() + 1
        for (y in (mid - half).coerceAtLeast(0) until (mid + half).coerceAtMost(b.height)) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            if (r > 200 && g > 200 && bl > 200) rows++
        }
        return rows / density
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    @Test
    fun silenceIsFlatAndStill_levelsMoveTheBars() {
        render(reducedMotion = false)
        val silent1 = capture()
        val heights = (0 until 7).map { barHeightDp(silent1, it, density) }
        heights.forEach { assertEquals("silent bar heights $heights", m.barMin, it, 1f) }
        // No fake animation: a second later, with the level still zero, not one pixel changed.
        rule.mainClock.advanceTimeBy(1_000)
        val silent2 = capture()
        assertTrue("silent frames differ", silent1.sameAs(silent2))
        Shots.save("levels-silence", silent1)

        // Synthetic speech: the bars follow the meter exactly (4 + 20 * level dp).
        val speech = floatArrayOf(0f, 0.25f, 0.5f, 1f, 0.75f, 0.5f, 0.1f)
        levels.value = speech
        rule.mainClock.advanceTimeBy(100)
        val loud = capture()
        Shots.save("levels-speech", loud)
        for (i in 0 until 7) {
            val h = barHeightDp(loud, i, density)
            assertTrue("bar $i: $h dp vs ${m.barHeight(speech[i])}", abs(h - m.barHeight(speech[i])) <= 1f)
        }
        // Back to silence: flat again, identical to the first silent frame.
        levels.value = FloatArray(7)
        rule.mainClock.advanceTimeBy(100)
        assertTrue(capture().sameAs(silent1))
    }

    @Test
    fun reducedMotionShowsAStaticThreeStepMeter() {
        render(reducedMotion = true)
        levels.value = floatArrayOf(0f, 0f, 0.6f, 0f, 0f, 0f, 0f) // two of three steps lit
        rule.mainClock.advanceTimeBy(100)
        val b = capture()
        Shots.save("levels-reduced", b)
        // Three bars of 8 / 16 / 24 dp; their heights never change with the level, only their opacity.
        val h = (0 until 3).map { barHeightDp(b, it, density) }
        levels.value = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f)
        rule.mainClock.advanceTimeBy(100)
        val all = capture()
        assertEquals(m.barMax, barHeightDp(all, 2, density), 1f)
        assertEquals(m.barMax / 3f, barHeightDp(all, 0, density), 1f)
        assertEquals(2 * m.barMax / 3f, barHeightDp(all, 1, density), 1f)
        assertEquals(m.barMax / 3f, h[0], 1f)
        assertEquals(2 * m.barMax / 3f, h[1], 1f)
        // The unlit third bar is dimmed (35%), so it is not counted as a light run.
        assertEquals(0f, h[2], 1f)
    }
}
