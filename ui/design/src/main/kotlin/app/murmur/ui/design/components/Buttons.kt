package app.murmur.ui.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.Sizes

/** DESIGN 4 button variants. [Destructive] is the text button in `err` (dialogs: "Delete"). */
enum class ButtonStyle { Primary, Secondary, Outline, Text, Destructive }

/** [Regular]: 52 dp (text buttons 48 dp). [Small]: 40 dp visual, 14 sp label, hit area padded to 48 dp. */
enum class ButtonSize { Regular, Small }

/** Disabled controls are drawn at 38 % opacity (DESIGN 4). */
const val DISABLED_ALPHA = 0.38f

/** The minimum touch target (DESIGN 4, 8). */
val MinTouch: Dp = Sizes.touchTargetMin

/** Vertical padding that grows a [visual]-tall control to the 48 dp touch target. */
internal fun hitPad(visual: Dp): Dp = ((MinTouch - visual) / 2).coerceAtLeast(0.dp)

/**
 * Full-radius button (DESIGN 4). Primary: accent fill, accentPressed while pressed, onAccent label. Secondary: surface2.
 * Outline: 1 dp line2 border. Text: accentInk, 12 dp padding. Destructive: text button in err.
 * [loading] replaces the icon by a 16 dp spinner, keeps the label (e.g. "Downloading…") and ignores taps.
 */
@Composable
fun MurmurButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    size: ButtonSize = ButtonSize.Regular,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val textLike = style == ButtonStyle.Text || style == ButtonStyle.Destructive
    val visualHeight = when {
        size == ButtonSize.Small -> Sizes.buttonSm
        textLike -> MinTouch
        else -> Sizes.button
    }
    val hPad = when {
        textLike -> 12.dp
        size == ButtonSize.Small -> 16.dp
        else -> 24.dp
    }
    val container = when (style) {
        ButtonStyle.Primary -> if (pressed) c.accentPressed else c.accent
        ButtonStyle.Secondary -> c.surface2
        else -> Color.Transparent
    }
    val content = when (style) {
        ButtonStyle.Primary -> c.onAccent
        ButtonStyle.Secondary, ButtonStyle.Outline -> c.ink
        ButtonStyle.Text -> c.accentInk
        ButtonStyle.Destructive -> c.err
    }
    val label = if (size == ButtonSize.Small) t.button.copy(fontSize = 14.sp) else t.button
    val loadingDescription = stringResource(R.string.design_loading)
    Row(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (loading) Modifier.semantics { contentDescription = "$text, $loadingDescription" } else Modifier)
            .padding(vertical = hitPad(visualHeight))
            .widthIn(min = MinTouch)
            .height(visualHeight)
            .clip(MurmurShapes.full)
            .background(container)
            .then(if (style == ButtonStyle.Outline) Modifier.border(1.dp, c.line2, MurmurShapes.full) else Modifier)
            .then(if (style == ButtonStyle.Primary) Modifier else Modifier.indication(interaction, LocalIndication.current))
            .padding(horizontal = hPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            Spinner(color = content)
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Text(text, style = label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 16 dp indeterminate spinner (prototype .spin: 2 dp ring with one open quarter, 0.9 s per turn). */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = MurmurTheme.colors.ink, size: Dp = Sizes.iconSm) {
    val reduced = MurmurTheme.reducedMotion
    val angle = if (reduced) 0f else {
        val tr = rememberInfiniteTransition(label = "spinner")
        val a by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn")
        a
    }
    Box(modifier.size(size).rotate(angle)) {
        Canvas(Modifier.size(size)) {
            val w = 2.dp.toPx()
            drawArc(
                color = color, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
                style = Stroke(width = w, cap = StrokeCap.Butt),
            )
        }
    }
}

/**
 * Icon button (DESIGN 4): 48 dp circle with a 24 dp icon; [small] = 40 dp visual inside a 48 dp hit area;
 * [tonal] adds the surface2 fill.
 */
@Composable
fun MurmurIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    small: Boolean = false,
    enabled: Boolean = true,
    tint: Color = MurmurTheme.colors.ink,
    iconSize: Dp = if (small) 20.dp else Sizes.icon,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = MurmurTheme.colors
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val visual = if (small) Sizes.buttonSm else Sizes.iconButton
    Box(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(hitPad(visual))
            .size(visual)
            .clip(MurmurShapes.full)
            .then(if (tonal) Modifier.background(c.surface2) else Modifier)
            .indication(interaction, LocalIndication.current),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
