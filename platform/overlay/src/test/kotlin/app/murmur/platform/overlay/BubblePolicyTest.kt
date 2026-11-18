package app.murmur.platform.overlay

import app.murmur.core.dictation.DictationBubble
import org.junit.Assert.assertEquals
import org.junit.Test

class BubblePolicyTest {
    private val base = VisibilityInput(editableFocused = true, bubbleAllowedForField = true, appHidden = false, hiddenUntilMs = null,
        nowMs = 10_000, showAlways = false, idleSinceMs = 9_000, tuckIn = true, talkBackOn = false)

    @Test
    fun designVisibilityRules() {
        assertEquals(BubbleVisual.Ready, BubblePolicy.merge(DictationBubble.Idle, base, false, false))
        assertEquals(BubbleVisual.Rest, BubblePolicy.merge(DictationBubble.Idle, base.copy(idleSinceMs = 5_000), false, false))
        assertEquals(BubbleVisual.Ready, BubblePolicy.merge(DictationBubble.Idle, base.copy(idleSinceMs = 5_000, talkBackOn = true), false, false))
        assertEquals(BubbleVisual.Hidden, BubblePolicy.merge(DictationBubble.Idle, base.copy(bubbleAllowedForField = false), false, false))
        assertEquals(BubbleVisual.Hidden, BubblePolicy.merge(DictationBubble.Idle, base.copy(hiddenUntilMs = 20_000), false, false))
        assertEquals(BubbleVisual.Active(DictationBubble.Polishing), BubblePolicy.merge(DictationBubble.Polishing, base.copy(appHidden = true), false, false))
    }
}
