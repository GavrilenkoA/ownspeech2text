package app.murmur.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** One spec of DESIGN 3.2; [TypeSpec] -> TextStyle with the bundled Onest / JetBrains Mono (WP-01). */
data class TypeSpec(
    val size: Int,
    val line: Int,
    val weight: Int,
    val tracking: Float,
    val mono: Boolean,
    val uppercase: Boolean,
    val tabular: Boolean,
)

val LocalMurmurColors = staticCompositionLocalOf { TokenColors.light }

/**
 * DESIGN 9: MurmurTheme provides the tokens and maps them onto MaterialTheme.colorScheme. Dynamic color is
 * off (DESIGN decision 2). Typography/shapes/components: WP-01.
 */
@Composable
fun MurmurTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) TokenColors.dark else TokenColors.light
    val scheme = if (darkTheme) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentInk,
        background = c.bg, surface = c.surface, surfaceVariant = c.surface2, onSurface = c.ink, onSurfaceVariant = c.ink2,
        outline = c.line2, outlineVariant = c.line, error = c.err, errorContainer = c.errSoft, inverseSurface = c.inverse,
        inverseOnSurface = c.onInverse, inversePrimary = c.inverseAccent, scrim = c.scrim,
        surfaceContainerLowest = c.surface, surfaceContainer = c.surface2, surfaceContainerHigh = c.surface3,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentInk,
        background = c.bg, surface = c.surface, surfaceVariant = c.surface2, onSurface = c.ink, onSurfaceVariant = c.ink2,
        outline = c.line2, outlineVariant = c.line, error = c.err, errorContainer = c.errSoft, inverseSurface = c.inverse,
        inverseOnSurface = c.onInverse, inversePrimary = c.inverseAccent, scrim = c.scrim,
        surfaceContainerLowest = c.surface, surfaceContainer = c.surface2, surfaceContainerHigh = c.surface3,
    )
    CompositionLocalProvider(LocalMurmurColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object MurmurTheme {
    val colors: MurmurColors @Composable get() = LocalMurmurColors.current
}
