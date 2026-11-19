package app.murmur.ui.design.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Segment widths of the segmented control (DESIGN 8: long labels take room from short ones before wrapping). */
class DistributeWidthsTest {
    @Test
    fun equalSharesWhenEverythingFits() {
        assertArrayEquals(intArrayOf(124, 124, 123), distributeWidths(intArrayOf(80, 100, 60), 371))
    }

    @Test
    fun longLabelsTakeTheirNeedAndTheRestShare() {
        // 200 % font scale on the cover: "Final" 89, "Original" 124, "Changes" 130 px of 371.
        val w = distributeWidths(intArrayOf(89, 124, 130), 371)
        assertEquals(371, w.sum())
        assertEquals(130, w[2])
        assertEquals(124, w[1])
        assertEquals(117, w[0])
    }

    @Test
    fun repeatsUntilEveryNeedFits() {
        // Share 80: only 150 exceeds it. Fixing 150 lowers the share of the other three to 56, so 80 is fixed too.
        val w = distributeWidths(intArrayOf(150, 80, 10, 10), 320)
        assertArrayEquals(intArrayOf(150, 80, 45, 45), w)
    }

    @Test
    fun proportionalWhenNeedsExceedTheTotal() {
        val w = distributeWidths(intArrayOf(100, 300), 200)
        assertArrayEquals(intArrayOf(50, 150), w)
        val odd = distributeWidths(intArrayOf(1, 1, 1), 100)
        assertEquals(100, odd.sum())
        assertEquals(1, odd.max() - odd.min())
    }

    @Test
    fun emptyAndSingle() {
        assertEquals(0, distributeWidths(IntArray(0), 100).size)
        assertArrayEquals(intArrayOf(100), distributeWidths(intArrayOf(500), 100))
        assertArrayEquals(intArrayOf(100), distributeWidths(intArrayOf(5), 100))
    }
}
