package app.murmur.platform.overlay

import android.view.HapticFeedbackConstants
import app.murmur.core.dictation.BubbleAction
import app.murmur.core.dictation.BubbleError
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.dictation.HapticKind
import app.murmur.core.store.BubbleSize
import app.murmur.core.store.Feedback
import app.murmur.platform.overlay.ui.MeterSteps
import app.murmur.platform.overlay.ui.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN 5.3 texts per state, 5.5 error copy, 5.6 sizes, 3.5 level bars, ADR-044 haptics. */
class BubbleCopyTest {
    private fun active(s: DictationBubble) = BubbleCopy.of(BubbleVisual.Active(s))!!

    @Test
    fun everyVisualHasItsTalkBackTextAndLiveRegion() {
        assertNull(BubbleCopy.of(BubbleVisual.Hidden))
        assertEquals(BubbleText(null, R.string.bubble_rest_cd, null, Live.OFF, null), BubbleCopy.of(BubbleVisual.Rest))
        assertEquals(BubbleText(null, R.string.bubble_ready_cd, null, Live.OFF, null), BubbleCopy.of(BubbleVisual.Ready))
        assertEquals(BubbleText(null, R.string.bubble_drag_cd, null, Live.OFF, null), BubbleCopy.of(BubbleVisual.Dragging(false)))
        assertEquals(BubbleText(null, R.string.bubble_finish_cd, R.string.bubble_listening, Live.POLITE, null),
            active(DictationBubble.Listening(0, hold = false, warn = false)))
        assertEquals(BubbleText(R.string.bubble_hold, R.string.bubble_hold_live, null, Live.POLITE, null),
            active(DictationBubble.Listening(0, hold = true, warn = false)))
        assertEquals(BubbleText(R.string.bubble_transcribing, R.string.bubble_transcribing, null, Live.POLITE, null), active(DictationBubble.Transcribing))
        assertEquals(BubbleText(R.string.bubble_polishing, R.string.bubble_polishing, null, Live.POLITE, null), active(DictationBubble.Polishing))
        assertEquals(BubbleText(R.string.bubble_inserted, R.string.bubble_inserted, null, Live.POLITE, null), active(DictationBubble.Inserted(false)))
        assertEquals(BubbleText(R.string.bubble_inserted_nocleanup, R.string.bubble_inserted_nocleanup, null, Live.POLITE, null),
            active(DictationBubble.Inserted(true)))
        assertEquals(BubbleText(R.string.bubble_copied, R.string.bubble_copied_live, null, Live.POLITE, null), active(DictationBubble.Copied(null)))
    }

    @Test
    fun errorsAreAssertiveWithTheirReasonAndAction() {
        val cases = mapOf(
            DictationBubble.Error(BubbleError.NO_SPEECH, BubbleAction.RETRY) to (R.string.bubble_err_nospeech to R.string.bubble_action_retry),
            DictationBubble.Error(BubbleError.MIC_BUSY, null) to (R.string.bubble_err_micbusy to null),
            DictationBubble.Error(BubbleError.NO_MODEL, BubbleAction.OPEN_MODELS) to (R.string.bubble_err_nomodel to R.string.bubble_action_open),
            DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY) to (R.string.bubble_err_load to R.string.bubble_action_retry),
            DictationBubble.Error(BubbleError.TRANSCRIPTION_FAILED, BubbleAction.OPEN_ENTRY) to (R.string.bubble_err_transcription to R.string.bubble_action_open),
            DictationBubble.Error(BubbleError.GENERIC, null) to (R.string.bubble_err_generic to null),
        )
        for ((state, expected) in cases) {
            assertEquals(BubbleText(expected.first, expected.first, null, Live.ASSERTIVE, expected.second), active(state))
        }
        // Every BubbleError has a label.
        assertEquals(BubbleError.entries.size, BubbleError.entries.map(BubbleCopy::errorLabel).toSet().size)
    }

    @Test
    fun sizesScalePillsButNotTheHitAreaBelow48dp() {
        val small = BubbleMetrics.of(BubbleSize.SMALL)
        val standard = BubbleMetrics.of(BubbleSize.STANDARD)
        val large = BubbleMetrics.of(BubbleSize.LARGE)
        assertEquals(listOf(44f, 52f, 60f), listOf(small.diameter, standard.diameter, large.diameter))
        assertEquals(56f, standard.readyHit, 0f)
        assertEquals(48f, small.readyHit, 0f)
        assertEquals(64f, large.readyHit, 0f)
        assertEquals(164f, standard.listeningWidth, 0f)
        assertEquals(164f * 44f / 52f, small.listeningWidth, 0.001f)
        assertEquals(280f, small.maxWidth, 0f)
        // Window band around the visual: the docked visual sits 8 dp from the edge (0 for the tab).
        assertEquals(2f, standard.edgeMargin(BubbleVisual.Ready) - standard.windowPadEdge(BubbleVisual.Ready), 0f)
        assertEquals(0f, standard.edgeMargin(BubbleVisual.Rest) - standard.windowPadEdge(BubbleVisual.Rest), 0f)
        // Ready window = 52 + 2 x 6 = 64 dp >= the 56 dp hit area of DESIGN 5.3.
        assertTrue(standard.diameter + 2 * standard.shadowPad >= standard.readyHit)
    }

    @Test
    fun levelBarsFollowTheMeterAndSilenceIsFlat() {
        val m = BubbleMetrics.of(BubbleSize.STANDARD)
        assertEquals(4f, m.barHeight(0f), 0f)
        assertEquals(24f, m.barHeight(1f), 0f)
        assertEquals(14f, m.barHeight(0.5f), 0f)
        assertEquals(4f, m.barHeight(-1f), 0f)
        assertEquals(24f, m.barHeight(3f), 0f)
        assertEquals(listOf(0, 1, 2, 3), listOf(0f, 0.2f, 0.5f, 0.9f).map(MeterSteps::lit))
    }

    @Test
    fun timerFormat() {
        assertEquals("0:07", formatElapsed(7_400))
        assertEquals("4:30", formatElapsed(270_000))
        assertEquals("5:00", formatElapsed(300_000))
        assertEquals("0:00", formatElapsed(-50))
    }

    @Test
    fun hapticsFollowTheSoundAndVibrationSetting() {
        assertTrue(OverlayFeedback.vibrates(Feedback.VIBRATION))
        assertTrue(OverlayFeedback.vibrates(Feedback.BOTH))
        assertFalse(OverlayFeedback.vibrates(Feedback.SOUND))
        assertFalse(OverlayFeedback.vibrates(Feedback.OFF))
        assertEquals(HapticFeedbackConstants.CONFIRM, OverlayFeedback.constantFor(HapticKind.CONFIRM))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, OverlayFeedback.constantFor(HapticKind.CLOCK_TICK))
        assertEquals(HapticFeedbackConstants.REJECT, OverlayFeedback.constantFor(HapticKind.REJECT))
    }
}
