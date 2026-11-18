package app.murmur.ui.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.Sizes
import kotlin.math.roundToInt

/**
 * Linear progress (DESIGN 4): full radius, surface3 track, accent fill; [paused] draws the fill in ink4 (paused or
 * failed downloads). [height] is 4 dp in lists, 6 dp in onboarding.
 */
@Composable
fun LinearProgress(progress: Float, modifier: Modifier = Modifier, paused: Boolean = false, height: Dp = Sizes.progress) {
    val c = MurmurTheme.colors
    val p = progress.coerceIn(0f, 1f)
    val paused_ = stringResource(R.string.design_paused)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(MurmurShapes.full)
            .background(c.surface3)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                if (paused) stateDescription = paused_
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(p)
                .height(height)
                .clip(MurmurShapes.full)
                .background(if (paused) c.ink4 else c.accent),
        )
    }
}

/** Ring progress (DESIGN 4): 36 dp, 3 dp ring (accent on surface3, ink4 when [paused]) around the percentage. */
@Composable
fun RingProgress(progress: Float, modifier: Modifier = Modifier, paused: Boolean = false, showPercent: Boolean = true) {
    val c = MurmurTheme.colors
    val p = progress.coerceIn(0f, 1f)
    val pct = (p * 100).roundToInt()
    val paused_ = stringResource(R.string.design_paused)
    Box(
        modifier
            .size(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                if (paused) stateDescription = paused_
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(36.dp)) {
            val w = 3.dp.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(c.surface3, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(if (paused) c.ink4 else c.accent, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(w))
        }
        if (showPercent) {
            Text(
                stringResource(R.string.design_percent, pct),
                style = MurmurTheme.type.monoBadge.copy(fontSize = 10.sp, letterSpacing = 0.sp),
                color = c.ink,
            )
        }
    }
}

/** Banner tone (DESIGN 4). */
enum class BannerKind { Err, Warn, Info }

/**
 * Banner (DESIGN 4): md radius, 14/16 dp padding, 22 dp leading icon, the [title] clause in bold followed by the
 * [body], and an optional text button. err/warn use their soft fills with ink text; info uses surface2.
 */
@Composable
fun Banner(
    kind: BannerKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = if (kind == BannerKind.Info) MurmurIcons.info else MurmurIcons.alert,
) {
    val c = MurmurTheme.colors
    val (bg, iconTint) = when (kind) {
        BannerKind.Err -> c.errSoft to c.err
        BannerKind.Warn -> c.warnSoft to c.warn
        BannerKind.Info -> c.surface2 to c.ink2
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(MurmurShapes.md)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = if (kind == BannerKind.Err) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                    append(" ")
                    append(body)
                },
                style = MurmurTheme.type.body,
                color = c.ink,
            )
            if (actionLabel != null && onAction != null) {
                // Prototype: the text button hangs 12 dp left so its label lines up with the text.
                Box(Modifier.offset(x = (-12).dp).padding(top = 2.dp)) {
                    MurmurButton(actionLabel, onAction, style = ButtonStyle.Text, size = ButtonSize.Small)
                }
            }
        }
    }
}

/**
 * Skeleton block (DESIGN 6.2 loading): surface2, 8 dp radius by default, with the shimmer sweeping every 1.4 s
 * (the only non-flat fill, DESIGN 3.1). Static under reduced motion.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
    val c = MurmurTheme.colors
    val highlight = if (MurmurTheme.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.55f)
    val reduced = MurmurTheme.reducedMotion
    val phase = if (reduced) -1f else {
        val tr = rememberInfiniteTransition(label = "shimmer")
        val v by tr.animateFloat(-1f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        v
    }
    Box(
        modifier
            .clip(shape)
            .background(c.surface2)
            .drawWithContent {
                drawContent()
                if (!reduced) {
                    val w = size.width
                    val x = phase * w
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.5f to highlight, 1f to Color.Transparent,
                            startX = x, endX = x + w,
                        ),
                    )
                }
            },
    )
}

/**
 * Empty state block (hist-empty): 56 dp surface2 icon tile (18 dp radius, 26 dp ink2 glyph), `title`, `bodyLg` in
 * ink2, and an optional secondary button (e.g. "Try a dictation"). Left-aligned, at most 420 dp wide.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = MurmurTheme.colors
    Column(modifier.widthIn(max = 420.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = c.ink2, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MurmurTheme.type.title, color = c.ink, modifier = Modifier.padding(top = 20.dp))
        Text(body, style = MurmurTheme.type.bodyLg, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
        if (actionLabel != null && onAction != null) {
            MurmurButton(
                actionLabel, onAction, style = ButtonStyle.Secondary, leadingIcon = actionIcon,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
