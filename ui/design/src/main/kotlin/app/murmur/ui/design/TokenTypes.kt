package app.murmur.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** One spec of DESIGN 3.2 (tokens.json typography); [MurmurType] turns it into a TextStyle. */
@Immutable
data class TypeSpec(
    val size: Int,
    val line: Int,
    val weight: Int,
    val tracking: Float,
    val mono: Boolean,
    val uppercase: Boolean,
    val tabular: Boolean,
)

/** One CSS box-shadow layer of tokens.json elevation (x is always 0). */
@Immutable
data class ShadowLayer(val y: Dp, val blur: Dp, val color: Color)

/** e0..e3 (DESIGN 3.3): the layered shadow of the prototype plus the Compose shadow elevation hint. */
@Immutable
data class ElevationSpec(val layers: List<ShadowLayer>, val shadowElevation: Dp)

/** tokens.json motion.spring. */
@Immutable
data class SpringToken(val stiffness: Float, val dampingRatio: Float) {
    fun <T> spec(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}
