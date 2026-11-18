package app.murmur.ui.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.Motion
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.Sizes
import app.murmur.ui.design.elevation

/**
 * Segmented control (DESIGN 4): 40 dp surface2 track with 3 dp padding, 34 dp segments, the selected one a surface
 * fill with e1. Each segment's touch target is 48 dp tall, so the control occupies 48 dp with the 40 dp track centred
 * (4 dp of transparent margin above and below the track).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MurmurTheme.colors
    Box(modifier.height(MinTouch), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Sizes.segmented)
                .clip(MurmurShapes.full)
                .background(c.surface2),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 3.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val fg by animateColorAsState(if (selected) c.ink else c.ink2, tween(Motion.shortMs), label = "segFg")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .then(if (selected) Modifier.elevation(Elevation.e1, MurmurShapes.full) else Modifier)
                            .clip(MurmurShapes.full)
                            .then(if (selected) Modifier.background(c.surface) else Modifier)
                            .indication(interaction, LocalIndication.current)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MurmurTheme.type.segment, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Search field (DESIGN 4, History search): 48 dp, full radius.
 * Idle ([active] false): surface2 with a search glyph and the hint. Active: surface with a 2 dp ink ring, a back
 * arrow before the field ([onBack]) and a clear (×) button while there is text. The hint is ink2 when idle (on
 * surface2 ink3 is 4.2:1, below DESIGN 8's 4.5:1) and ink3 when active (4.8:1 on surface).
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onActivate: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSearch: () -> Unit = {},
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val textStyle = t.entry.copy(color = c.ink, lineHeight = t.rowTitle.lineHeight)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active && onBack != null) {
            MurmurIconButton(MurmurIcons.back, stringResource(R.string.design_close_search), onBack, small = true, iconSize = Sizes.icon)
        }
        Row(
            Modifier
                .weight(1f)
                .height(Sizes.search)
                .clip(MurmurShapes.full)
                .background(if (active) c.surface else c.surface2)
                .then(if (active) Modifier.border(2.dp, c.ink, MurmurShapes.full) else Modifier)
                .padding(end = if (active && query.isNotEmpty()) 2.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The text field spans the whole pill (minus the clear button), so the full 48 dp height focuses it.
            BasicTextField(
                value = query,
                onValueChange = { if (!active) onActivate(); onQueryChange(it) },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused && !active) onActivate() },
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .padding(start = 16.dp, end = if (active && query.isNotEmpty()) 4.dp else 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!active) Icon(MurmurIcons.search, null, tint = c.ink3, modifier = Modifier.size(20.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(hint, style = textStyle, color = if (active) c.ink3 else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    }
                },
            )
            if (active && query.isNotEmpty()) {
                MurmurIconButton(MurmurIcons.x, stringResource(R.string.design_clear_search), { onQueryChange("") }, small = true)
            }
        }
    }
}
