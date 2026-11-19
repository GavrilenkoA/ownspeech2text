package app.murmur.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * First baselines of the frames' line boxes, measured in docs/design/screens/dash--cover.png (glyph bottoms relative
 * to the metric dividers and the Today card): the metric title, explanation and value, the Today sentence and the
 * display number. The browser rounds ascent and descent to whole dp and floors the half-leading.
 */
class CssLineBoxTest {
    @Test
    fun baselinesOfTheFrames() {
        assertEquals("rowTitle 15/20", 15f, CssLineBox.baselineDp(15f, 20f))
        assertEquals("rowSub 13/18", 13f, CssLineBox.baselineDp(13f, 18f))
        assertEquals("metric value 20/24", 18f, CssLineBox.baselineDp(20f, 24f))
        assertEquals("metric unit 13/16", 12f, CssLineBox.baselineDp(13f, 16f))
        assertEquals("body 14/20", 15f, CssLineBox.baselineDp(14f, 20f))
        assertEquals("display 44/48", 39f, CssLineBox.baselineDp(44f, 48f))
    }

    @Test
    fun metricRowIsSeventyOneDp() {
        // padding 14 + (value baseline − title baseline) + title 20 + gap 2 + explanation 18 + padding 14 = 71 dp,
        // the divider pitch of the frames (72 dp with the 1 dp divider).
        val shift = CssLineBox.baselineDp(20f, 24f) - CssLineBox.baselineDp(15f, 20f)
        assertEquals(71f, 14 + shift + 20 + 2 + 18 + 14)
    }
}
