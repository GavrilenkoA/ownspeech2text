package app.murmur.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.core.model.ProductName
import app.murmur.ui.design.AppInfoProvider
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.AppTile
import app.murmur.ui.design.components.Banner
import app.murmur.ui.design.components.BannerKind
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.MurmurButton
import app.murmur.ui.design.components.MurmurChip
import app.murmur.ui.design.components.Overline
import app.murmur.ui.design.components.ScreenHeader
import app.murmur.ui.design.components.Skeleton
import app.murmur.ui.design.elevation
import app.murmur.ui.design.rememberIsWide
import kotlin.math.roundToInt

/** Test tags of the dashboard (screenshot and UI tests). */
object DashboardTags {
    const val SCREEN = "screen-dashboard"
    const val RANGE = "dash-range"
    const val TODAY_CARD = "dash-today"
    const val CHART = "dash-chart"
    const val METRICS = "dash-metrics"
    const val LANGUAGES = "dash-languages"
    const val APPS = "dash-apps"
    const val SKELETON = "dash-skeleton"
    const val EMPTY = "dash-empty"
    const val ERROR = "dash-error"
    const val BUBBLE_OFF = "dash-bubble-off"
    const val TRY = "dash-try"
    const val SCROLL = "dash-scroll"
}

/** What the dashboard's controls do (stateless content, so screenshot tests can drive every state). */
@Immutable
class DashboardActions(
    val onToggleRange: () -> Unit,
    val onEditTypingSpeed: () -> Unit,
    val onTryDictation: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenAccessibility: () -> Unit,
)

/** Section gap (DESIGN 3.3: sections are 28 dp apart on Dashboard) and the inner column gap. */
private val SectionGap = 28.dp

/**
 * The dashboard below the shell's bars: header with the range chip, then the state. Cover (compact): one column,
 * 20 dp gutters. Inner (medium+): two columns 28 dp apart, 28 dp gutters; left Today, chart, languages, right
 * metrics and apps (DESIGN 6.2). Empty and loading keep the cover's single column on both displays (frames).
 */
@Composable
fun DashboardContent(
    state: DashboardState,
    texts: DashboardTexts?,
    emptyRows: List<MetricTexts>,
    bubbleOff: Boolean,
    apps: AppInfoProvider,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val c = MurmurTheme.colors
    val wide = rememberIsWide()
    val range = (state as? DashboardState.Ready)?.range ?: DashboardRange.Week
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag(DashboardTags.SCREEN),
    ) {
        ScreenHeader(stringResource(R.string.dash_title)) {
            RangeChip(range, actions.onToggleRange, enabled = true)
        }
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag(DashboardTags.SCROLL)) {
            val showOff = bubbleOff && state !is DashboardState.Loading
            when {
                state is DashboardState.Loading -> LoadingContent()
                state is DashboardState.Error -> ErrorContent(showOff, actions)
                state is DashboardState.Ready && (state.empty || texts == null) -> EmptyContent(showOff, emptyRows, actions)
                wide -> WideContent(texts!!, showOff, apps, actions)
                else -> CompactContent(texts!!, showOff, apps, actions)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------- layouts per state

@Composable
private fun CompactContent(t: DashboardTexts, bubbleOff: Boolean, apps: AppInfoProvider, actions: DashboardActions) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        if (bubbleOff) BubbleOffBanner(actions.onOpenAccessibility)
        TodayCard(t.today, actions.onEditTypingSpeed)
        WeekSection(t.week)
        MetricsSection(stringResource(R.string.dash_metrics_title), t.metrics)
        if (t.languages.isNotEmpty()) LanguagesSection(t.languages)
        if (t.apps.rows.isNotEmpty()) AppsSection(t.apps, apps)
    }
}

@Composable
private fun WideContent(t: DashboardTexts, bubbleOff: Boolean, apps: AppInfoProvider, actions: DashboardActions) {
    Column(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        if (bubbleOff) BubbleOffBanner(actions.onOpenAccessibility)
        Row(horizontalArrangement = Arrangement.spacedBy(SectionGap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SectionGap)) {
                TodayCard(t.today, actions.onEditTypingSpeed)
                WeekSection(t.week)
                if (t.languages.isNotEmpty()) LanguagesSection(t.languages)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SectionGap)) {
                MetricsSection(stringResource(R.string.dash_metrics_title), t.metrics)
                if (t.apps.rows.isNotEmpty()) AppsSection(t.apps, apps)
            }
        }
    }
}

@Composable
private fun EmptyContent(bubbleOff: Boolean, rows: List<MetricTexts>, actions: DashboardActions) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
            .testTag(DashboardTags.EMPTY),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        if (bubbleOff) BubbleOffBanner(actions.onOpenAccessibility)
        val c = MurmurTheme.colors
        Card {
            Overline(stringResource(R.string.dash_today))
            BigNumber(number = "0", unit = pluralWords(0), color = c.ink3, unitColor = c.ink3)
            Text(
                stringResource(R.string.dash_empty_body), style = MurmurTheme.type.body, color = c.ink2,
                modifier = Modifier.padding(top = 6.dp),
            )
            MurmurButton(
                stringResource(R.string.dash_try), actions.onTryDictation, style = ButtonStyle.Primary,
                leadingIcon = MurmurIcons.mic, modifier = Modifier.padding(top = 18.dp).testTag(DashboardTags.TRY),
            )
        }
        // The prototype's empty list has no 2 dp gap under its title (dash-empty).
        MetricsSection(stringResource(R.string.dash_empty_metrics), rows, titleGap = 0.dp)
    }
}

@Composable
private fun pluralWords(n: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.dash_words_unit, n)

@Composable
private fun ErrorContent(bubbleOff: Boolean, actions: DashboardActions) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (bubbleOff) BubbleOffBanner(actions.onOpenAccessibility)
        Banner(
            BannerKind.Info,
            title = stringResource(R.string.dash_error_title),
            body = stringResource(R.string.dash_error_body),
            actionLabel = stringResource(R.string.dash_retry),
            onAction = actions.onRetry,
            modifier = Modifier.testTag(DashboardTags.ERROR),
        )
    }
}

/** dash-loading: skeleton blocks in the geometry of the card, the chart and three metric rows. */
@Composable
private fun LoadingContent() {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag(DashboardTags.SKELETON)
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        Card(spacing = 12.dp) {
            Skeleton(Modifier.size(60.dp, 12.dp))
            Skeleton(Modifier.size(180.dp, 44.dp))
            Skeleton(Modifier.fillMaxWidth().height(14.dp))
            Skeleton(Modifier.fillMaxWidth(0.7f).height(14.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(60, 52, 20, 16, 80, 70, 40).forEach { h -> Skeleton(Modifier.weight(1f).height(h.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Skeleton(Modifier.fillMaxWidth(0.55f).height(16.dp))
                    Skeleton(Modifier.size(48.dp, 20.dp))
                }
            }
        }
    }
}

/**
 * CSS line box for one line of Onest: the browser that rendered the frames makes the box exactly the line height and
 * centres the font's ascent + descent in it (half-leading, negative when the font is taller than the line: Onest is
 * 1.275 em, so a 44 sp display in a 48 sp line, 20 sp values in 24 sp). Compose keeps the font's full ascent for
 * such lines and rounds the half-leading differently, which moved the metric rows by 1 px each. This places the text
 * so that its baseline sits where CSS puts it and reports the line height; baselines move with the text.
 */
private fun Modifier.cssLineBox(style: TextStyle): Modifier = layout { m, c ->
    val p = m.measure(c)
    val target = style.lineHeight.roundToPx()
    val size = style.fontSize.toPx()
    val baseline = p[FirstBaseline]
    // Wrapped text (large font scales) keeps Compose's layout; the line box applies to one line.
    if (p.height >= target * 3 / 2 || baseline == androidx.compose.ui.layout.AlignmentLine.Unspecified) {
        layout(p.width, p.height) { p.place(0, 0) }
    } else {
        val cssBaseline = (target - (ONEST_ASCENT + ONEST_DESCENT) * size) / 2f + ONEST_ASCENT * size
        layout(p.width, target) { p.place(0, cssBaseline.roundToInt() - baseline) }
    }
}

/** Onest's hhea / typo ascender and descender (970 / 305 per 1000 units, `ui/design/src/main/res/font`). */
private const val ONEST_ASCENT = 0.970f
private const val ONEST_DESCENT = 0.305f

// -------------------------------------------------------------------------------------------- header

/**
 * "Last 7 days" / "Today" (DESIGN 6.2). A tap switches to the other range. The chip keeps its 48 dp touch target
 * but takes only its 32 dp visual height in the header row, so the header stays 64 dp as in the frames.
 */
@Composable
private fun RangeChip(range: DashboardRange, onToggle: () -> Unit, enabled: Boolean) {
    val current = stringResource(if (range == DashboardRange.Week) R.string.dash_range_week else R.string.dash_range_today)
    val other = stringResource(if (range == DashboardRange.Week) R.string.dash_range_today else R.string.dash_range_week)
    val description = stringResource(R.string.dash_range_cd, current)
    val actionLabel = stringResource(R.string.dash_range_action, other)
    Box(
        Modifier
            .padding(end = 8.dp)
            .layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val trim = HitPad.roundToPx()
                val h = (p.height - 2 * trim).coerceAtLeast(0)
                layout(p.width, h) { p.place(0, -trim) }
            },
    ) {
        MurmurChip(
            label = current,
            onClick = onToggle,
            enabled = enabled,
            modifier = Modifier
                .testTag(DashboardTags.RANGE)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    stateDescription = current
                    if (enabled) onClick(actionLabel) { onToggle(); true } else disabled()
                },
        )
    }
}

/** Vertical padding that grows a 32 dp chip to its 48 dp touch target (DESIGN 4). */
private val HitPad: Dp = 8.dp

// -------------------------------------------------------------------------------------------- sections

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .elevation(Elevation.e1, MurmurShapes.lg)
            .clip(MurmurShapes.lg)
            .background(MurmurTheme.colors.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun BigNumber(number: String, unit: String, color: Color, unitColor: Color) {
    val t = MurmurTheme.type
    Row(Modifier.padding(top = 8.dp)) {
        Text(number, style = t.display, color = color, modifier = Modifier.alignByBaseline().cssLineBox(t.display))
        Spacer(Modifier.width(8.dp))
        Text(unit, style = t.titleSm, color = unitColor, modifier = Modifier.alignByBaseline())
    }
}

/** Today card: TODAY, the word count, the speaking time and the time saved (tap: typing speed sheet). */
@Composable
private fun TodayCard(t: TodayTexts, onEdit: () -> Unit) {
    val c = MurmurTheme.colors
    val label = stringResource(R.string.dash_card_action)
    val sentence = buildAnnotatedString {
        if (t.sentence.boldStart < 0) {
            append(t.sentence.text)
        } else {
            append(t.sentence.text.substring(0, t.sentence.boldStart))
            withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) {
                append(t.sentence.text.substring(t.sentence.boldStart, t.sentence.boldEnd))
            }
            append(t.sentence.text.substring(t.sentence.boldEnd))
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .elevation(Elevation.e1, MurmurShapes.lg)
            .clip(MurmurShapes.lg)
            .background(c.surface)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onEdit)
            .padding(20.dp)
            .testTag(DashboardTags.TODAY_CARD),
    ) {
        Overline(stringResource(R.string.dash_today))
        BigNumber(t.number, t.unit, color = if (t.isZero) c.ink3 else c.ink, unitColor = if (t.isZero) c.ink3 else c.ink2)
        Text(sentence, style = MurmurTheme.type.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MurmurTheme.type.titleSm, color = MurmurTheme.colors.ink,
        modifier = modifier.semantics { heading() },
    )
}

/** Title with a right-aligned caption on the same baseline ("Last 7 days … 11,374 words"). */
@Composable
private fun SectionHeader(title: String, caption: String, captionStyle: TextStyle) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, Modifier.weight(1f).alignByBaseline())
        Text(caption, style = captionStyle, color = MurmurTheme.colors.ink3, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun WeekSection(w: WeekTexts) {
    val t = MurmurTheme.type
    Column(Modifier.padding(top = 4.dp)) {
        SectionHeader(stringResource(R.string.dash_week_title), w.total, t.mono.copy(fontFeatureSettings = "tnum"))
        WeekChart(w, Modifier.padding(top = 16.dp))
    }
}

/** "How it's going" (and the empty state's "What you'll see here"): metric rows with 1 dp dividers. */
@Composable
private fun MetricsSection(title: String, rows: List<MetricTexts>, titleGap: Dp = 2.dp) {
    Column(Modifier.testTag(DashboardTags.METRICS)) {
        SectionTitle(title, Modifier.padding(bottom = titleGap))
        rows.forEachIndexed { i, m ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MurmurTheme.colors.line))
            MetricRow(m)
        }
    }
}

/**
 * One metric: name and explanation on the left, the value (20 sp) with its unit (13 sp, ink3) on the right, the name's
 * baseline on the value's; the explanation wraps under the name. When the name no longer fits on one line beside the
 * value (large text, DESIGN 8), the value moves under the explanation.
 */
@Composable
private fun MetricRow(m: MetricTexts) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val noValueCd = stringResource(R.string.dash_no_value_cd)
    val dash = stringResource(R.string.dash_no_value)
    Layout(
        contents = listOf(
            { Text(m.title, style = t.rowTitle, color = c.ink) },
            { Text(m.subtitle, style = t.rowSub, color = c.ink3) },
            {
                Row {
                    if (m.value == null) {
                        Text(
                            dash, style = MetricValue, color = c.ink3,
                            modifier = Modifier.alignByBaseline().cssLineBox(MetricValue)
                                .semantics { contentDescription = noValueCd },
                        )
                    } else {
                        Text(
                            m.value, style = MetricValue, color = c.ink, maxLines = 1,
                            modifier = Modifier.alignByBaseline().cssLineBox(MetricValue),
                        )
                        if (m.unit != null) {
                            Text(
                                m.unit, style = MetricUnit, color = c.ink3, maxLines = 1,
                                modifier = Modifier.padding(start = 2.dp).alignByBaseline().cssLineBox(MetricUnit),
                            )
                        }
                    }
                }
            },
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) { },
    ) { (titleM, subM, valueM), constraints ->
        val title = titleM.single()
        val sub = subM.single()
        val value = valueM.single()
        val width = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val subGap = 2.dp.roundToPx()
        val vp = value.measure(Constraints(maxWidth = width))
        if (title.maxIntrinsicWidth(Constraints.Infinity) + gap + vp.width <= width) {
            val left = width - vp.width - gap
            val tp = title.measure(Constraints(maxWidth = left))
            val sp = sub.measure(Constraints(maxWidth = left))
            val tb = tp[FirstBaseline]
            val vb = vp[FirstBaseline]
            val base = maxOf(tb, vb)
            val ty = base - tb
            val vy = base - vb
            val h = maxOf(ty + tp.height + subGap + sp.height, vy + vp.height)
            layout(width, h) {
                tp.place(0, ty)
                sp.place(0, ty + tp.height + subGap)
                vp.place(width - vp.width, vy)
            }
        } else {
            val tp = title.measure(Constraints(maxWidth = width))
            val sp = sub.measure(Constraints(maxWidth = width))
            val top = 4.dp.roundToPx()
            val vy = tp.height + subGap + sp.height + top
            layout(width, vy + vp.height) {
                tp.place(0, 0)
                sp.place(0, tp.height + subGap)
                vp.place(0, vy)
            }
        }
    }
}

/**
 * Metric value: 20 sp / 24 / 600, −0.3 tracking. Proportional figures as in the frames (DESIGN 3.2 asks for `tnum`
 * only where numbers sit in columns; these stand alone).
 */
private val MetricValue: TextStyle
    @Composable get() = MurmurTheme.type.title.copy(lineHeight = 24.sp, letterSpacing = (-0.3).sp)

private val MetricUnit: TextStyle
    @Composable get() = MurmurTheme.type.label.copy(fontSize = 13.sp, lineHeight = 16.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagesSection(langs: List<LangTexts>) {
    val c = MurmurTheme.colors
    fun color(tone: LangTone) = when (tone) {
        LangTone.Primary -> c.ink
        LangTone.Secondary -> c.ink4
        LangTone.Tertiary -> c.ink2
        LangTone.Mixed -> c.accent
    }
    Column(Modifier.testTag(DashboardTags.LANGUAGES)) {
        SectionTitle(stringResource(R.string.dash_languages))
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth().height(10.dp).clip(MurmurShapes.full).clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            langs.forEach { l -> Box(Modifier.weight(l.share.coerceAtLeast(0.001f)).fillMaxHeight().background(color(l.tone))) }
        }
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            langs.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color(l.tone)))
                    Text(
                        l.text, style = MurmurTheme.type.rowSub, color = c.ink2,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppsSection(a: AppsTexts, provider: AppInfoProvider) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val words = t.mono.copy(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    Column(Modifier.testTag(DashboardTags.APPS)) {
        SectionHeader(stringResource(R.string.dash_apps), a.caption, t.caption)
        Column(Modifier.padding(top = 6.dp)) {
            a.rows.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTile(r.label, provider.icon(r.pkg), packageName = r.pkg)
                        Text(
                            r.label, style = t.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 10.dp),
                        )
                        Text(r.words, style = words, color = c.ink3, maxLines = 1)
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        Spacer(Modifier.width(30.dp))
                        Box(Modifier.weight(1f).height(4.dp).clip(MurmurShapes.full).background(c.surface2)) {
                            Box(Modifier.fillMaxWidth(r.fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(MurmurShapes.full).background(c.ink2))
                        }
                    }
                }
            }
            if (a.others != null) {
                Text(a.others, style = t.caption, color = c.ink3, modifier = Modifier.padding(start = 30.dp, top = 6.dp))
            }
        }
    }
}

@Composable
private fun BubbleOffBanner(onOpen: () -> Unit) {
    Banner(
        BannerKind.Err,
        title = stringResource(R.string.dash_off_title),
        body = stringResource(R.string.dash_off_body, ProductName.NAME),
        actionLabel = stringResource(R.string.dash_off_action),
        onAction = onOpen,
        modifier = Modifier.testTag(DashboardTags.BUBBLE_OFF),
    )
}
