package app.murmur.platform.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN 5.3 gestures on raw screen pixels at density 2.625 (8 dp = 21 px, 64 dp = 168 px). */
class BubbleGesturesTest {
    private val density = 2.625f
    private val pill = PxRect(900f, 1000f, 1037f, 1137f)
    private val r = BubbleGestureRecognizer(density) { pill }
    private val readyHold = PressPolicy(TouchRegion.BODY, canTap = true, canHold = true, canDrag = true)
    private val tapDrag = PressPolicy(TouchRegion.BODY, canTap = true, canHold = false, canDrag = true)

    @Test
    fun quickPressIsATap() {
        r.down(960f, 1060f, 0, readyHold)
        assertEquals(listOf(Gesture.Tap(TouchRegion.BODY)), r.up(962f, 1061f, 120))
    }

    @Test
    fun movementUnder8dpStaysATap() {
        r.down(960f, 1060f, 0, tapDrag)
        assertEquals(emptyList<Gesture>(), r.move(980f, 1060f, 40)) // 20 px < 21 px
        assertEquals(listOf(Gesture.Tap(TouchRegion.BODY)), r.up(980f, 1060f, 80))
    }

    @Test
    fun movement8dpStartsADragFromTheDownPoint() {
        r.down(960f, 1060f, 0, tapDrag)
        assertEquals(listOf(Gesture.DragStart(960f, 1060f), Gesture.DragMove(982f, 1060f)), r.move(982f, 1060f, 40))
        assertEquals(listOf(Gesture.DragMove(700f, 1200f)), r.move(700f, 1200f, 300))
        val end = r.up(690f, 1205f, 400)
        assertEquals(1, end.size)
        assertTrue(end.single() is Gesture.DragEnd)
    }

    @Test
    fun holdStartsAt250msAndReleaseFinishes() {
        r.down(960f, 1060f, 1_000, readyHold)
        assertEquals(1_250L, r.holdDeadline())
        assertEquals(emptyList<Gesture>(), r.tick(1_249))
        assertEquals(listOf(Gesture.HoldStart), r.tick(1_250))
        // Movement inside the pill (or within 64 dp of it) is not a drag while holding.
        assertEquals(emptyList<Gesture>(), r.move(860f, 1060f, 1_400))
        assertEquals(listOf(Gesture.HoldRelease), r.up(860f, 1060f, 2_000))
    }

    @Test
    fun slidingMoreThan64dpOffThePillDiscards() {
        r.down(960f, 1060f, 0, readyHold)
        r.tick(300)
        assertEquals(emptyList<Gesture>(), r.move(900f - 168f, 1060f, 400)) // exactly 64 dp: not yet
        assertEquals(listOf(Gesture.HoldSlideOff), r.move(900f - 169f, 1060f, 420))
        // Releasing afterwards sends nothing: the dictation is already discarded.
        assertEquals(emptyList<Gesture>(), r.up(600f, 1060f, 500))
    }

    @Test
    fun aHoldThatIsDueFiresBeforeAMove() {
        r.down(960f, 1060f, 0, readyHold)
        // The timer was late: the move at 260 ms still sees the hold first, and 30 px is not a slide-off.
        assertEquals(listOf(Gesture.HoldStart), r.move(990f, 1060f, 260))
    }

    @Test
    fun aHoldThatIsDueAtReleaseIsHoldThenRelease() {
        r.down(960f, 1060f, 0, readyHold)
        assertEquals(listOf(Gesture.HoldStart, Gesture.HoldRelease), r.up(960f, 1060f, 600))
    }

    @Test
    fun withoutHoldToTalkALongPressIsATap() {
        r.down(960f, 1060f, 0, tapDrag)
        assertEquals(null, r.holdDeadline())
        assertEquals(emptyList<Gesture>(), r.tick(5_000))
        assertEquals(listOf(Gesture.Tap(TouchRegion.BODY)), r.up(960f, 1060f, 5_000))
    }

    @Test
    fun dragBeforeTheHoldCancelsTheHold() {
        r.down(960f, 1060f, 0, readyHold)
        r.move(900f, 1060f, 100)
        assertEquals(null, r.holdDeadline())
        assertEquals(emptyList<Gesture>(), r.tick(400))
        assertTrue(r.dragging)
    }

    @Test
    fun workingStatesOnlyDrag_tapsAreSwallowed() {
        val working = PressPolicy(TouchRegion.BODY, canTap = false, canHold = false, canDrag = true)
        r.down(960f, 1060f, 0, working)
        assertEquals(emptyList<Gesture>(), r.up(960f, 1060f, 50))
        r.down(960f, 1060f, 100, working)
        assertEquals(Gesture.DragStart(960f, 1060f), r.move(900f, 1060f, 150).first())
    }

    @Test
    fun noDragWhenThePolicyForbidsIt() {
        val actionButton = PressPolicy(TouchRegion.ACTION, canTap = true, canHold = false, canDrag = false)
        r.down(960f, 1060f, 0, actionButton)
        assertEquals(emptyList<Gesture>(), r.move(800f, 1060f, 50))
        // Moved away: not a tap either.
        assertEquals(emptyList<Gesture>(), r.up(800f, 1060f, 100))
        r.down(960f, 1060f, 200, actionButton)
        assertEquals(listOf(Gesture.Tap(TouchRegion.ACTION)), r.up(960f, 1060f, 260))
    }

    @Test
    fun flingVelocityInDpPerSecond() {
        r.down(500f, 1060f, 0, tapDrag)
        r.move(530f, 1060f, 10)
        r.move(600f, 1060f, 20)
        r.move(700f, 1060f, 30)
        // All samples are within the last 100 ms: (2600 - 500) px / 2.625 over 50 ms = 16000 dp/s.
        val v = (r.up(2_600f, 1060f, 50).single() as Gesture.DragEnd).vxDpPerSec
        assertEquals(16_000f, v, 1f)
        // A slow drag that stops before the release has (almost) no velocity.
        r.down(500f, 1060f, 1_000, tapDrag)
        r.move(560f, 1060f, 1_020)
        r.move(800f, 1060f, 1_400)
        r.move(800f, 1060f, 1_600)
        val slow = (r.up(800f, 1060f, 1_700).single() as Gesture.DragEnd).vxDpPerSec
        assertEquals(0f, slow, 0.001f)
    }

    @Test
    fun cancelWhileHoldingFinishes_whileDraggingSettles() {
        r.down(960f, 1060f, 0, readyHold)
        r.tick(250)
        assertEquals(listOf(Gesture.HoldRelease), r.cancel())
        r.down(960f, 1060f, 1_000, tapDrag)
        r.move(800f, 1060f, 1_050)
        assertEquals(listOf(Gesture.DragCancel), r.cancel())
        r.down(960f, 1060f, 2_000, tapDrag)
        assertEquals(emptyList<Gesture>(), r.cancel())
    }

    @Test
    fun distanceToRect() {
        assertEquals(0f, pill.distanceTo(950f, 1050f), 0f)
        assertEquals(100f, pill.distanceTo(800f, 1050f), 0f)
        assertEquals(50f, pill.distanceTo(1067f, 1177f), 0.001f) // 30, 40 -> 50
    }
}
