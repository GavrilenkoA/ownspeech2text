package app.murmur.ui.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.Motion
import app.murmur.ui.design.MurmurColors
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.Sizes

/** Divider inset when the group's rows carry a 24 dp leading icon (16 padding + 24 icon + 14 gap). */
val DividerInsetIcon: Dp = 54.dp

/** Divider inset of plain rows. */
val DividerInsetPlain: Dp = 16.dp

/** Builder for [GroupedList]: one entry per row; [selected] rows hide the dividers next to them (prototype .sel). */
class GroupedListScope internal constructor() {
    internal class Item(val selected: Boolean, val content: @Composable () -> Unit)
    internal val items = mutableListOf<Item>()

    fun item(selected: Boolean = false, content: @Composable () -> Unit) {
        items += Item(selected, content)
    }
}

/**
 * Grouped list (DESIGN 4): one `surface` with lg radius and 1 dp `line` dividers between rows, inset [dividerInset]
 * on the start side (16 dp, or [DividerInsetIcon] for rows with leading icons) and [dividerEndInset] on the end side.
 * No cards inside cards.
 */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    dividerInset: Dp = DividerInsetPlain,
    dividerEndInset: Dp = 0.dp,
    content: GroupedListScope.() -> Unit,
) {
    val items = GroupedListScope().apply(content).items
    val c = MurmurTheme.colors
    Column(
        modifier
            .clip(MurmurShapes.lg)
            .background(c.surface),
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0 && !item.selected && !items[i - 1].selected) {
                InsetDivider(start = dividerInset, end = dividerEndInset)
            }
            item.content()
        }
    }
}

/** 1 dp `line` divider with start/end insets. */
@Composable
fun InsetDivider(modifier: Modifier = Modifier, start: Dp = 0.dp, end: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .height(1.dp)
            .background(MurmurTheme.colors.line),
    )
}

/** Section label above a group (prototype .sec-l: 13 sp / 600 in ink3, 20/20/8 padding). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
) {
    Text(
        text,
        modifier = modifier.padding(contentPadding),
        style = MurmurTheme.type.rowSub.copy(fontWeight = MurmurTheme.type.titleSm.fontWeight),
        color = MurmurTheme.colors.ink3,
    )
}

/** Overline (DESIGN 3.2): 11 sp / 600, 0.8 tracking, uppercase, ink3 ("TODAY", "CONTEXT USED"). */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MurmurTheme.colors.ink3) {
    Text(MurmurTheme.type.transform("overline", text), modifier = modifier, style = MurmurTheme.type.overline, color = color)
}

/** What sits at the end of a [ListRow]. */
sealed interface RowTrailing {
    /** Nothing. */
    data object None : RowTrailing
    /** Optional value text (ink3) and a chevR: the row opens a sub-screen or sheet. */
    data class Chevron(val value: String? = null) : RowTrailing
    /** A value only (e.g. "On" in ok). */
    data class Value(val text: String, val color: Color? = null) : RowTrailing
    /** A switch; the whole row toggles it (DESIGN 4: the whole row is the touch target). */
    data class Switch(val checked: Boolean) : RowTrailing
    /** A radio; the whole row selects it. */
    data class Radio(val selected: Boolean) : RowTrailing
    /** A status word with an icon (Settings → Permissions). */
    data class Status(val text: String, val kind: StatusKind, val icon: ImageVector? = null) : RowTrailing
}

/**
 * Grouped list row (DESIGN 4): >= 56 dp, 16 dp padding (10 dp vertical), 14 dp gaps; optional 22 dp leading icon in
 * ink2 (or any [leading] content such as an app tile), title in rowTitle, subtitle in rowSub/ink3. The whole row is
 * the touch target; pressed = ink at 6 %. [selected] fills the row with accentSoft (subtitle switches to ink2 for
 * contrast). [titleColor] = err for destructive rows ("Delete all history").
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: RowTrailing = RowTrailing.None,
    onClick: (() -> Unit)? = null,
    titleColor: Color? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = Sizes.listRowMin,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val click = when {
        onClick == null -> Modifier
        trailing is RowTrailing.Switch ->
            Modifier.toggleable(trailing.checked, interaction, indication, enabled = enabled, role = Role.Switch) { onClick() }
        trailing is RowTrailing.Radio ->
            Modifier.selectable(trailing.selected, interaction, indication, enabled = enabled, role = Role.RadioButton) { onClick() }
        else -> Modifier.clickable(interaction, indication, enabled = enabled, role = Role.Button) { onClick() }
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(rowBackground(c, true)) else Modifier)
            .then(click)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else if (leadingIcon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(leadingIcon, null, tint = c.ink2, modifier = Modifier.size(22.dp))
            }
        }
        TitleAndTrailing(
            title = {
                Column {
                    Text(title, style = t.rowTitle, color = titleColor ?: c.ink)
                    if (subtitle != null) {
                        Text(
                            subtitle, style = t.rowSub, color = rowSecondaryInk(c, selected),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            },
            trailing = if (trailing == RowTrailing.None) null else {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RowTrailingContent(trailing, selected)
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Title column and trailing content of a row, 14 dp apart. The trailing part keeps its full width when everything
 * fits (as a plain Row would). When it does not (large text, narrow panes), the trailing part yields down to its
 * longest word before the title has to break inside one; only if even the two longest words do not fit side by side
 * does the title break, while the trailing part keeps its longest word (up to 60 % of the width).
 */
@Composable
private fun TitleAndTrailing(title: @Composable () -> Unit, trailing: (@Composable () -> Unit)?, modifier: Modifier) {
    Layout(
        contents = listOf(title, trailing ?: {}),
        modifier = modifier,
    ) { (titleM, trailingM), constraints ->
        val tm = titleM.single()
        val rm = trailingM.singleOrNull()
        if (rm == null) {
            val p = tm.measure(constraints.copy(minWidth = constraints.maxWidth))
            return@Layout layout(constraints.maxWidth, p.height) { p.place(0, 0) }
        }
        val gap = 14.dp.roundToPx()
        val avail = (constraints.maxWidth - gap).coerceAtLeast(0)
        val trailingW = trailingWidth(
            avail,
            titleMin = tm.minIntrinsicWidth(Constraints.Infinity), titleMax = tm.maxIntrinsicWidth(Constraints.Infinity),
            trailingMin = rm.minIntrinsicWidth(Constraints.Infinity), trailingMax = rm.maxIntrinsicWidth(Constraints.Infinity),
        )
        val rp = rm.measure(Constraints(maxWidth = trailingW))
        val tp = tm.measure(Constraints(minWidth = avail - trailingW, maxWidth = avail - trailingW))
        val h = maxOf(tp.height, rp.height, constraints.minHeight)
        layout(constraints.maxWidth, h) {
            tp.place(0, (h - tp.height) / 2)
            rp.place(constraints.maxWidth - rp.width, (h - rp.height) / 2)
        }
    }
}

/** Width of a row's trailing part; see [TitleAndTrailing]. Min = longest word, max = one line. */
internal fun trailingWidth(avail: Int, titleMin: Int, titleMax: Int, trailingMin: Int, trailingMax: Int): Int {
    if (avail <= 0) return 0
    // Everything on one line, or the title can wrap between words while the trailing part stays whole.
    if (titleMax + trailingMax <= avail || titleMin + trailingMax <= avail) return trailingMax.coerceAtMost(avail)
    // Trailing wraps between its words; it takes what the title's longest word leaves, at least its own longest.
    if (titleMin + trailingMin <= avail) return (avail - titleMin).coerceIn(trailingMin, trailingMax)
    // Some word must break: keep the trailing value's longest word whole (a cut status or value reads worse than a
    // wrapped title), but leave the title at least 40 %.
    return trailingMin.coerceAtMost(avail * 3 / 5)
}

/**
 * Secondary text and glyphs of a row (subtitle, trailing value, chevron): ink3, or ink2 on a selected (accentSoft)
 * row, where ink3 is 4.1:1 in the light theme and fails DESIGN 8's 4.5:1.
 */
internal fun rowSecondaryInk(c: MurmurColors, selected: Boolean): Color = if (selected) c.ink2 else c.ink3

/** Background of a row: accentSoft when selected, else the group's surface. */
internal fun rowBackground(c: MurmurColors, selected: Boolean): Color = if (selected) c.accentSoft else c.surface

@Composable
private fun RowScope.RowTrailingContent(trailing: RowTrailing, selected: Boolean) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val secondary = rowSecondaryInk(c, selected)
    when (trailing) {
        RowTrailing.None -> Unit
        is RowTrailing.Chevron -> {
            if (trailing.value != null) {
                Text(trailing.value, style = t.body, color = secondary)
            }
            Icon(MurmurIcons.chevR, null, tint = secondary, modifier = Modifier.size(20.dp))
        }
        is RowTrailing.Value -> Text(trailing.text, style = t.body, color = trailing.color ?: secondary)
        is RowTrailing.Switch -> MurmurSwitch(trailing.checked, onCheckedChange = null)
        is RowTrailing.Radio -> MurmurRadio(trailing.selected, onClick = null)
        is RowTrailing.Status -> StatusLabel(trailing.text, trailing.kind, icon = trailing.icon)
    }
}

/**
 * Switch (DESIGN 4): 52 × 32 track with a 2 dp border. Off: surface3, line2 border, ink3 thumb. On: accent, onAccent
 * thumb. 20 dp thumb. Pass [onCheckedChange] = null inside a row that toggles it (the row is the target); standalone
 * the hit area is padded to 48 dp.
 */
@Composable
fun MurmurSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MurmurTheme.colors
    val reduced = MurmurTheme.reducedMotion
    val spec = tween<Dp>(if (reduced) 0 else Motion.shortMs)
    val thumbX by animateDpAsState(if (checked) 24.dp else 4.dp, spec, label = "thumb")
    val track by animateColorAsState(if (checked) c.accent else c.surface3, tween(Motion.shortMs), label = "track")
    val border by animateColorAsState(if (checked) c.accent else c.line2, tween(Motion.shortMs), label = "border")
    val thumb by animateColorAsState(if (checked) c.onAccent else c.ink3, tween(Motion.shortMs), label = "thumbColor")
    val toggle = if (onCheckedChange != null) {
        Modifier
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = hitPad(Sizes.toggleH))
    } else Modifier
    Box(
        modifier
            .then(toggle)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(Sizes.toggleW, Sizes.toggleH)
            .clip(MurmurShapes.full)
            .background(track)
            .border(2.dp, border, MurmurShapes.full),
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .offset { IntOffset((thumbX + 2.dp).roundToPx(), 0) }
                .size(20.dp)
                .clip(MurmurShapes.full)
                .background(thumb),
        )
    }
}

/**
 * Radio (DESIGN 4): 22 dp, 2 dp border (line2 off, accent on), accent dot. The dot is 10 dp as rendered in the frames
 * (`retention--cover.png`; DESIGN 4's text says 12 dp). Pass [onClick] = null inside a selectable row.
 */
@Composable
fun MurmurRadio(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MurmurTheme.colors
    val ring by animateColorAsState(if (selected) c.accent else c.line2, tween(Motion.shortMs), label = "radio")
    val select = if (onClick != null) {
        Modifier
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(hitPad(Sizes.radio))
    } else Modifier
    Box(
        modifier
            .then(select)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(Sizes.radio)
            .border(2.dp, ring, MurmurShapes.full),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(MurmurShapes.full).background(c.accent))
    }
}
