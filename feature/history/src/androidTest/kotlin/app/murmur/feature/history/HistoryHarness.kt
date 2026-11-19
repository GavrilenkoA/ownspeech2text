package app.murmur.feature.history

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import app.murmur.core.model.EntryId
import app.murmur.core.model.Outcome
import app.murmur.core.store.Settings
import app.murmur.testing.FakeClock
import app.murmur.testing.InMemoryAudioStore
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.contrib.Wp16HistoryFixture
import app.murmur.testing.contrib.Wp16LiveHistoryRepository
import app.murmur.ui.design.AppInfoProvider
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.MurmurNavigationBar
import app.murmur.ui.design.components.MurmurNavigationRail
import app.murmur.ui.design.components.NavItem
import app.murmur.ui.design.rememberIsWide
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Launcher icons for the screenshot tests: the prototype's letter tiles (APPS map colours, white Onest 600 letter),
 * so rows look like the frames. Real builds get PackageManager icons from :app.
 */
class LetterTileApps(private val context: Context) : AppInfoProvider {
    private val typeface: Typeface = Typeface.create(context.resources.getFont(app.murmur.ui.design.R.font.onest_variable), 600, false)
    private val cache = HashMap<String, Painter>()
    override fun label(pkg: String): String? = Wp16HistoryFixture.LABELS[pkg]
    override fun icon(pkg: String): Painter? {
        val (color, letter) = Wp16HistoryFixture.TILES[pkg] ?: return null
        return cache.getOrPut(pkg) { LetterPainter(Color(color), letter, typeface) }
    }
    override fun launcherApps(): List<String> = Wp16HistoryFixture.LABELS.keys.sortedBy { Wp16HistoryFixture.LABELS[it] }
}

private class LetterPainter(private val color: Color, private val letter: String, private val typeface: Typeface) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified
    override fun DrawScope.onDraw() {
        drawRect(color)
        val small = size.height < 28.dp.toPx()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = this@LetterPainter.typeface
            this.color = Color.White.toArgb()
            textSize = size.height * if (small) 0.5f else 0.417f
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        drawIntoCanvas { it.nativeCanvas.drawText(letter, size.width / 2, size.height / 2 - (fm.ascent + fm.descent) / 2, paint) }
    }
}

/** The fakes behind one test, seeded with the frames' data set (or empty). */
class HistoryWorld(context: Context, seed: Boolean = true, retentionDays: Int = 7) {
    val history = Wp16LiveHistoryRepository()
    val audio = InMemoryAudioStore()
    val settings = InMemorySettingsRepository(Settings(retentionDays = retentionDays))
    val clock = FakeClock(Wp16HistoryFixture.NOW, Wp16HistoryFixture.ZONE)
    val retries = CopyOnWriteArrayList<EntryId>()
    val repolishes = CopyOnWriteArrayList<EntryId>()
    /** When set, Re-polish waits for it and returns its outcome. */
    @Volatile var repolishGate: CompletableDeferred<Outcome<EntryId>>? = null
    val ids: List<EntryId> = if (seed) kotlinx.coroutines.runBlocking { Wp16HistoryFixture.seed(history, audio) } else emptyList()
    val deps = HistoryDeps(
        history = history,
        settings = settings,
        audio = audio,
        clock = clock,
        retry = { id -> retries += id; Outcome.Ok(id) },
        repolish = { id -> repolishes += id; repolishGate?.await() ?: Outcome.Ok(id) },
        apps = LetterTileApps(context),
        modelName = { Wp16HistoryFixture.MODEL_NAMES[it] },
    )
}

/**
 * A stand-in for WP-15's shell, so screenshots compare with the frames: the frames' 36 dp status bar (or the real
 * inset if taller), the rail on medium+ widths; on compact widths the History content gets [TestNavBar] as its
 * bottom bar. English (UK) like the frames ("Monday, 16 November", 24-hour time) regardless of the emulator locale.
 */
@Composable
fun FrameShell(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val config = remember(base) { Configuration(base.resources.configuration).apply { setLocale(Locale.UK) } }
    val localized = remember(config) { base.createConfigurationContext(config) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalContext provides localized, LocalConfiguration provides config, LocalResources provides localized.resources,
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MurmurTheme(darkTheme = dark) {
            val c = MurmurTheme.colors
            val top = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 36.dp)
            if (rememberIsWide()) {
                Row(Modifier.fillMaxSize().background(c.bg).padding(top = top).windowInsetsPadding(WindowInsets.navigationBars)) {
                    MurmurNavigationRail(NAV_ITEMS, selectedIndex = 1, onSelect = {}, modifier = Modifier.fillMaxHeight())
                    Box(Modifier.weight(1f)) { content() }
                }
            } else {
                Box(Modifier.fillMaxSize().background(c.bg).padding(top = top)) { content() }
            }
        }
    }
}

private val NAV_ITEMS = listOf(
    NavItem("Dashboard", MurmurIcons.dash), NavItem("History", MurmurIcons.hist),
    NavItem("Models", MurmurIcons.models), NavItem("Settings", MurmurIcons.sliders),
)

/** The shell's bottom bar stand-in (History selected). */
@Composable
fun TestNavBar() = MurmurNavigationBar(NAV_ITEMS, selectedIndex = 1, onSelect = {})
