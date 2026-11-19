package app.murmur.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.ui.design.MurmurTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Chart geometry (prototype .chart): bars up to 104 dp, 34 dp wide at most, 10 dp apart, 8 dp to the labels. */
internal object ChartSpec {
    val barArea: Dp = 104.dp
    val minBar: Dp = 4.dp
    val maxBarWidth: Dp = 34.dp
    val columnGap: Dp = 10.dp
    val labelGap: Dp = 8.dp
    val label: Dp = 16.dp
    /** Room for the max label above a full bar; the tallest real bar stays just below the nice maximum. */
    val height: Dp = 150.dp
    val topRadius: Dp = 8.dp
    val bottomRadius: Dp = 3.dp
}

/**
 * "Last 7 days" bar chart (DESIGN 6.2, 8): one bar per local day, today in the accent, the others surface3, only the
 * largest day labelled (mono, tabular), day initials under the bars, today's in ink. The bars are drawn on one Canvas;
 * the whole chart is one image for TalkBack with every value in its description.
 */
@Composable
fun WeekChart(w: WeekTexts, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val dayStyle = t.labelSm.copy(letterSpacing = 0.sp)
    val maxStyle = t.mono.copy(fontSize = 11.sp, fontFeatureSettings = "tnum")
    Box(
        modifier
            .fillMaxWidth()
            .height(ChartSpec.height)
            .testTag(DashboardTags.CHART)
            .clearAndSetSemantics {
                contentDescription = w.description
                role = Role.Image
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(ChartSpec.height)) {
            val n = w.days.size
            if (n == 0) return@Canvas
            val gap = ChartSpec.columnGap.toPx()
            val col = (size.width - gap * (n - 1)) / n
            val barW = min(col, ChartSpec.maxBarWidth.toPx())
            val bottom = size.height - (ChartSpec.label + ChartSpec.labelGap).toPx()
            val top = ChartSpec.topRadius.toPx()
            val low = ChartSpec.bottomRadius.toPx()
            w.days.forEachIndexed { i, d ->
                val h = barHeightDp(d.fraction).toPx()
                val x = i * (col + gap) + (col - barW) / 2
                // CSS border-radius semantics: radii scale down together when the bar is shorter than their sum.
                val f = min(1f, h / (top + low))
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = x, top = bottom - h, right = x + barW, bottom = bottom,
                            topLeftCornerRadius = CornerRadius(min(top, barW / 2) * f),
                            topRightCornerRadius = CornerRadius(min(top, barW / 2) * f),
                            bottomRightCornerRadius = CornerRadius(low * f),
                            bottomLeftCornerRadius = CornerRadius(low * f),
                        ),
                    )
                }
                drawPath(path, if (d.isToday) c.accent else c.surface3)
            }
        }
        // Labels: one column per day; the max label sits 8 dp above its bar.
        Row(Modifier.fillMaxWidth().height(ChartSpec.height), horizontalArrangement = Arrangement.spacedBy(ChartSpec.columnGap)) {
            w.days.forEachIndexed { i, d ->
                Column(Modifier.weight(1f).height(ChartSpec.height), horizontalAlignment = Alignment.CenterHorizontally) {
                    val barDp = barHeightDp(d.fraction)
                    val labelTop = ChartSpec.height - ChartSpec.label
                    if (i == w.maxIndex && w.maxLabel != null) {
                        val maxTop = labelTop - ChartSpec.labelGap - barDp - ChartSpec.labelGap - ChartSpec.label
                        Box(Modifier.height(ChartSpec.label).fillMaxWidth().offsetY(maxTop), contentAlignment = Alignment.Center) {
                            Text(w.maxLabel, style = maxStyle, color = c.ink2, maxLines = 1, softWrap = false)
                        }
                    }
                    Box(Modifier.weight(1f))
                    Text(
                        d.label, style = dayStyle, color = if (d.isToday) c.ink else c.ink3, textAlign = TextAlign.Center,
                        maxLines = 1, modifier = Modifier.fillMaxWidth().height(ChartSpec.label),
                    )
                }
            }
        }
    }
}

/** A bar's height: its share of the 104 dp area in whole dp, at least 4 dp (prototype `max(4, round(v / max × 104))`). */
internal fun barHeightDp(fraction: Float): Dp = max(ChartSpec.minBar.value, (fraction * ChartSpec.barArea.value).roundToInt().toFloat()).dp

/** Places the element [y] from the top of its column without taking layout space (the max label floats). */
private fun Modifier.offsetY(y: Dp): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, 0) { p.place(0, y.roundToPx()) }
}
