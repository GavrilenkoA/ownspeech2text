package app.murmur.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Radius tokens as shapes (DESIGN 3.3): full for buttons, chips, search, segmented and the bubble; lg for grouped
 * lists and cards; xl for sheets and dialogs; md for banners, the audio player and the editor; xs for badges and tiles.
 */
object MurmurShapes {
    val full: Shape = CircleShape
    val xs = RoundedCornerShape(Radius.xs)
    val sm = RoundedCornerShape(Radius.sm)
    val md = RoundedCornerShape(Radius.md)
    val lg = RoundedCornerShape(Radius.lg)
    val xl = RoundedCornerShape(Radius.xl)

    /** Bottom sheet: xl on the top corners only. */
    val sheetBottom = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)

    /** Side sheet on the inner display: xl on the leading (left) corners only. */
    val sheetSide = RoundedCornerShape(topStart = Radius.xl, bottomStart = Radius.xl)

    /** Stock M3 components pick these up through MaterialTheme.shapes. */
    val material = Shapes(extraSmall = xs, small = sm, medium = md, large = lg, extraLarge = xl)
}

/**
 * Draws the layered shadow of [spec] (tokens.json e1..e3) behind the content in [shape]. e0 adds nothing.
 * Apply before `background`/`clip` so the shadow sits outside the surface.
 */
fun Modifier.elevation(spec: ElevationSpec, shape: Shape): Modifier =
    spec.layers.fold(this) { m, layer ->
        m.dropShadow(shape, Shadow(radius = layer.blur, color = layer.color, offset = DpOffset(0.dp, layer.y)))
    }
