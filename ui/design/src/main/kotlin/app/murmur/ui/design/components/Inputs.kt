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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.Motion
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.Sizes
import app.murmur.ui.design.elevation

/**
 * Segmented control (DESIGN 4): 40 dp surface2 track with 3 dp padding, 34 dp segments, the selected one a surface
 * fill with e1. Each segment's touch target is 48 dp tall, so the control occupies 48 dp with the 40 dp track centred
 * (4 dp of transparent margin above and below the track). Segments are equally wide; when large text (DESIGN 8) does
 * not fit an equal share, a longer label gets the width it needs from the shorter ones, and only a label that still
 * does not fit wraps to a second line. Track and segments grow in height with the text.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MurmurTheme.colors
    val margin = (MinTouch - Sizes.segmented) / 2
    val trackShape = RoundedCornerShape(Sizes.segmented / 2)
    val segmentShape = RoundedCornerShape(SEGMENT_H / 2)
    Layout(
        content = {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val fg by animateColorAsState(if (selected) c.ink else c.ink2, tween(Motion.shortMs), label = "segFg")
                Box(
                    Modifier
                        .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(i) }
                        .padding(vertical = margin + SEGMENT_TRACK_PAD),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .heightIn(min = SEGMENT_H)
                            .then(if (selected) Modifier.elevation(Elevation.e1, segmentShape) else Modifier)
                            .clip(segmentShape)
                            .then(if (selected) Modifier.background(c.surface) else Modifier)
                            .indication(interaction, LocalIndication.current)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, style = MurmurTheme.type.segment, color = fg, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val top = margin.toPx()
                val outline = trackShape.createOutline(Size(size.width, size.height - 2 * top), layoutDirection, this)
                translate(top = top) { drawOutline(outline, c.surface2) }
            }
            .padding(horizontal = SEGMENT_TRACK_PAD)
            .selectableGroup(),
    ) { measurables, constraints ->
        val n = measurables.size
        val gap = SEGMENT_GAP.roundToPx()
        val avail = (constraints.maxWidth - gap * (n - 1).coerceAtLeast(0)).coerceAtLeast(0)
        val widths = distributeWidths(IntArray(n) { measurables[it].maxIntrinsicWidth(Constraints.Infinity) }, avail)
        val h = maxOf(MinTouch.roundToPx(), (0 until n).maxOfOrNull { measurables[it].maxIntrinsicHeight(widths[it]) } ?: 0)
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], h)) }
        layout(constraints.maxWidth, h) {
            var x = 0
            placeables.forEach { it.place(x, 0); x += it.width + gap }
        }
    }
}

private val SEGMENT_H = 34.dp
private val SEGMENT_TRACK_PAD = 3.dp
private val SEGMENT_GAP = 2.dp

/**
 * Splits [total] px among items that would like [needs] px each: equal shares when every need fits its share;
 * otherwise items whose need exceeds the share get their need and the rest share what is left equally; if even that
 * cannot satisfy everyone, widths are proportional to the needs. The widths always add up to [total].
 */
internal fun distributeWidths(needs: IntArray, total: Int): IntArray {
    val n = needs.size
    if (n == 0) return IntArray(0)
    fun spread(amount: Int, idx: List<Int>, out: IntArray) {
        idx.forEachIndexed { k, i -> out[i] = amount / idx.size + if (k < amount % idx.size) 1 else 0 }
    }
    val out = IntArray(n)
    if (needs.sum() > total) {
        // Not everyone fits: proportional to need (largest remainders first, so the sum is exact).
        val sum = needs.sum().coerceAtLeast(1).toLong()
        val raw = LongArray(n) { needs[it].toLong() * total }
        for (i in 0 until n) out[i] = (raw[i] / sum).toInt()
        val order = (0 until n).sortedByDescending { raw[it] % sum }
        repeat(total - out.sum()) { out[order[it % n]]++ }
        return out
    }
    val fixed = BooleanArray(n)
    while (true) {
        val free = (0 until n).filter { !fixed[it] }
        val left = total - (0 until n).filter { fixed[it] }.sumOf { needs[it] }
        val share = left / free.size
        val grow = free.filter { needs[it] > share }
        if (grow.isEmpty() || grow.size == free.size) {
            for (i in 0 until n) if (fixed[i]) out[i] = needs[i]
            spread(left, free, out)
            return out
        }
        grow.forEach { fixed[it] = true }
    }
}

/**
 * Search field (DESIGN 4, History search): 48 dp (taller with large text), full radius.
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
    // A full pill at 48 dp; the radius stays 24 dp if large text makes the field taller.
    val shape = RoundedCornerShape(Sizes.search / 2)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active && onBack != null) {
            MurmurIconButton(MurmurIcons.back, stringResource(R.string.design_close_search), onBack, small = true, iconSize = Sizes.icon)
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Sizes.search)
                .clip(shape)
                .background(if (active) c.surface else c.surface2)
                .then(if (active) Modifier.border(2.dp, c.ink, shape) else Modifier)
                .padding(end = if (active && query.isNotEmpty()) 2.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The text field spans the whole pill (minus the clear button), so the full height focuses it.
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
                    .heightIn(min = Sizes.search)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused && !active) onActivate() },
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .heightIn(min = Sizes.search)
                            .padding(start = 16.dp, end = if (active && query.isNotEmpty()) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!active) Icon(MurmurIcons.search, null, tint = c.ink3, modifier = Modifier.size(20.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                // Two lines only when large text needs them (DESIGN 8); at 100 % the hints fit one.
                                Text(hint, style = textStyle, color = if (active) c.ink3 else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
