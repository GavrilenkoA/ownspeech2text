package app.murmur.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.DashboardStats
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.StatsCalculator
import app.murmur.testing.FakeClock
import app.murmur.testing.InMemoryMetricsRepository
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.StaticAppInfoProvider
import app.murmur.ui.design.components.MurmurNavigationBar
import app.murmur.ui.design.components.MurmurNavigationRail
import app.murmur.ui.design.components.NavItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.LocalDate

const val FRAME_TAG = "dash-frame"

/** Prototype device sizes (DESIGN 2, prototype .screen.cover / .inner), 1 dp = 2 px like the PNGs. */
enum class Device(val width: Int, val height: Int) { Cover(412, 960), Inner(750, 832) }

/**
 * The prototype frame around a screen, at the PNGs' scale (density 2): 36 dp status bar, the bottom bar (cover) or
 * the rail (inner) with Dashboard selected, and the 20 dp gesture bar. Window insets are consumed, so the screenshot
 * contains exactly what `docs/design/screens/dash*.png` shows and can be compared pixel by pixel.
 */
@Composable
fun DashboardFrame(device: Device, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
        MurmurTheme(darkTheme = dark) {
            val c = MurmurTheme.colors
            val items = listOf(
                NavItem("Dashboard", MurmurIcons.dash),
                NavItem("History", MurmurIcons.hist),
                NavItem("Models", MurmurIcons.models),
                NavItem("Settings", MurmurIcons.sliders),
            )
            Box(Modifier.fillMaxWidth().wrapContentSize(Alignment.TopStart)) {
                Column(
                    Modifier
                        .requiredSize(device.width.dp, device.height.dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing)
                        .background(c.bg)
                        .testTag(FRAME_TAG),
                ) {
                    Box(Modifier.fillMaxWidth().height(36.dp))
                    if (device == Device.Cover) {
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.weight(1f)) { content() }
                            MurmurNavigationBar(items, 0, {})
                        }
                    } else {
                        Row(Modifier.weight(1f)) {
                            MurmurNavigationRail(items, 0, {}, Modifier.fillMaxHeight())
                            Box(Modifier.weight(1f)) { content() }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(108.dp, 4.dp).alpha(0.85f).clip(MurmurShapes.full).background(c.ink))
                    }
                }
            }
        }
    }
}

/** The numbers of the frames (prototype WEEK, dashMetrics, dashLang, dashApps), as WP-12 stats. */
object FrameData {
    const val SLACK = Wp12MetricsFixture.SLACK
    val apps = StaticAppInfoProvider(
        mapOf(
            Wp12MetricsFixture.TELEGRAM to "Telegram",
            Wp12MetricsFixture.GMAIL to "Gmail",
            SLACK to "Slack",
            Wp12MetricsFixture.NOTES to "Notes",
            Wp12MetricsFixture.CHROME to "Chrome",
            Wp12MetricsFixture.TERMUX to "Termux",
        ),
    )

    private val week = listOf(2140, 1860, 420, 310, 2950, 2410, 1284)

    /**
     * The frames' figures cannot all come from consistent rows (9 % of ≈ 11,400 words changed is ≈ 1,000 edits, but
     * 286 fillers + 54 fixes is 340), so screenshots use these stats through a fixed calculator; the real
     * calculator is covered by the view-model tests.
     */
    val stats = DashboardStats(
        todayWords = 1284,
        todaySpeakingMin = 9.0,
        timeSavedMin = 1284 / 40.0 - 9.0,
        last7Days = week.mapIndexed { i, w -> LocalDate.of(2026, 11, 12 + i) to w },
        paceWpmMedian = 142.0,
        readyMedianMs = 1_400,
        readyP90Ms = 2_600,
        typedShare = 0.94,
        copiedOnlyCount = 4,
        copiedOnlyTopApp = Wp12MetricsFixture.TERMUX,
        changedShare = 0.09,
        fillersRemoved = 286,
        fixes = 54,
        languages = linkedMapOf("ru" to 62, "en" to 31, "mixed" to 7),
        topApps = listOf(
            Wp12MetricsFixture.TELEGRAM to 4120, Wp12MetricsFixture.GMAIL to 2060, SLACK to 1580,
            Wp12MetricsFixture.NOTES to 920, Wp12MetricsFixture.CHROME to 610,
        ),
        otherAppsCount = 8,
        otherAppsWords = 2084,
    )

    /** Rows behind the frame: the four Termux copies ("4 copied only, all in Termux") and one insert. */
    val rows: List<DictationMetrics> = (1..4).map {
        Wp12MetricsFixture.row(Wp12MetricsFixture.TODAY, Wp12MetricsFixture.TERMUX, EntryStatus.COPIED, wordsFinal = 5, recordMs = 4_000, speechMs = 3_000, stopToDoneMs = 1_000)
    } + Wp12MetricsFixture.row(Wp12MetricsFixture.TODAY, Wp12MetricsFixture.TELEGRAM, wordsFinal = 20, recordMs = 9_000, speechMs = 8_000, stopToDoneMs = 1_000)

    val fixedCalculator = object : StatsCalculator {
        override fun compute(rows: List<DictationMetrics>, today: LocalDate, typingWpm: Int): DashboardStats = stats
    }

    fun store(rows: List<DictationMetrics>): InMemoryMetricsRepository =
        InMemoryMetricsRepository().apply { runBlocking { rows.forEach { add(it) } } }

    /** since() never emits: the dashboard stays in its loading state. */
    val neverLoads = object : MetricsRepository {
        override fun since(day: LocalDate): Flow<List<DictationMetrics>> = flow { awaitCancellation() }
        override suspend fun add(m: DictationMetrics) = Unit
        override suspend fun deleteBefore(day: LocalDate): Int = 0
    }

    /** since() fails: "Couldn't read your stats." */
    val broken = object : MetricsRepository {
        override fun since(day: LocalDate): Flow<List<DictationMetrics>> = flow { throw IOException("unreadable") }
        override suspend fun add(m: DictationMetrics) = Unit
        override suspend fun deleteBefore(day: LocalDate): Int = 0
    }

    fun deps(
        metrics: MetricsRepository = store(rows),
        calculator: StatsCalculator = fixedCalculator,
        connected: Boolean = true,
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        clock: MurmurClock = FakeClock(),
    ) = DashboardDeps(metrics, settings, calculator, clock, MutableStateFlow(connected), apps)
}
