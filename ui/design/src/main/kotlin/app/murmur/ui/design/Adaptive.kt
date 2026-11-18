package app.murmur.ui.design

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp

/**
 * DESIGN 2 / ARCHITECTURE 3: medium width and up (>= 600 dp) is the "inner" layout (rail, list-detail, side sheets).
 * Branch on the window size class, never on the device.
 */
@Composable
fun rememberIsWide(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(Layout.mediumMinWidth.value.toInt())

/** Screen gutters (DESIGN 3.3): 20 dp on the cover display, 24 dp in inner panes (28 dp for wide content panes). */
object Gutters {
    val cover: Dp = Spacing.s20
    val pane: Dp = Spacing.s24
    val paneWide: Dp = Spacing.s28
    /** Around grouped lists. */
    val group: Dp = Spacing.s16
}

/** The horizontal screen gutter for the current window (20 dp compact, 24 dp medium+). */
@Composable
fun screenGutter(): Dp = if (rememberIsWide()) Gutters.pane else Gutters.cover

val LocalReducedMotion = staticCompositionLocalOf { false }

/** DESIGN 3.5: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` ("Remove animations") = reduced motion. */
fun isReducedMotion(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Reads the system animator scale and follows changes while composed. */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(isReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = isReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Cross-fade duration that replaces every spring and morph under reduced motion (DESIGN 3.5). */
const val REDUCED_MOTION_FADE_MS: Int = Motion.shortMs

/** [token] as a spring, or a 150 ms tween when [reduced] (DESIGN 3.5). */
fun <T> springOrFade(token: SpringToken, reduced: Boolean): FiniteAnimationSpec<T> =
    if (reduced) tween(REDUCED_MOTION_FADE_MS) else token.spec()
