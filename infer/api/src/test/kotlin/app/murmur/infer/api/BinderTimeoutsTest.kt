package app.murmur.infer.api

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderTimeoutsTest {
    private val t = BinderTimeouts()

    @Test
    fun prefixScalesWithTokensAtTheFloorSpeed() {
        // ADR-049: 3,240 tokens (synthetic: LFM3's first measurement; the catalog says 3,226) at the 40 tok/s floor -> 2 x 81 s = 162 s.
        assertEquals(162_000, t.prefixMs(3_240))
        // The acceptance case of the instrumented test: 3,000 tokens -> 150 s.
        assertEquals(150_000, t.prefixMs(3_000))
        // Never below 30 s (a restore from disk, a short prefix).
        assertEquals(30_000, t.prefixMs(0))
        assertEquals(30_000, t.prefixMs(600))
        assertEquals(30_050, t.prefixMs(601))
    }

    @Test
    fun prefixUsesTheBenchmarkedSpeedWhenKnown() {
        // AI Hub S25 CPU LFM3: 3,240 tokens (synthetic, as above) at 368 tok/s -> 2 x 8.8 s = 17.6 s, floored to 30 s.
        assertEquals(30_000, t.prefixMs(3_240, 368.0))
        // Emulator LFM3 ~15 tok/s: 1,123 tokens -> 2 x 74.9 s.
        assertEquals(149_733, t.prefixMs(1_123, 15.0)) // 149,733.3 ms
        // A broken (zero / negative) measurement falls back to the floor instead of dividing by it.
        assertEquals(162_000, t.prefixMs(3_240, 0.0))
        assertEquals(162_000, t.prefixMs(3_240, -1.0))
    }

    @Test
    fun benchFormula() {
        // (512/40 + 128/5) x 3 reps x 2 s = (12.8 + 25.6) x 6 = 230.4 s.
        assertEquals(230_400, t.benchMs(512, 128, 3))
        assertEquals(30_000, t.benchMs(16, 4, 1)) // (0.4 + 0.8) x 2 = 2.4 s -> 30 s floor
    }

    @Test
    fun scaleMultipliesEveryTimeout() {
        val s = t.copy(scale = 0.1)
        assertEquals(15_000, s.prefixMs(3_000))
        assertEquals(16_200, s.prefixMs(3_240))
        assertEquals(3_000, s.prefixMs(0))
        assertEquals(23_040, s.benchMs(512, 128, 3))
        assertEquals(300, s.scaled(s.bindMs))
        assertEquals(1_500, s.scaled(s.asrLoadMs))
        val slow = t.copy(scale = 4.0)
        assertEquals(648_000, slow.prefixMs(3_240))
        assertEquals(120_000, slow.scaled(slow.llmLoadMs))
        assertEquals(16_000, slow.asrFinalMs(0))
    }

    @Test
    fun asrFinalIsBasePlusHalfASecondPerTailSecond() {
        assertEquals(4_000, t.asrFinalMs(0))
        assertEquals(9_000, t.asrFinalMs(10_000))
        assertEquals(4_250, t.asrFinalMs(500))
        assertEquals(4_000, t.asrFinalMs(-200)) // a committed segment past the audio end never shortens the base
    }

    @Test
    fun tokenEstimateIs0Point4PerChar() {
        assertEquals(3_000, BinderTimeouts.estimateTokens("a".repeat(7_500)))
        assertEquals(1, BinderTimeouts.estimateTokens("ab"))
        assertEquals(0, BinderTimeouts.estimateTokens(""))
    }

    @Test
    fun timeoutMapping() {
        val expected = mapOf(
            BinderCall.ASR_LOAD to Stage.ASR_LOAD,
            BinderCall.ASR_FINISH to Stage.ASR_FINAL,
            BinderCall.ASR_START_SESSION to Stage.CALL,
            BinderCall.OCR_LOAD to Stage.OCR,
            BinderCall.OCR_RECOGNIZE to Stage.OCR,
            BinderCall.LLM_LOAD to Stage.LLM_LOAD,
            BinderCall.LLM_PREFIX to Stage.LLM_PREFIX,
            BinderCall.LLM_BENCH to Stage.BENCH,
            BinderCall.LLM_GENERATE to Stage.CALL,
            BinderCall.API_VERSION to Stage.CALL,
            BinderCall.PROBE to Stage.CALL,
            BinderCall.MEMORY to Stage.CALL,
            BinderCall.UNLOAD to Stage.CALL,
            BinderCall.CANCEL to Stage.CALL,
        )
        assertEquals(BinderCall.entries.toSet(), expected.keys)
        for ((call, stage) in expected) {
            assertEquals(call.name, MurmurError.Timeout(stage), call.timeoutError())
            assertTrue(call.timeoutError().retryable)
        }
        assertEquals("timeout.llm_prefix", BinderCall.LLM_PREFIX.timeoutError().code)
        assertEquals("timeout.bench", BinderCall.LLM_BENCH.timeoutError().code)
        // A late OCR never kills :asr (a live ASR session shares the process); everything else is treated as death.
        assertFalse(BinderCall.OCR_RECOGNIZE.killOnTimeout)
        assertFalse(BinderCall.OCR_LOAD.killOnTimeout)
        BinderCall.entries.filter { it.stage != Stage.OCR }.forEach { assertTrue(it.name, it.killOnTimeout) }
    }
}
