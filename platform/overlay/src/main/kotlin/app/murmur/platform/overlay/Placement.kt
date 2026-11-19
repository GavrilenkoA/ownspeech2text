package app.murmur.platform.overlay

import app.murmur.core.store.BubblePosition
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Inputs of the DESIGN 5.4 placement, all in pixels of one display.
 *
 * [safeTop] is the bottom of the status bar (or display cutout), [safeBottom] the top of the navigation / gesture bar.
 * [bubbleDiameterPx] is the docked circle (Settings size); every other visual is centred on the same line.
 */
data class PlacementInput(
    val screenW: Int, val screenH: Int, val density: Float,
    val safeTop: Int, val safeBottom: Int,
    val fieldTop: Int?,
    val imeTop: Int?,
    val stored: BubblePosition?,
    val bubbleDiameterPx: Int,
    /** Where the bubble is now (top of the circle), used when there is neither a field nor a stored position. */
    val currentY: Int? = null,
    /** Docked side now, used when nothing is stored. */
    val currentRightEdge: Boolean? = null,
)

/** [x] / [y] = left / top of the docked circle in screen pixels; the window adds its own padding around it. */
data class Placement(val rightEdge: Boolean, val x: Int, val y: Int)

/** Pure placement math of DESIGN 5.4 (px in, px out); unit-tested without a device. */
object BubblePlacement {
    const val EDGE_MARGIN_DP = 8f
    const val FIELD_GAP_DP = 12f
    const val IME_GAP_DP = 12f
    const val STATUS_GAP_DP = 16f
    /** Bottom of the usable range without a keyboard: the same 12 dp above the navigation / gesture bar. */
    const val NAV_GAP_DP = 12f
    const val FLING_DP_PER_S = 1_000f
    /** Default y (fraction of the usable range) when there is no field, nothing stored and no current position. */
    const val DEFAULT_FRACTION = 0.6f

    fun place(input: PlacementInput): Placement {
        val right = input.stored?.rightEdge ?: input.currentRightEdge ?: true
        val y = when {
            input.stored != null -> clampY(yForFraction(input.stored.yFraction, input), input)
            input.fieldTop != null -> fieldY(input)
            input.currentY != null -> clampY(input.currentY, input)
            else -> clampY(yForFraction(DEFAULT_FRACTION, input), input)
        }
        return Placement(right, xFor(right, input.screenW, input.bubbleDiameterPx, input.density), y)
    }

    /** Release after drag: nearer edge, or fling direction if |vx| > 1000 dp/s. A release exactly at the centre docks right. */
    fun snap(xCenter: Int, screenW: Int, vxDpPerSec: Float): Boolean =
        if (abs(vxDpPerSec) > FLING_DP_PER_S) vxDpPerSec > 0f else xCenter * 2 >= screenW

    /** The position stored after a drag that ends with the circle's top at [y] on side [rightEdge] (per display). */
    fun stored(rightEdge: Boolean, y: Int, input: PlacementInput): BubblePosition =
        BubblePosition(rightEdge, fractionOf(clampY(y, input), input))

    /** Left edge of the docked circle: 8 dp from the docked screen edge. */
    fun xFor(rightEdge: Boolean, screenW: Int, diameterPx: Int, density: Float): Int {
        val m = px(EDGE_MARGIN_DP, density)
        return if (rightEdge) screenW - m - diameterPx else m
    }

    /** Highest allowed top of the circle: status bar + 16 dp. */
    fun topLimit(input: PlacementInput): Int = input.safeTop + px(STATUS_GAP_DP, input.density)

    /** Lowest allowed bottom of the circle without a keyboard. */
    fun navLimit(input: PlacementInput): Int = input.safeBottom - px(NAV_GAP_DP, input.density)

    /** Lowest allowed bottom of the circle: never below imeTop - 12 dp, nor the navigation limit. */
    fun bottomLimit(input: PlacementInput): Int {
        val ime = input.imeTop?.let { it - px(IME_GAP_DP, input.density) }
        return if (ime == null) navLimit(input) else minOf(ime, navLimit(input))
    }

    /** Clamp a circle top into [topLimit, bottomLimit - d]; when the range is empty the keyboard wins (stay above it). */
    fun clampY(y: Int, input: PlacementInput): Int {
        val lo = topLimit(input)
        val hi = bottomLimit(input) - input.bubbleDiameterPx
        return if (hi < lo) maxOf(hi, input.safeTop) else y.coerceIn(lo, hi)
    }

    /**
     * The stored fraction is relative to the display's usable range WITHOUT the keyboard, so that the same position
     * comes back when the keyboard closes; the keyboard only clamps it.
     */
    fun fractionOf(y: Int, input: PlacementInput): Float {
        val lo = topLimit(input)
        val span = navLimit(input) - input.bubbleDiameterPx - lo
        if (span <= 0) return 0f
        return ((y - lo).toFloat() / span).coerceIn(0f, 1f)
    }

    fun yForFraction(f: Float, input: PlacementInput): Int {
        val lo = topLimit(input)
        val span = (navLimit(input) - input.bubbleDiameterPx - lo).coerceAtLeast(0)
        return lo + (f.coerceIn(0f, 1f) * span).roundToInt()
    }

    /**
     * 12 dp above the field's top; if that goes above status bar + 16 dp (a tall field whose top is near the screen
     * top, e.g. a full-screen editor), 12 dp above the keyboard instead. Never below imeTop - 12 dp: a field hidden
     * behind the keyboard pulls the bubble up to the keyboard edge.
     */
    private fun fieldY(input: PlacementInput): Int {
        val d = input.bubbleDiameterPx
        val aboveField = input.fieldTop!! - px(FIELD_GAP_DP, input.density) - d
        val y = if (aboveField < topLimit(input)) bottomLimit(input) - d else aboveField
        return clampY(y, input)
    }

    /**
     * Re-clamp on a size or rotation change when nothing is stored: keep the side and the proportional y
     * (platform spike a: same edge, proportional y).
     */
    fun reproject(placement: Placement, old: PlacementInput, new: PlacementInput): Placement {
        val f = fractionOf(placement.y, old)
        val y = clampY(yForFraction(f, new), new)
        return Placement(placement.rightEdge, xFor(placement.rightEdge, new.screenW, new.bubbleDiameterPx, new.density), y)
    }

    /**
     * Key of [app.murmur.core.store.Settings.bubblePositions]: cover and inner screens are stored separately (DESIGN 5.4).
     * The Fold's two panels are one logical display with different sizes, so the key is the size class of the
     * smallest width (rotation-invariant), prefixed by the display id for secondary displays.
     */
    fun displayKey(displayId: Int, widthPx: Int, heightPx: Int, density: Float): String {
        val sw = minOf(widthPx, heightPx) / density
        val cls = if (sw >= 600f) "inner" else "cover"
        return if (displayId == 0) cls else "$displayId:$cls"
    }

    fun px(dp: Float, density: Float): Int = (dp * density).roundToInt()
}
