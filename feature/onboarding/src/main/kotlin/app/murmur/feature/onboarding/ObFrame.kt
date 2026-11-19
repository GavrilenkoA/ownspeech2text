package app.murmur.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.core.model.ProductName
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.MurmurButton

/** The five checklist entries of the inner display (prototype OB_STEPS) and the cover kicker names. */
internal data class ChecklistItem(val icon: ImageVector, val title: Int, val sub: Int)

internal val CHECKLIST = listOf(
    ChecklistItem(MurmurIcons.mic, R.string.ob_list_mic, R.string.ob_list_mic_sub),
    ChecklistItem(MurmurIcons.a11y, R.string.ob_list_access, R.string.ob_list_access_sub),
    ChecklistItem(MurmurIcons.bell, R.string.ob_list_notif, R.string.ob_list_notif_sub),
    ChecklistItem(MurmurIcons.batt, R.string.ob_list_battery, R.string.ob_list_battery_sub),
    ChecklistItem(MurmurIcons.models, R.string.ob_list_models, R.string.ob_list_models_sub),
)

/**
 * The onboarding frame (prototype obFrame). Cover (compact width): one column, 24 dp gutters, the dots and "n of 5" at
 * the top (not on the welcome screen), the content scrolling, the call to action pinned to the bottom (thumb zone).
 * Inner (medium+): a 300 dp surface panel with the whole checklist and a centred content column of at most 470 dp.
 * [dotIndex] = -1 for the welcome screen (no progress shown, every checklist entry pending).
 */
@Composable
internal fun ObFrame(
    wide: Boolean,
    dotIndex: Int,
    testTag: String,
    foot: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MurmurTheme.colors
    if (!wide) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
                .testTag(testTag),
        ) {
            if (dotIndex >= 0) DotsRow(dotIndex)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = foot)
        }
    } else {
        Row(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag(testTag),
        ) {
            Checklist(dotIndex)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 470.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp),
                ) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = foot)
                }
            }
        }
    }
}

/** Cover progress: 5 dots (current = 20 dp ink pill, done = ink3) and "n of 5" / "Done". */
@Composable
private fun DotsRow(index: Int) {
    val c = MurmurTheme.colors
    val total = CHECKLIST.size
    val cd = stringResource(R.string.ob_step_counter_cd, (index + 1).coerceAtMost(total), total)
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.semantics { contentDescription = cd }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { i ->
                val on = i == index
                Box(
                    Modifier
                        .size(width = if (on) 20.dp else 6.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (on) c.ink else if (i < index) c.ink3 else c.line2),
                )
            }
        }
        Text(
            if (index >= total) stringResource(R.string.ob_counter_done) else stringResource(R.string.ob_step_counter, index + 1, total),
            style = MurmurTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = c.ink3,
        )
    }
}

/** Inner left panel (prototype .ob-l): mark + product name, the five steps (done ✓ / current ink / pending outline). */
@Composable
private fun Checklist(index: Int) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(size.width - 0.5.dp.toPx(), 0f), Offset(size.width - 0.5.dp.toPx(), size.height), 1.dp.toPx()) }
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .testTag("ob-checklist"),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(MurmurIcons.mark, null, tint = c.ink, modifier = Modifier.size(30.dp))
            Text(ProductName.NAME, style = t.title, color = c.ink)
        }
        Column {
            CHECKLIST.forEachIndexed { i, item ->
                val state = when {
                    index < 0 -> StepMark.TODO
                    i < index -> StepMark.DONE
                    i == index -> StepMark.NOW
                    else -> StepMark.TODO
                }
                ChecklistRow(i, item, state)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.ob_inner_footer), style = t.caption, color = c.ink3)
    }
}

private enum class StepMark { DONE, NOW, TODO }

@Composable
private fun ChecklistRow(i: Int, item: ChecklistItem, mark: StepMark) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val title = stringResource(item.title)
    val cd = when (mark) {
        StepMark.DONE -> stringResource(R.string.ob_list_done_cd, title)
        StepMark.NOW -> stringResource(R.string.ob_list_now_cd, title)
        StepMark.TODO -> title
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val circle = Modifier.size(28.dp).clip(CircleShape)
        when (mark) {
            StepMark.DONE -> Box(circle.background(c.okSoft), contentAlignment = Alignment.Center) {
                Icon(MurmurIcons.bold("check"), null, tint = c.ok, modifier = Modifier.size(16.dp))
            }
            StepMark.NOW -> Box(circle.background(c.ink), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = t.label.copy(fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold), color = c.bg)
            }
            StepMark.TODO -> Box(Modifier.size(28.dp).border(1.5.dp, c.line2, CircleShape), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = t.label.copy(fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold), color = c.ink3)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.rowTitle, color = if (mark == StepMark.TODO) c.ink3 else c.ink)
            Text(stringResource(item.sub), style = t.rowSub, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ------------------------------------------------------------------ content pieces (prototype .ob children)

/** 64 dp accentSoft tile with a 32 dp accentInk icon, 28 dp above and 24 dp below. */
@Composable
internal fun Ill(icon: ImageVector) {
    val c = MurmurTheme.colors
    Box(
        Modifier
            .padding(top = 28.dp, bottom = 24.dp)
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = c.accentInk, modifier = Modifier.size(32.dp))
    }
}

/** "Step 1 · Microphone" (13/18 medium, ink3, 8 dp below). [top] = 20 dp on the steps without an illustration. */
@Composable
internal fun Kicker(text: String, top: Dp = 0.dp) {
    Text(
        text,
        style = MurmurTheme.type.rowSub.copy(fontWeight = FontWeight.Medium),
        color = MurmurTheme.colors.ink3,
        modifier = Modifier.padding(top = top, bottom = 8.dp),
    )
}

@Composable
internal fun ObTitle(text: String, modifier: Modifier = Modifier, size: Int? = null, line: Int? = null) {
    val base = MurmurTheme.type.onboardingTitle
    val style = if (size != null && line != null) base.copy(fontSize = size.sp, lineHeight = line.sp) else base
    Text(
        text,
        style = style.copy(lineBreak = LineBreak.Heading),
        color = MurmurTheme.colors.ink,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
internal fun Lead(text: String) {
    Text(
        text,
        style = MurmurTheme.type.bodyLg.copy(lineBreak = LineBreak.Paragraph),
        color = MurmurTheme.colors.ink2,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** The "why" list: 22 dp ink2 icons, 15/22 text, 14 dp apart. */
@Composable
internal fun Why(items: List<Pair<ImageVector, String>>, top: Dp = 24.dp) {
    val c = MurmurTheme.colors
    Column(Modifier.padding(top = top), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = c.ink2, modifier = Modifier.padding(top = 0.dp).size(22.dp))
                Text(text, style = MurmurTheme.type.entry, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The "On the next screen" box: md radius, 1 dp line, surface, 14/16 padding. */
@Composable
internal fun SysBox(header: String, top: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = MurmurTheme.colors
    Column(
        Modifier
            .padding(top = top)
            .fillMaxWidth()
            .clip(MurmurShapes.md)
            .background(c.surface)
            .border(1.dp, c.line, MurmurShapes.md)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(header, style = MurmurTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = c.ink3)
        content()
    }
}

/** A string resource with &lt;b> spans, as an AnnotatedString (bold parts of the system-path boxes). */
@Composable
internal fun htmlString(id: Int, vararg args: Any): AnnotatedString = AnnotatedString.fromHtml(stringResource(id, *args))

/** Footnote under the call to action (13/18 ink3, centred). */
@Composable
internal fun Fine(text: String) {
    Text(
        text,
        style = MurmurTheme.type.rowSub,
        color = MurmurTheme.colors.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
    )
}

@Composable
internal fun PrimaryCta(text: String, onClick: () -> Unit, tag: String, icon: ImageVector? = null, enabled: Boolean = true, loading: Boolean = false) {
    MurmurButton(
        text, onClick, Modifier.fillMaxWidth().testTag(tag),
        style = ButtonStyle.Primary, leadingIcon = icon, enabled = enabled, loading = loading,
    )
}

@Composable
internal fun SecondaryCta(text: String, onClick: () -> Unit, tag: String, style: ButtonStyle = ButtonStyle.Text, icon: ImageVector? = null) {
    MurmurButton(text, onClick, Modifier.fillMaxWidth().testTag(tag), style = style, leadingIcon = icon)
}
