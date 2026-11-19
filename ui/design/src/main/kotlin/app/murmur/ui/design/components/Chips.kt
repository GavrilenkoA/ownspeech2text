package app.murmur.ui.design.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.Sizes

/**
 * Chip (DESIGN 4): 32 dp visual inside a 48 dp hit area, full radius, 1 dp line2 border, 13 sp / 500 in ink2.
 * [selected]: ink fill, bg text (filter chips). [dropdown]: the 36 dp variant with a trailing chevD (tone picker); a
 * dropdown chip is a button, not a toggle, so it ignores [selected].
 */
@Composable
fun MurmurChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dropdown: Boolean = false,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = MurmurTheme.colors
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val on = selected && !dropdown
    val visual = if (dropdown) 36.dp else Sizes.chip
    val fg = when {
        on -> c.bg
        dropdown -> c.ink
        else -> c.ink2
    }
    val click = if (dropdown) {
        Modifier.clickable(interaction, null, enabled = enabled, role = Role.DropdownList, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    // Corner radius fixed at half the visual height: a full pill at 100 % font scale that stays a rounded rectangle
    // when large text grows the chip (DESIGN 8, up to 200 %).
    val shape = RoundedCornerShape(visual / 2)
    Row(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(click)
            .padding(vertical = hitPad(visual))
            .widthIn(min = MinTouch)
            .heightIn(min = visual)
            .clip(shape)
            .background(if (on) c.ink else Color.Transparent)
            .border(1.dp, if (on) c.ink else c.line2, shape)
            .indication(interaction, LocalIndication.current)
            .padding(start = if (leadingIcon != null) 10.dp else 12.dp, end = if (dropdown) 8.dp else 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, null, tint = fg, modifier = Modifier.size(Sizes.iconSm))
        Text(label, style = MurmurTheme.type.chip, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (dropdown) Icon(MurmurIcons.chevD, null, tint = c.ink2, modifier = Modifier.size(Sizes.iconSm))
    }
}

/** Badge variants (DESIGN 4). */
enum class BadgeKind {
    /** `NPU`: accentSoft / accentInk. */
    Npu,
    /** `GPU`, `CPU`, quantisation: surface2 / ink2. */
    Neutral,
    /** Language codes: 1 dp line2 outline, ink2. */
    Language,
    /** `No Russian`: warnSoft / warn. */
    NoRussian,
    /** `In training`: surface2 / ink2. */
    InTraining,
}

/** 20 dp badge (taller only when large text needs it), xs radius, monoBadge type (DESIGN 4). Not interactive. */
@Composable
fun MurmurBadge(text: String, kind: BadgeKind, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    val (bg, fg) = when (kind) {
        BadgeKind.Npu -> c.accentSoft to c.accentInk
        BadgeKind.Neutral, BadgeKind.InTraining -> c.surface2 to c.ink2
        BadgeKind.Language -> Color.Transparent to c.ink2
        BadgeKind.NoRussian -> c.warnSoft to c.warn
    }
    Row(
        modifier
            .heightIn(min = 20.dp)
            .clip(MurmurShapes.xs)
            .background(bg)
            .then(if (kind == BadgeKind.Language) Modifier.border(1.dp, c.line2, MurmurShapes.xs) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MurmurTheme.type.monoBadge, color = fg, maxLines = 1)
    }
}

/** The `No Russian` badge with its copy-deck text. */
@Composable
fun NoRussianBadge(modifier: Modifier = Modifier) =
    MurmurBadge(stringResource(R.string.design_no_russian), BadgeKind.NoRussian, modifier)

/** The `In training` badge with its copy-deck text. */
@Composable
fun InTrainingBadge(modifier: Modifier = Modifier) =
    MurmurBadge(stringResource(R.string.design_in_training), BadgeKind.InTraining, modifier)

/** In-use pill (DESIGN 4): 24 dp (grows with large text), 12 dp radius, accent fill, bold check plus "In use", 11 sp / 600. */
@Composable
fun InUsePill(modifier: Modifier = Modifier, text: String = stringResource(R.string.design_in_use)) {
    val c = MurmurTheme.colors
    Row(
        modifier
            .heightIn(min = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.accent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MurmurIcons.bold("check"), null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        Text(text, style = MurmurTheme.type.pill, color = c.onAccent, maxLines = 1)
    }
}

/** Status tone for [StatusLabel]. */
enum class StatusKind { Ok, Warn, Err }

/**
 * Icon + word status in labelSm (History "Copied"/"Failed", Settings permission states). DESIGN 8: status is never
 * shown by colour alone, so pass an [icon] unless the word itself is the status ("Restricted").
 */
@Composable
fun StatusLabel(text: String, kind: StatusKind, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = MurmurTheme.colors
    val color = when (kind) {
        StatusKind.Ok -> c.ok
        StatusKind.Warn -> c.warn
        StatusKind.Err -> c.err
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MurmurTheme.type.labelSm, color = color, maxLines = 1)
    }
}
