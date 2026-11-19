package app.murmur.platform.overlay

import app.murmur.core.store.BubblePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * DESIGN 5.4 placement and snap tables. Displays are the emulator's Fold7 AVDs at 420 dpi (density 2.625):
 * cover 1080 x 2520 px (411 x 960 dp), inner 1968 x 2184 px (750 x 832 dp); status bar 63 px, gesture bar 63 px.
 */
class BubblePlacementTest {
    private val density = 2.625f
    private fun px(dp: Float) = (dp * density).roundToInt()
    private val d = px(52f) // 137

    private fun cover(
        fieldTop: Int? = null, imeTop: Int? = null, stored: BubblePosition? = null,
        currentY: Int? = null, diameter: Int = d,
    ) = PlacementInput(1080, 2520, density, safeTop = 63, safeBottom = 2520 - 63, fieldTop = fieldTop, imeTop = imeTop,
        stored = stored, bubbleDiameterPx = diameter, currentY = currentY)

    private fun inner(fieldTop: Int? = null, imeTop: Int? = null, stored: BubblePosition? = null) =
        PlacementInput(1968, 2184, density, safeTop = 63, safeBottom = 2184 - 63, fieldTop = fieldTop, imeTop = imeTop,
            stored = stored, bubbleDiameterPx = d)

    private val rightX = 1080 - px(8f) - d
    private val topLimit = 63 + px(16f)

    @Test
    fun t01_fieldWithoutKeyboard_sits12dpAboveTheField_dockedRightByDefault() {
        val p = BubblePlacement.place(cover(fieldTop = 1200))
        assertEquals(Placement(true, rightX, 1200 - px(12f) - d), p)
    }

    @Test
    fun t02_keyboardUp_fieldAboveIt_stays12dpAboveTheField() {
        // Chat composer just above the keyboard (b-listen--cover: field top 638 dp, keyboard 690 dp).
        val p = BubblePlacement.place(cover(fieldTop = px(638f), imeTop = px(690f)))
        assertEquals(px(638f) - px(12f) - d, p.y)
        assertTrue(p.y + d <= px(690f) - px(12f))
    }

    @Test
    fun t03_keyboardUp_fieldBehindTheKeyboard_neverBelowImeTopMinus12dp() {
        val ime = 1500
        val p = BubblePlacement.place(cover(fieldTop = 1900, imeTop = ime))
        assertEquals(ime - px(12f) - d, p.y)
    }

    @Test
    fun t04_keyboardDown_theSameFieldDocksAboveItAgain() {
        val up = BubblePlacement.place(cover(fieldTop = 1900, imeTop = 1500))
        val down = BubblePlacement.place(cover(fieldTop = 1900, imeTop = null))
        assertEquals(1900 - px(12f) - d, down.y)
        assertTrue(down.y > up.y)
    }

    @Test
    fun t05_fieldNearTheTop_goes12dpAboveTheKeyboard() {
        // A full-screen editor whose top is under the status bar + 16 dp limit.
        val p = BubblePlacement.place(cover(fieldTop = 150, imeTop = 1400))
        assertEquals(1400 - px(12f) - d, p.y)
    }

    @Test
    fun t06_fieldNearTheTop_noKeyboard_goesToTheBottomOfTheUsableArea() {
        val p = BubblePlacement.place(cover(fieldTop = 150))
        assertEquals(2520 - 63 - px(12f) - d, p.y)
    }

    @Test
    fun t07_fieldExactlyAtTheLimit_staysAboveTheField() {
        val fieldTop = topLimit + px(12f) + d
        assertEquals(topLimit, BubblePlacement.place(cover(fieldTop = fieldTop)).y)
        assertEquals(1400 - px(12f) - d, BubblePlacement.place(cover(fieldTop = fieldTop - 1, imeTop = 1400)).y)
    }

    @Test
    fun t08_storedPositionWinsOverTheField() {
        val stored = BubblePosition(rightEdge = false, yFraction = 0.25f)
        val p = BubblePlacement.place(cover(fieldTop = 1200, stored = stored))
        assertFalse(p.rightEdge)
        assertEquals(px(8f), p.x)
        assertEquals(BubblePlacement.yForFraction(0.25f, cover()), p.y)
    }

    @Test
    fun t09_storedPositionIsClampedByTheKeyboard() {
        val p = BubblePlacement.place(cover(stored = BubblePosition(true, 1f), imeTop = 1400))
        assertEquals(1400 - px(12f) - d, p.y)
        // Keyboard closes: the stored bottom position comes back.
        assertEquals(2520 - 63 - px(12f) - d, BubblePlacement.place(cover(stored = BubblePosition(true, 1f))).y)
    }

    @Test
    fun t10_storedFractionZero_isStatusBarPlus16dp() {
        assertEquals(topLimit, BubblePlacement.place(cover(stored = BubblePosition(true, 0f))).y)
    }

    @Test
    fun t11_storeAndPlaceRoundTrip() {
        val y = 1333
        val stored = BubblePlacement.stored(rightEdge = false, y = y, input = cover())
        val p = BubblePlacement.place(cover(stored = stored))
        assertFalse(p.rightEdge)
        assertTrue("y=${p.y}", abs(p.y - y) <= 1)
    }

    @Test
    fun t12_storingAboveTheLimitClampsFirst() {
        assertEquals(0f, BubblePlacement.stored(true, 10, cover()).yFraction, 0f)
        assertEquals(1f, BubblePlacement.stored(true, 5_000, cover()).yFraction, 0f)
    }

    @Test
    fun t13_noFieldNothingStored_keepsTheCurrentPositionClamped() {
        assertEquals(900, BubblePlacement.place(cover(currentY = 900)).y)
        assertEquals(topLimit, BubblePlacement.place(cover(currentY = 0)).y)
        assertEquals(1400 - px(12f) - d, BubblePlacement.place(cover(currentY = 2300, imeTop = 1400)).y)
    }

    @Test
    fun t14_nothingKnown_usesTheDefaultFractionOnTheRight() {
        val p = BubblePlacement.place(cover())
        assertEquals(Placement(true, rightX, BubblePlacement.yForFraction(BubblePlacement.DEFAULT_FRACTION, cover())), p)
    }

    @Test
    fun t15_innerDisplay_sameRulesWithItsOwnSize() {
        val p = BubblePlacement.place(inner(fieldTop = 1300, imeTop = 1343))
        assertEquals(Placement(true, 1968 - px(8f) - d, 1300 - px(12f) - d), p)
        val left = BubblePlacement.place(inner(stored = BubblePosition(false, 0.5f)))
        assertEquals(px(8f), left.x)
    }

    @Test
    fun t16_coverAndInnerAreStoredSeparately_rotationKeepsTheKey() {
        assertEquals("cover", BubblePlacement.displayKey(0, 1080, 2520, density))
        assertEquals("cover", BubblePlacement.displayKey(0, 2520, 1080, density)) // landscape cover
        assertEquals("inner", BubblePlacement.displayKey(0, 1968, 2184, density))
        assertEquals("inner", BubblePlacement.displayKey(0, 2184, 1968, density)) // landscape inner
        assertEquals("2:cover", BubblePlacement.displayKey(2, 1080, 2520, density))
    }

    @Test
    fun t17_rotationReprojectsSameEdgeAndProportionalY() {
        val portrait = cover()
        val landscape = PlacementInput(2520, 1080, density, safeTop = 63, safeBottom = 1080 - 63, fieldTop = null, imeTop = null,
            stored = null, bubbleDiameterPx = d)
        val before = Placement(true, rightX, BubblePlacement.yForFraction(0.4f, portrait))
        val after = BubblePlacement.reproject(before, portrait, landscape)
        assertTrue(after.rightEdge)
        assertEquals(2520 - px(8f) - d, after.x)
        assertEquals(0.4f, BubblePlacement.fractionOf(after.y, landscape), 0.01f)
        // And back: the same position as before (within rounding).
        val back = BubblePlacement.reproject(after, landscape, portrait)
        assertTrue(abs(back.y - before.y) <= 2)
    }

    @Test
    fun t18_coverToInnerSizeChangeKeepsEdgeAndFraction() {
        val before = Placement(false, px(8f), BubblePlacement.yForFraction(0.7f, cover()))
        val after = BubblePlacement.reproject(before, cover(), inner())
        assertFalse(after.rightEdge)
        assertEquals(px(8f), after.x)
        assertEquals(0.7f, BubblePlacement.fractionOf(after.y, inner()), 0.01f)
    }

    @Test
    fun t19_sizeSettingChangesDiameter_rightEdgeStays8dpFromTheEdge() {
        for (sizeDp in listOf(44f, 52f, 60f)) {
            val dia = px(sizeDp)
            val p = BubblePlacement.place(cover(fieldTop = 1200, diameter = dia))
            assertEquals(1080 - px(8f) - dia, p.x)
            assertEquals(1200 - px(12f) - dia, p.y)
        }
    }

    @Test
    fun t20_keyboardLeavesNoRoom_staysAboveTheKeyboard() {
        // Landscape cover with a tall keyboard: the range between status bar + 16 dp and imeTop - 12 dp is empty.
        val tight = PlacementInput(2520, 1080, density, safeTop = 63, safeBottom = 1080 - 63, fieldTop = 120, imeTop = 250,
            stored = null, bubbleDiameterPx = d)
        val p = BubblePlacement.place(tight)
        assertTrue(BubblePlacement.bottomLimit(tight) - d < BubblePlacement.topLimit(tight)) // really empty
        assertTrue(p.y + d <= 250 - px(12f))
        assertTrue(p.y >= 63)
    }

    @Test
    fun snapTable() {
        val w = 1080
        // Nearer edge.
        assertFalse(BubblePlacement.snap(xCenter = 200, screenW = w, vxDpPerSec = 0f))
        assertTrue(BubblePlacement.snap(xCenter = 900, screenW = w, vxDpPerSec = 0f))
        // Exactly the centre docks right (the default side).
        assertTrue(BubblePlacement.snap(xCenter = 540, screenW = w, vxDpPerSec = 0f))
        // Slow movement does not override the nearer edge.
        assertFalse(BubblePlacement.snap(xCenter = 200, screenW = w, vxDpPerSec = 1_000f))
        assertTrue(BubblePlacement.snap(xCenter = 900, screenW = w, vxDpPerSec = -999f))
        // Fling > 1000 dp/s decides the side.
        assertTrue(BubblePlacement.snap(xCenter = 200, screenW = w, vxDpPerSec = 1_001f))
        assertFalse(BubblePlacement.snap(xCenter = 900, screenW = w, vxDpPerSec = -2_500f))
    }
}
