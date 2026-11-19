package app.murmur.feature.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.ui.design.MurmurTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Chart geometry (prototype .chart): bars up to 104 dp, at most 34 dp wide, 10 dp apart, labels 8 dp away. */
internal object ChartSpec {
    val barArea: Dp = 104.dp
    val minBar: Dp = 4.dp
    val maxBarWidth: Dp = 34.dp
    val columnGap: Dp = 10.dp
    val labelGap: Dp = 8.dp
    val topRadius: Dp = 8.dp
    val bottomRadius: Dp = 3.dp
    /**
     * The frames reserve 2 dp less than label + gap + full bar above the bars (150 dp at 100 %): the scale's nice
     * maximum keeps real bars at ≤ 102 dp, and a label that would rise higher is held at the top instead.
     */
    val headroomTrim: Dp = 2.dp
    /**
     * The prototype's chart box is 140 dp while its columns need 150 (16 + 8 + 102 + 8 + 16), so the day labels hang
     * 10 dp into the 28 dp gap below. The chart reports 10 dp less than it draws, which keeps every following section
     * where the frames put it.
     */
    val bottomOverhang: Dp = 10.dp
}

/** A bar's height: its share of the 104 dp area in whole dp, at least 4 dp (prototype `max(4, round(v / max × 104))`). */
internal fun barHeightDp(fraction: Float): Dp =
    max(ChartSpec.minBar.value, (fraction * ChartSpec.barArea.value).roundToInt().toFloat()).dp

/**
 * "Last 7 days" bar chart (DESIGN 6.2, 8): one bar per local day, today in the accent and the others surface3, only
 * the largest day labelled (mono, tabular) 8 dp above its bar, day initials 8 dp below the bars (today's in ink).
 * Bars are drawn on the canvas behind the labels. Label rows take the height their text needs, so the chart grows
 * with large text; a label wider than its column overflows into the empty space beside it instead of being cut.
 * For TalkBack the chart is one image whose description lists every day.
 */
@Composable
fun WeekChart(w: WeekTexts, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val dayStyle = t.labelSm.copy(letterSpacing = 0.sp)
    val maxStyle = t.mono.copy(fontSize = 11.sp, fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Line heights of the two label styles at the current font scale.
    val dayLine = remember(dayStyle, density) { measurer.measure("Mo", dayStyle, maxLines = 1).size.height }
    val maxLine = remember(maxStyle, density) { measurer.measure("0", maxStyle, maxLines = 1).size.height }
    val hasMax = w.maxIndex >= 0 && w.maxLabel != null
    Layout(
        content = {
            w.days.forEach { d ->
                Text(d.label, style = dayStyle, color = if (d.isToday) c.ink else c.ink3, maxLines = 1, softWrap = false)
            }
            if (hasMax) Text(w.maxLabel!!, style = maxStyle, color = c.ink2, maxLines = 1, softWrap = false)
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(DashboardTags.CHART)
            .clearAndSetSemantics {
                contentDescription = w.description
                role = Role.Image
            }
            .drawBehind {
                val n = w.days.size
                if (n == 0) return@drawBehind
                val gap = ChartSpec.columnGap.toPx()
                val col = (size.width - gap * (n - 1)) / n
                val barW = min(col, ChartSpec.maxBarWidth.toPx())
                val bottom = size.height + ChartSpec.bottomOverhang.toPx() - dayLine - ChartSpec.labelGap.toPx()
                val top = ChartSpec.topRadius.toPx()
                val low = ChartSpec.bottomRadius.toPx()
                w.days.forEachIndexed { i, d ->
                    val h = barHeightDp(d.fraction).toPx()
                    val x = i * (col + gap) + (col - barW) / 2
                    // CSS border-radius semantics: the radii shrink together when the bar is shorter than their sum.
                    val f = min(1f, h / (top + low))
                    val r = min(top, barW / 2) * f
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = x, top = bottom - h, right = x + barW, bottom = bottom,
                                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                                bottomRightCornerRadius = CornerRadius(low * f), bottomLeftCornerRadius = CornerRadius(low * f),
                            ),
                        )
                    }
                    drawPath(path, if (d.isToday) c.accent else c.surface3)
                }
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val n = w.days.size
        val gap = ChartSpec.columnGap.roundToPx()
        val col = if (n > 0) (width - gap * (n - 1)).toFloat() / n else 0f
        val labelGap = ChartSpec.labelGap.roundToPx()
        val barBase = maxLine + labelGap + ChartSpec.barArea.roundToPx() - ChartSpec.headroomTrim.roundToPx()
        val height = barBase + labelGap + dayLine
        val loose = Constraints()
        val days = measurables.take(n).map { it.measure(loose) }
        val maxLabel = if (hasMax) measurables[n].measure(loose) else null
        layout(width, height - ChartSpec.bottomOverhang.roundToPx()) {
            days.forEachIndexed { i, p ->
                val center = i * (col + gap) + col / 2
                p.place((center - p.width / 2f).roundToInt(), height - dayLine + (dayLine - p.height) / 2)
            }
            if (maxLabel != null) {
                val i = w.maxIndex
                val center = i * (col + gap) + col / 2
                val bar = barHeightDp(w.days[i].fraction).roundToPx()
                val y = (barBase - bar - labelGap - maxLine).coerceAtLeast(0)
                val x = (center - maxLabel.width / 2f).roundToInt().coerceIn(0, max(0, width - maxLabel.width))
                maxLabel.place(x, y + (maxLine - maxLabel.height) / 2)
            }
        }
    }
}
