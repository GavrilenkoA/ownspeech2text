package app.murmur.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/** DESIGN 4 grouped list: "pressed: ink at 6 % overlay". Keyboard focus shows the same overlay. */
internal const val PRESSED_OVERLAY_ALPHA = 0.06f

/**
 * The design's press feedback for every Murmur clickable (provided as LocalIndication by [MurmurTheme]): a flat ink
 * overlay over the clickable's bounds, faded in over the `instant` duration. No ripple (DESIGN 3.1: no non-flat fills
 * except the skeleton). Clip the clickable to its shape before `clickable` so the overlay follows the shape.
 */
object PressOverlay : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()

    private class Node(private val source: InteractionSource) :
        Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
        private val alpha = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                var pressed = 0
                var focused = 0
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                        is FocusInteraction.Focus -> focused++
                        is FocusInteraction.Unfocus -> focused = (focused - 1).coerceAtLeast(0)
                    }
                    val target = if (pressed > 0 || focused > 0) PRESSED_OVERLAY_ALPHA else 0f
                    launch {
                        alpha.animateTo(target, tween(Motion.instantMs)) { invalidateDraw() }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val a = alpha.value
            if (a > 0f) drawRect(currentValueOf(LocalMurmurColors).ink.copy(alpha = a))
        }
    }
}
