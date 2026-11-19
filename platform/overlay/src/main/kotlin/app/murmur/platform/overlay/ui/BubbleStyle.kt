package app.murmur.platform.overlay.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import app.murmur.ui.design.TokenType
import app.murmur.ui.design.TypeSpec

/**
 * Bubble text styles from tokens.json (DESIGN 3.2: bubble 14/18 600, monoTimer 14/18 500 tnum). The typefaces are
 * the platform sans / monospace until :ui:design bundles Onest and JetBrains Mono (WP-01); [sans] and [mono] are the
 * only two places to switch.
 */
internal object BubbleType {
    val sans: FontFamily = FontFamily.SansSerif
    val mono: FontFamily = FontFamily.Monospace

    fun style(spec: TypeSpec, scale: Float = 1f): TextStyle = TextStyle(
        fontFamily = if (spec.mono) mono else sans,
        fontWeight = FontWeight(spec.weight),
        fontSize = (spec.size * scale).sp,
        lineHeight = (spec.line * scale).sp,
        letterSpacing = spec.tracking.sp,
        fontFeatureSettings = if (spec.tabular) "tnum" else null,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    fun label(scale: Float) = style(TokenType.bubble, scale)
    fun timer(scale: Float) = style(TokenType.monoTimer, scale)
    /** Error action button: prototype `.bub .act` 600 13/16. */
    fun action(scale: Float) = style(TypeSpec(13, 16, 600, 0f, mono = false, uppercase = false, tabular = false), scale)
    /** Hide-target label: prototype `.target .lbl` 500 13/16. */
    val targetLabel = style(TypeSpec(13, 16, 500, 0f, mono = false, uppercase = false, tabular = false))
    val snackBody = style(TokenType.body)
    val snackButton = style(TokenType.button)
}

/**
 * The bubble's e2 shadow, drawn inside its window's transparent band: a window clips everything outside it, so an
 * elevation shadow would end in a hard edge. tokens.json e2 is (y 2, blur 6, 8%) + (y 8, blur 24, 10%); both layers
 * are compressed so that offset + blur stays within [reachPx] (the 6 dp band), keeping their alphas. Dark theme uses
 * the prototype's dark shadow alphas (40% / 45%).
 */
internal fun Modifier.bubbleShadow(shape: Shape, dark: Boolean, reachPx: Float, enabled: Boolean = true): Modifier =
    if (!enabled || reachPx <= 0f) this else drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        // (y offset, blur) as shares of the band, and the layer's alpha.
        val layers = listOf(
            Triple(0.15f, 0.50f, if (dark) 0.40f else 0.08f),
            Triple(0.35f, 0.65f, if (dark) 0.45f else 0.10f),
        )
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            // Near-invisible fill: the shadow layer needs something drawn; the background paints over it.
            paint.color = Color.Black.copy(alpha = 0.004f).toArgb()
            for ((dyShare, blurShare, alpha) in layers) {
                paint.setShadowLayer(reachPx * blurShare, 0f, reachPx * dyShare, Color.Black.copy(alpha = alpha).toArgb())
                drawOutline(canvas.nativeCanvas, outline, size, paint)
            }
        }
    }

private fun drawOutline(c: android.graphics.Canvas, outline: Outline, size: Size, paint: android.graphics.Paint) {
    when (outline) {
        is Outline.Rectangle -> c.drawRect(0f, 0f, size.width, size.height, paint)
        is Outline.Rounded -> {
            val r = outline.roundRect
            if (r.topLeftCornerRadius == r.topRightCornerRadius && r.topLeftCornerRadius == r.bottomLeftCornerRadius &&
                r.topLeftCornerRadius == r.bottomRightCornerRadius
            ) {
                c.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint)
            } else {
                val path = android.graphics.Path()
                path.addRoundRect(r.left, r.top, r.right, r.bottom, floatArrayOf(
                    r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, r.topRightCornerRadius.x, r.topRightCornerRadius.y,
                    r.bottomRightCornerRadius.x, r.bottomRightCornerRadius.y, r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y,
                ), android.graphics.Path.Direction.CW)
                c.drawPath(path, paint)
            }
        }
        is Outline.Generic -> c.drawPath(outline.path.asAndroidPath(), paint)
    }
}
