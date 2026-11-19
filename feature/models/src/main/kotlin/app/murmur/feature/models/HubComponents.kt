package app.murmur.feature.models

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.core.model.ComputeUnit
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.Sizes
import app.murmur.ui.design.components.BadgeKind
import app.murmur.ui.design.components.MinTouch
import app.murmur.ui.design.components.MurmurBadge
import app.murmur.ui.design.components.Spinner

/**
 * Download pill of a model row (prototype .dlbtn): 36 dp visual inside a 48 dp hit area, full radius, 1 dp line2
 * border, dl glyph 18 dp, 13 sp / 500 label. Grows with large text (the radius stays 18 dp).
 */
@Composable
internal fun DownloadPill(label: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(vertical = 6.dp)
            .widthIn(min = MinTouch)
            .heightIn(min = 36.dp)
            .clip(shape)
            .border(1.dp, c.line2, shape)
            .indication(interaction, LocalIndication.current)
            .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MurmurIcons.dl, null, tint = c.ink, modifier = Modifier.size(18.dp))
        Text(label, style = MurmurTheme.type.label.copy(fontSize = 13.sp, lineHeight = 16.sp), color = c.ink, maxLines = 1)
    }
}

/** Unit badge: the chosen unit filled (NPU accentSoft, GPU/CPU surface2), other usable units outlined. */
@Composable
internal fun UnitBadgeView(unit: ComputeUnit, chosen: Boolean) {
    val kind = when {
        !chosen -> BadgeKind.Language
        unit == ComputeUnit.NPU -> BadgeKind.Npu
        else -> BadgeKind.Neutral
    }
    MurmurBadge(HubTexts.unit(unit), kind)
}

/** One row of [KvTable]. [onClick] makes the value a link (Source); [mono] = prototype `.v.mono` (12 sp). */
internal class KvRow(val key: String, val value: String, val mono: Boolean = false, val onClick: (() -> Unit)? = null, val valueTag: String? = null)

/**
 * Key-value table (prototype .kv): keys in ink3 at their natural width (the widest key sets the column, at most half
 * the table), a 16 dp gap, values right-aligned in ink; 12 dp vertical padding; a 1 dp `line` above every row but the
 * first, drawn under each column separately so the gap stays open as in the frames. A clickable value is a 48 dp
 * target.
 */
@Composable
internal fun KvTable(rows: List<KvRow>, modifier: Modifier = Modifier, valueStyle: TextStyle = MurmurTheme.type.body) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    Layout(
        content = {
            rows.forEach { r ->
                Text(r.key, style = t.body, color = c.ink3, modifier = Modifier.padding(vertical = 12.dp))
                val vs = if (r.mono) t.mono.copy(lineHeight = 20.sp) else valueStyle
                Box(
                    Modifier
                        .then(if (r.onClick != null) Modifier.clickable(role = Role.Button, onClick = r.onClick) else Modifier)
                        .then(if (r.valueTag != null) Modifier.testTag(r.valueTag) else Modifier)
                        .heightIn(min = if (r.onClick != null) MinTouch else 0.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(r.value, style = vs, color = c.ink, textAlign = TextAlign.End, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            repeat((rows.size - 1).coerceAtLeast(0) * 2) { Box(Modifier.background(c.line)) }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val n = rows.size
        val gap = 16.dp.roundToPx()
        val line = 1.dp.roundToPx()
        val keys = (0 until n).map { measurables[2 * it] }
        val values = (0 until n).map { measurables[2 * it + 1] }
        val lines = measurables.drop(2 * n)
        val maxKey = keys.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val keyW = minOf(maxKey, (constraints.maxWidth - gap) / 2).coerceAtLeast(0)
        val valueW = (constraints.maxWidth - gap - keyW).coerceAtLeast(0)
        val kp = keys.map { it.measure(Constraints.fixedWidth(keyW)) }
        val vp = values.map { it.measure(Constraints.fixedWidth(valueW)) }
        val heights = (0 until n).map { maxOf(kp[it].height, vp[it].height) }
        val lp = lines.mapIndexed { i, m -> m.measure(Constraints.fixed(if (i % 2 == 0) keyW else valueW, line)) }
        layout(constraints.maxWidth, heights.sum()) {
            var y = 0
            for (i in 0 until n) {
                if (i > 0) {
                    lp[2 * (i - 1)].place(0, y)
                    lp[2 * (i - 1) + 1].place(keyW + gap, y)
                }
                kp[i].place(0, y)
                vp[i].place(keyW + gap, y + (heights[i] - vp[i].height) / 2)
                y += heights[i]
            }
        }
    }
}

/** One cell of the 2 × 2 result grid: big number, small unit, caption. */
internal class Big4Cell(val number: String, val unit: String, val label: String)

/**
 * Result grid (prototype .big4): two columns, 1 dp `line` between cells, md radius; cells on `surface` with 14/16 dp
 * padding, 26/30 sp numbers followed by a 14 sp ink3 unit, 12 sp ink3 captions.
 */
@Composable
internal fun Big4(cells: List<Big4Cell>, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val numberStyle = t.title.copy(fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum")
    Column(
        modifier
            .fillMaxWidth()
            .clip(MurmurShapes.md)
            .background(c.line),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                pair.forEach { cell ->
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(c.surface)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .semantics(mergeDescendants = true) { },
                    ) {
                        Text(
                            buildAnnotatedString {
                                append(cell.number)
                                if (cell.unit.isNotEmpty()) {
                                    withStyle(SpanStyle(fontSize = 14.sp, fontWeight = t.label.fontWeight, color = c.ink3, letterSpacing = 0.sp)) {
                                        // prototype: <small> with margin-left 3px; "14×" has no unit gap.
                                        append(if (cell.unit == "×") cell.unit else " " + cell.unit)
                                    }
                                }
                            },
                            style = numberStyle, color = c.ink,
                        )
                        Text(cell.label, style = t.caption, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f).fillMaxHeight().background(c.surface))
            }
        }
    }
}

/** Step indicator of the benchmark checklist (prototype .step .ic): 24 dp circle. */
@Composable
internal fun StepIcon(status: StepStatus) {
    val c = MurmurTheme.colors
    val shape = MurmurShapes.full
    when (status) {
        StepStatus.DONE -> Box(Modifier.size(24.dp).clip(shape).background(c.okSoft), contentAlignment = Alignment.Center) {
            Icon(MurmurIcons.bold("check"), null, tint = c.ok, modifier = Modifier.size(14.dp))
        }
        StepStatus.NOW -> Box(Modifier.size(24.dp).clip(shape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Spinner(color = c.accentInk, size = Sizes.iconSm)
        }
        StepStatus.FAILED -> Box(Modifier.size(24.dp).clip(shape).background(c.errSoft), contentAlignment = Alignment.Center) {
            Icon(MurmurIcons.bold("x"), null, tint = c.err, modifier = Modifier.size(14.dp))
        }
        StepStatus.TODO -> Box(Modifier.size(24.dp).clip(shape).border(1.5.dp, c.line2, shape))
    }
}

/**
 * Text field of the "Add a model" sheet (no frame; built from the search field's tokens): label in labelSm ink2, a
 * 48 dp surface2 field with md radius and 16 dp padding, hint in ink2; focused = surface with a 2 dp ink ring; an
 * error draws an err ring and the message below.
 */
@Composable
internal fun HubTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    fieldTag: String? = null,
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = t.labelSm, color = c.ink2)
        val ring = when {
            error != null -> Modifier.border(2.dp, c.err, MurmurShapes.md)
            focused -> Modifier.border(2.dp, c.ink, MurmurShapes.md)
            else -> Modifier
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = t.entry.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .heightIn(min = Sizes.search)
                .clip(MurmurShapes.md)
                .background(if (focused) c.surface else c.surface2)
                .then(ring)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .then(if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier),
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = Sizes.search).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, style = t.entry, color = c.ink2, maxLines = 1)
                    inner()
                }
            },
        )
        if (error != null) Text(error, style = t.caption, color = c.err, modifier = Modifier.padding(top = 6.dp))
    }
}
