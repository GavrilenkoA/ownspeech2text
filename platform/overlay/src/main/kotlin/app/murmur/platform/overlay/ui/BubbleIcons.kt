package app.murmur.platform.overlay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The bubble's six glyphs, path data copied from the prototype's ICONS map (docs/design/prototype/index.html) on the
 * 24 dp grid. Outlined glyphs use the 1.75 dp stroke, the mark and the Inserted check 2.25 (prototype class "b2");
 * stop is filled. SVG <rect> elements are written as equivalent rounded-rect paths. Black, tinted by Icon(tint).
 */
internal object BubbleIcons {
    private const val STROKE = 1.75f
    private const val BOLD = 2.25f

    val mark: ImageVector = outlined("mark", BOLD,
        "M5 17.5V9.5M5 12.25a3.25 3.25 0 0 1 6.5 0v5.25M11.5 12.25a3.25 3.25 0 0 1 6.5 0v5.25")

    /** rect x=7 y=7 w=10 h=10 rx=2.5, filled. */
    val stop: ImageVector = ImageVector.Builder("bubble.stop", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M9.5 7h5a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1 -2.5 2.5h-5a2.5 2.5 0 0 1 -2.5 -2.5v-5a2.5 2.5 0 0 1 2.5 -2.5z"),
            fill = SolidColor(Color.Black))
    }.build()

    val check: ImageVector = outlined("check", BOLD, "M5 12.5l4.5 4.5L19 7.5")

    val close: ImageVector = outlined("x", STROKE, "M6.5 6.5l11 11M17.5 6.5l-11 11")

    val alert: ImageVector = outlined("alert", STROKE,
        "M10.3 4.6L3.2 17a2 2 0 0 0 1.7 3h14.2a2 2 0 0 0 1.7-3L13.7 4.6a2 2 0 0 0-3.4 0z", "M12 9.5v4M12 16.8v.2")

    /** rect x=5.5 y=5 w=13 h=16 rx=2.5 plus the clip and two text lines. */
    val clipboard: ImageVector = outlined("clip", STROKE,
        "M8 5h8a2.5 2.5 0 0 1 2.5 2.5v11a2.5 2.5 0 0 1 -2.5 2.5h-8a2.5 2.5 0 0 1 -2.5 -2.5v-11a2.5 2.5 0 0 1 2.5 -2.5z",
        "M9 5V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1", "M9 11h6M9 15h4")

    private fun outlined(name: String, stroke: Float, vararg paths: String): ImageVector =
        ImageVector.Builder("bubble.$name", 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
}
