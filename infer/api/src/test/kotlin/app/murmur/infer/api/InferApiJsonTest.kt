package app.murmur.infer.api

import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.LlmStats
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import org.junit.Assert.assertEquals
import org.junit.Test

class InferApiJsonTest {
    @Test
    fun errorsAndStatsRoundTrip() {
        val e = MurmurError.EngineLoadFailed(ModelKind.LLM, "ctx")
        assertEquals(e, InferApi.decodeError(InferApi.encodeError(e)))
        val s = LlmStats(10, 5, 1.0, 2.0, 3.0, 4.0, 5.0, 0, ComputeUnit.NPU)
        assertEquals(s, InferApi.decode(LlmStats.serializer(), InferApi.encode(LlmStats.serializer(), s)))
        assertEquals(MurmurError.Internal("bad error payload"), InferApi.decodeError("{"))
    }
}
