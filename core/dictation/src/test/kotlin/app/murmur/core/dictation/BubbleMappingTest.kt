package app.murmur.core.dictation

import app.murmur.core.model.MurmurError
import app.murmur.core.model.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleMappingTest {
    private val s = SessionId(1)

    @Test
    fun designStatesMap() {
        assertEquals(DictationBubble.Idle, BubbleMapping.of(DictationState.Idle))
        assertEquals(DictationBubble.Listening(5, hold = true, warn = false), BubbleMapping.of(DictationState.Arming(s, StartMode.HOLD, 5)))
        assertEquals(DictationBubble.Transcribing, BubbleMapping.of(DictationState.Finishing(s, 9, showTranscribing = true)))
        assertEquals(DictationBubble.Inserted(noCleanup = true),
            BubbleMapping.of(DictationState.Done(s, DictationOutcome.Inserted(null, noCleanup = true))))
        assertEquals(DictationBubble.Error(BubbleError.NO_SPEECH, BubbleAction.RETRY),
            BubbleMapping.of(DictationState.Done(s, DictationOutcome.Failed(MurmurError.NoSpeech, null))))
        assertEquals(DictationBubble.Error(BubbleError.NO_MODEL, BubbleAction.OPEN_MODELS), BubbleMapping.errorBubble(MurmurError.NoAsrModel))
    }

    @Test
    fun llmTimeoutIsAdaptiveAndCapped() {
        val c = DictationConfig()
        assertEquals(20_000, c.llmTimeoutMs(null))
        assertEquals(4_000 + 3_000, c.llmTimeoutMs(2_000))
        assertEquals(20_000, c.llmTimeoutMs(60_000))
    }
}
