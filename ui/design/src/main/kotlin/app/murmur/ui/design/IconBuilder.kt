package app.murmur.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/** One element of a prototype icon as SVG path data; [filled] = the prototype's `class="f"` (fill, no stroke). */
internal data class IconPath(val d: String, val filled: Boolean = false, val alpha: Float = 1f)

/** DESIGN 3.4: outlined icons use a 1.75 dp stroke on the 24 dp grid; the mark uses 2.25. */
internal val ICON_STROKE: Float = Sizes.iconStroke.value
internal const val MARK_STROKE: Float = 2.25f
private const val GRID = 24f

/**
 * Builds the ImageVector for prototype icon [name]. Strokes and fills are black so that `Icon(tint = …)` colours
 * the whole glyph, as `currentColor` does in the prototype.
 */
internal fun buildIcon(name: String, strokeWidth: Float = if (name == "mark") MARK_STROKE else ICON_STROKE): ImageVector {
    val paths = requireNotNull(IconData.paths[name]) { "unknown icon $name" }
    val b = ImageVector.Builder(
        name = "MurmurIcons.$name", defaultWidth = GRID.dp, defaultHeight = GRID.dp,
        viewportWidth = GRID, viewportHeight = GRID,
    )
    for (p in paths) {
        if (p.filled) {
            b.addPath(pathData = addPathNodes(p.d), fill = SolidColor(Color.Black), fillAlpha = p.alpha)
        } else {
            b.addPath(
                pathData = addPathNodes(p.d), fill = null, stroke = SolidColor(Color.Black), strokeAlpha = p.alpha,
                strokeLineWidth = strokeWidth, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return b.build()
}

private val boldCache = ConcurrentHashMap<String, ImageVector>()

internal fun boldIcon(name: String): ImageVector = boldCache.getOrPut(name) { buildIcon(name, strokeWidth = MARK_STROKE) }
