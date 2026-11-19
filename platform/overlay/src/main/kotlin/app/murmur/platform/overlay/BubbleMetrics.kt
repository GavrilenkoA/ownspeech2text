package app.murmur.platform.overlay

import app.murmur.core.store.BubbleSize

/**
 * Bubble geometry in dp (DESIGN 5.3, 5.6, tokens.json size.bubble). Standard is 52 dp; Small / Large scale every pill
 * dimension proportionally ("Pills scale proportionally"); hit areas never drop below 48 dp. The edge tab, the
 * cancel button and the hide target keep their sizes (they are not pills).
 */
data class BubbleMetrics(val sizeDp: Int) {
    val scale: Float = sizeDp / STANDARD_DP

    val diameter: Float = sizeDp.toFloat()
    /** Ready hit area: 56 dp at 52 (the visual plus 2 dp each side), never below 48. */
    val readyHit: Float = maxOf(TOUCH_MIN, diameter + 4f)
    val pillHeight: Float = diameter
    val listeningWidth: Float = 164f * scale
    val maxWidth: Float = MAX_WIDTH

    val barCount = 7
    val barWidth: Float = 3f * scale
    val barGap: Float = 3f * scale
    val barMin: Float = 4f * scale
    val barMax: Float = 24f * scale

    val stopDiameter: Float = 40f * scale
    val actionHeight: Float = 40f * scale
    val markReady: Float = 26f * scale
    val statusIcon: Float = 20f * scale

    /** Listening pill paddings (prototype .bub.listen: 0 6 0 16), working (0 18 0 16), done (0 18 0 14), error (0 6 0 14). */
    val listenPadStart: Float = 16f * scale
    val listenPadEnd: Float = 6f * scale
    val holdPadEnd: Float = 18f * scale
    val workPadStart: Float = 16f * scale
    val workPadEnd: Float = 18f * scale
    val donePadStart: Float = 14f * scale
    val donePadEnd: Float = 18f * scale
    val errPadEnd: Float = 6f * scale
    val contentGap: Float = 10f * scale

    /** The cancel circle floats 50 dp above the pill's top, its right edge 4 dp inside the pill's docked end. */
    val cancelOffsetY: Float = 50f * scale
    val cancelInset: Float = 4f

    /**
     * Transparent band around every visual inside its window, so the e2 shadow is not clipped by the window bounds.
     * The whole window is touchable and acts as the visual (no swallowed taps): the Ready hit area becomes
     * diameter + 12 dp, above the 56 dp minimum of DESIGN 5.3.
     */
    val shadowPad: Float = SHADOW_PAD

    companion object {
        const val STANDARD_DP = 52f
        const val TOUCH_MIN = 48f
        const val MAX_WIDTH = 280f
        const val SHADOW_PAD = 6f
        const val TAB_WIDTH = 22f
        const val TAB_HEIGHT = 60f
        const val TAB_HIT_WIDTH = 48f
        /** The tab's e1 shadow (1 dp offset, 2 dp blur) needs 2 dp above and below inside the window. */
        const val TAB_PAD_Y = 2f
        const val TAB_MARK = 14f
        const val CANCEL_DIAMETER = 40f
        const val CANCEL_HIT = 48f
        const val HIDE_TARGET = 64f
        const val HIDE_TARGET_BOTTOM = 56f
        const val HIDE_LABEL_GAP = 10f
        const val MAGNET_RADIUS = 72f
        const val HIDE_TARGET_RISE = 16f
        const val DRAG_LIFT = 1.08f
        const val TARGET_HOT_SCALE = 1.12f

        fun of(size: BubbleSize) = BubbleMetrics(size.dp)
    }

    /** Edge margin of the docked visual (0 for the Rest tab). */
    fun edgeMargin(visual: BubbleVisual): Float = if (visual == BubbleVisual.Rest) 0f else BubblePlacement.EDGE_MARGIN_DP

    /** Window padding on the docked-edge side and on top, around the visual (the host anchors the window with it). */
    fun windowPadEdge(visual: BubbleVisual): Float = if (visual == BubbleVisual.Rest) 0f else shadowPad
    fun windowPadTop(visual: BubbleVisual): Float = if (visual == BubbleVisual.Rest) TAB_PAD_Y else shadowPad

    /** Height of the visual; every visual is centred on the docked circle's centre line. */
    fun visualHeight(visual: BubbleVisual): Float = if (visual == BubbleVisual.Rest) TAB_HEIGHT else pillHeight

    /** Bar height in dp for a 0..1 level (LevelMeter output): silence is the 4 dp floor, never animated. */
    fun barHeight(level: Float): Float = barMin + (barMax - barMin) * level.coerceIn(0f, 1f)
}
