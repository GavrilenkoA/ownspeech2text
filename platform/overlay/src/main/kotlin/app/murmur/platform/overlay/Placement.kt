package app.murmur.platform.overlay

/** Pure placement math of DESIGN 5.4 (px in, px out); unit-tested without a device. WP-03 implements. */
data class PlacementInput(
    val screenW: Int, val screenH: Int, val density: Float,
    val safeTop: Int, val safeBottom: Int,
    val fieldTop: Int?, val fieldBottom: Int?,
    val imeTop: Int?,
    val stored: app.murmur.core.store.BubblePosition?,
    val bubbleDiameterPx: Int,
)

data class Placement(val rightEdge: Boolean, val x: Int, val y: Int)

object BubblePlacement {
    fun place(input: PlacementInput): Placement = TODO("WP-03: DESIGN 5.4 rules (12 dp above field, never below imeTop - 12 dp, per-display stored position)")
    /** Release after drag: nearer edge, or fling direction if |vx| > 1000 dp/s. */
    fun snap(xCenter: Int, screenW: Int, vxDpPerSec: Float): Boolean = TODO("WP-03")
}
