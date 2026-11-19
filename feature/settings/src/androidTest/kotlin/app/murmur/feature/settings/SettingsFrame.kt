package app.murmur.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.MurmurNavigationBar
import app.murmur.ui.design.components.MurmurNavigationRail
import app.murmur.ui.design.components.NavItem

const val FRAME_TAG = "settings-frame"

/** Prototype device sizes (DESIGN 2, prototype .screen.cover / .inner), 1 dp = 2 px like the PNGs. */
enum class Device(val width: Int, val height: Int) { Cover(412, 960), Inner(750, 832) }

/**
 * The prototype frame around Settings at the PNGs' scale (density 2): 36 dp status bar, the bottom bar (cover; not
 * under the pushed prompt and tone screens) or the rail (inner) with Settings selected, the 20 dp gesture bar, and
 * the inline overlay host on top (sheets and dialogs over the whole frame). Window insets are consumed, so the
 * screenshot is comparable pixel by pixel with `docs/design/screens/{settings,prompt,tone,retention}*.png`.
 */
@Composable
fun SettingsFrame(device: Device, dark: Boolean, bottomBar: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val host = remember { InlineOverlayHost() }
    CompositionLocalProvider(LocalDensity provides Density(2f, fontScale), LocalInlineOverlayHost provides host) {
        MurmurTheme(darkTheme = dark) {
            val c = MurmurTheme.colors
            val items = listOf(
                NavItem("Dashboard", MurmurIcons.dash),
                NavItem("History", MurmurIcons.hist),
                NavItem("Models", MurmurIcons.models),
                NavItem("Settings", MurmurIcons.sliders),
            )
            Box(Modifier.fillMaxWidth().wrapContentSize(Alignment.TopStart)) {
                Box(
                    Modifier
                        .requiredSize(device.width.dp, device.height.dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing)
                        .testTag(FRAME_TAG),
                ) {
                    Column(Modifier.fillMaxSize().background(c.bg)) {
                        Box(Modifier.fillMaxWidth().height(36.dp))
                        if (device == Device.Cover) {
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.weight(1f)) { content() }
                                if (bottomBar) MurmurNavigationBar(items, 3, {})
                            }
                        } else {
                            Row(Modifier.weight(1f)) {
                                MurmurNavigationRail(items, 3, {}, Modifier.fillMaxHeight())
                                Box(Modifier.weight(1f)) { content() }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(108.dp, 4.dp).alpha(0.85f).clip(MurmurShapes.full).background(c.ink))
                        }
                    }
                    InlineOverlay(host)
                }
            }
        }
    }
}
