package app.murmur.infer.api

import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.SyntheticBench
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Stage
import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every payload that crosses the binder survives InferApi.json unchanged (ARCHITECTURE.md 4.3). */
class InferApiJsonTest {
    private fun <T> roundTrip(s: KSerializer<T>, v: T): String {
        val json = InferApi.encode(s, v)
        assertEquals(json, v, InferApi.decode(s, json))
        return json
    }

    @Test
    fun errorsAndStatsRoundTrip() {
        val e = MurmurError.EngineLoadFailed(ModelKind.LLM, "ctx")
        assertEquals(e, InferApi.decodeError(InferApi.encodeError(e)))
        val s = LlmStats(10, 5, 1.0, 2.0, 3.0, 4.0, 5.0, 0, ComputeUnit.NPU)
        assertEquals(s, InferApi.decode(LlmStats.serializer(), InferApi.encode(LlmStats.serializer(), s)))
        assertEquals(MurmurError.Internal("bad error payload"), InferApi.decodeError("{"))
    }

    @Test
    fun loadPayloads() {
        val spec = LoadSpec(
            kind = ModelKind.ASR, modelId = ModelId("gigaam-v3-rnnt"), artifactId = ArtifactId("gigaam-v3-rnnt@cpu-int8"),
            runtime = "sherpa-onnx", unit = ComputeUnit.NPU, dir = "/data/user/0/app.murmur/files/models/x",
            layout = mapOf("encoder" to "encoder.onnx", "decoder" to "decoder.onnx"), maxWindowSec = 20.0,
            options = mapOf(LoadOptions.THREADS to "4", LoadOptions.N_CTX to "4096"),
        )
        roundTrip(LoadSpec.serializer(), spec)
        roundTrip(LoadSpec.serializer(), spec.copy(maxWindowSec = null, options = emptyMap()))
        roundTrip(LoadInfo.serializer(), LoadInfo(ComputeUnit.NPU, 812, "HTP0", unitShare = 0.97))
        roundTrip(LoadInfo.serializer(), LoadInfo(ComputeUnit.CPU, 120, "CPU"))
    }

    @Test
    fun probeFactsAndMemory() {
        val probe = ProcessProbe(
            InferProcess.LLM, runtimes = setOf("llama.cpp"), units = setOf(ComputeUnit.NPU, ComputeUnit.CPU),
            facts = mapOf(ProcessProbe.FACT_NATIVE_BUILD to "b12520-hexagon", "htp.arch" to "v79"),
        )
        val json = roundTrip(ProcessProbe.serializer(), probe)
        assertTrue(json, json.contains("\"native.build\":\"b12520-hexagon\""))
        roundTrip(ProcessProbe.serializer(), ProcessProbe(InferProcess.ASR, emptySet(), emptySet()))
        roundTrip(ProcessMemory.serializer(), ProcessMemory(1_234_567, 1_000_000, 900_000, 5_432))
    }

    @Test
    fun asrSessionPayloads() {
        roundTrip(AsrSessionOptions.serializer(), AsrSessionOptions(language = "ru", emitPartials = false))
        roundTrip(AsrSessionOptions.serializer(), AsrSessionOptions())
        val events = listOf(
            AsrEvent.Partial("при"),
            AsrEvent.Committed(3, "привет, мир 👋", 1_200, 2_480, "ru"),
            AsrEvent.Committed(4, "hello", 2_480, 3_000, null),
            AsrEvent.Speech(speaking = true, atMs = 640),
            AsrEvent.Failure(MurmurError.ProcessDied(InferProcess.ASR)),
            AsrEvent.Failure(MurmurError.Timeout(Stage.ASR_FINAL)),
        )
        for (e in events) {
            val json = roundTrip(AsrEvent.serializer(), e)
            assertTrue(json, json.startsWith("{\"type\":"))
        }
        roundTrip(Transcript.serializer(), Transcript("привет мир", "ru", 2, 10_000, 8_200, 140, ComputeUnit.NPU))
        roundTrip(Transcript.serializer(), Transcript("", null, 0, 0, 0, 0, ComputeUnit.CPU))
    }

    @Test
    fun ocrPayloads() {
        val exclude = listOf(Rect(0, 0, 1968, 63), Rect(0, 1343, 1968, 2184))
        roundTrip(ListSerializer(Rect.serializer()), exclude)
        assertEquals("[]", InferApi.encode(ListSerializer(Rect.serializer()), emptyList()))
        roundTrip(
            ScreenText.serializer(),
            ScreenText(listOf(1968, 2184), listOf(OcrLine("Встреча в 15:00", 0.93, listOf(10, 20, 300, 60)))),
        )
    }

    @Test
    fun llmPayloads() {
        roundTrip(PrefixSpec.serializer(), PrefixSpec("system: clean the text\n", "ab".repeat(32)))
        roundTrip(PrefixInfo.serializer(), PrefixInfo(tokens = 3_240, prefillMs = 81_000, restoredFromDisk = false, bytes = 52_428_800))
        val request = LlmRequest(suffix = "<transcript>ну короче привет</transcript>", maxTokens = 160, minTokens = 44,
            stop = listOf("</out>"), temperature = 0f)
        val json = roundTrip(LlmRequest.serializer(), request)
        assertTrue(json, json.contains("\"minTokens\":44"))
        // encodeDefaults: minTokens always crosses, even at its default.
        assertTrue(InferApi.encode(LlmRequest.serializer(), LlmRequest("x", 10)).contains("\"minTokens\":0"))
        roundTrip(LlmStats.serializer(), LlmStats(400, 37, 2.0, 310.5, 320.0, 1_800.0, 2_150.0, LlmStats.STOP_MAX_TOKENS, ComputeUnit.GPU))
        roundTrip(SyntheticBench.serializer(), SyntheticBench(ppTps = 368.2, tgTps = 21.4, ppMs = 1_390.0, tgMs = 5_980.0))
    }

    @Test
    fun everyErrorFamilyRoundTrips() {
        val errors = listOf(
            MurmurError.ProcessDied(InferProcess.LLM),
            MurmurError.Timeout(Stage.LLM_PREFIX),
            MurmurError.Timeout(Stage.BENCH),
            MurmurError.UnitUnavailable(ComputeUnit.NPU),
            MurmurError.EngineRuntimeMissing("qnn"),
            MurmurError.OutOfMemory(ModelKind.LLM),
            MurmurError.LlmContextOverflow(neededTokens = 5_000, nCtx = 4_096),
            MurmurError.AsrFailed("pcm_overflow"),
            MurmurError.LlmFailed(null),
            MurmurError.OcrFailed("shm"),
            MurmurError.NoSpeech,
            MurmurError.Internal("api"),
        )
        for (e in errors) {
            val json = InferApi.encodeError(e)
            assertEquals(json, e, InferApi.decodeError(json))
            assertEquals(e.code, InferApi.decodeError(json).code)
        }
    }

    @Test
    fun unknownKeysAreRejectedSoThatVersionDriftIsLoud() {
        val r = runCatching { InferApi.decode(LoadInfo.serializer(), """{"unit":"cpu","loadMs":1,"deviceName":"CPU","extra":1}""") }
        assertTrue(r.isFailure)
    }
}
