package app.murmur.wiring

import app.murmur.core.dictation.DictationConfig
import app.murmur.platform.a11y.InsertionLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-090 items 2 and 4: the floors that keep a debug timeout scale below 1 from changing what the app does. */
class TimeoutFloorsTest {
    @Test fun binderTimeoutsAreLengthenedInDebugButNeverShortened() {
        assertEquals(1.0, binderScale(debug = false, debugTimeoutScale = 4f), 0.0)
        assertEquals(4.0, binderScale(debug = true, debugTimeoutScale = 4f), 0.0)
        assertEquals(1.0, binderScale(debug = true, debugTimeoutScale = 0.1f), 0.0)
        assertEquals(1.0, binderScale(debug = true, debugTimeoutScale = 0f), 0.0)
        assertEquals(1.0, binderScale(debug = true, debugTimeoutScale = -2f), 0.0)
    }

    /** The orchestrator's DELIVERY timer (core) must outlast the insertion ladder (platform) at every scale. */
    @Test fun deliveryTimerOutlastsTheInsertionLadder() {
        val c = DictationConfig()
        val margin = 400L
        assertTrue(c.deliveryMinMs >= InsertionLadder.LADDER_BUDGET_MS + margin)
        for (scale in listOf(0.01, 0.1, 0.5, 1.0, 3.0)) {
            assertTrue("scale $scale", c.copy(timeoutScale = scale).deliveryTimerMs() >= InsertionLadder.LADDER_BUDGET_MS + margin)
        }
    }
}
