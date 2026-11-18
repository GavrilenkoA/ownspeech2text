package app.murmur.ui.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalMurmurColors = staticCompositionLocalOf { TokenColors.light }

/** True inside a dark MurmurTheme (skeleton shimmer and other theme-specific details). */
val LocalMurmurDark = staticCompositionLocalOf { false }

/**
 * DESIGN 9: MurmurTheme provides the tokens (colours, type, reduced motion) and maps them onto MaterialTheme
 * (colorScheme per the DESIGN 9 table, typography, shapes). Dynamic colour is off (DESIGN decision 2). Clickables get
 * the design's pressed overlay (ink at 6 %) instead of the Material ripple.
 */
@Composable
fun MurmurTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) TokenColors.dark else TokenColors.light
    val type = MurmurType.Default
    val reduced = rememberReducedMotion()
    CompositionLocalProvider(
        LocalMurmurColors provides c,
        LocalMurmurType provides type,
        LocalMurmurDark provides darkTheme,
        LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(colorScheme = colorSchemeOf(c, darkTheme), typography = type.toMaterial(), shapes = MurmurShapes.material) {
            CompositionLocalProvider(
                LocalIndication provides PressOverlay,
                LocalContentColor provides c.ink,
                content = content,
            )
        }
    }
}

/** The DESIGN 9 role table. */
fun colorSchemeOf(c: MurmurColors, dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentInk,
    background = c.bg, onBackground = c.ink, surface = c.surface, surfaceVariant = c.surface2, onSurface = c.ink,
    onSurfaceVariant = c.ink2, outline = c.line2, outlineVariant = c.line, error = c.err, errorContainer = c.errSoft,
    inverseSurface = c.inverse, inverseOnSurface = c.onInverse, inversePrimary = c.inverseAccent, scrim = c.scrim,
    surfaceContainerLowest = c.surface, surfaceContainer = c.surface2, surfaceContainerHigh = c.surface3,
) else lightColorScheme(
    primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentInk,
    background = c.bg, onBackground = c.ink, surface = c.surface, surfaceVariant = c.surface2, onSurface = c.ink,
    onSurfaceVariant = c.ink2, outline = c.line2, outlineVariant = c.line, error = c.err, errorContainer = c.errSoft,
    inverseSurface = c.inverse, inverseOnSurface = c.onInverse, inversePrimary = c.inverseAccent, scrim = c.scrim,
    surfaceContainerLowest = c.surface, surfaceContainer = c.surface2, surfaceContainerHigh = c.surface3,
)

object MurmurTheme {
    val colors: MurmurColors @Composable @ReadOnlyComposable get() = LocalMurmurColors.current
    val type: MurmurType @Composable @ReadOnlyComposable get() = LocalMurmurType.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalMurmurDark.current
    /** DESIGN 3.5: system "Remove animations" / animator duration scale 0. */
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}
