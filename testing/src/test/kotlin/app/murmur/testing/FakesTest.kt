package app.murmur.testing

import app.murmur.core.audio.PcmChunk
import app.murmur.core.audio.PcmFormat
import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakesTest {
    @Test
    fun fakeAsrNeedsSpeech() = runTest {
        val asr = FakeAsrEngine(script = "тест")
        val s = asr.startSession(AsrSessionOptions())
        s.accept(PcmChunk(ShortArray(16_000), 0))
        assertEquals(Outcome.Err(MurmurError.NoSpeech), s.finish())
        val s2 = asr.startSession(AsrSessionOptions())
        s2.accept(PcmChunk(syntheticSpeech(), 0))
        assertEquals("тест", (s2.finish() as Outcome.Ok).value.text)
    }

    @Test
    fun fakeAsrCommitsSegmentsWhileAudioFlowsAndDiesWithItsHost() = runTest {
        val e = FakeEngines()
        val s = e.asr.startSession(AsrSessionOptions())
        val pcm = syntheticSpeech(totalMs = 2_000, speechMs = 1_800)
        for (i in pcm.indices step PcmFormat.FRAME_SAMPLES) s.accept(PcmChunk(pcm.copyOfRange(i, minOf(i + PcmFormat.FRAME_SAMPLES, pcm.size)), i.toLong()))
        assertTrue(s.events.first() is AsrEvent.Committed)
        e.asrHost.crash()
        assertEquals(Outcome.Err(MurmurError.ProcessDied(InferProcess.ASR)), s.finish())
        assertEquals(1, e.asrHost.crashes)
    }

    @Test
    fun fakeLlmIsDeterministicAndCanHangOrFailPerUnit() = runTest {
        val out = FakeLlmEngine().generate(LlmRequest("<transcript>\nпривет мир\n</transcript>", 64)).toList()
        assertEquals("Привет мир.", (out.last() as LlmEvent.Done).text)
        val hanging = FakeLlmEngine(hang = true)
        assertNull(withTimeoutOrNull(5_000) { hanging.generate(LlmRequest("x", 8)).toList() })
        val npuBroken = FakeLlmEngine(loadErrors = mutableMapOf(ComputeUnit.NPU to MurmurError.UnitUnavailable(ComputeUnit.NPU)))
        val r = FakeModelResolver(npuFirst = true).resolve(Settings()) as Outcome.Ok
        assertTrue(npuBroken.load(r.value.llm!!.spec) is Outcome.Err)
        assertTrue(npuBroken.load(r.value.llm!!.fallback!!) is Outcome.Ok)
        assertEquals(ComputeUnit.CPU, r.value.asrFallback!!.unit)
    }

    @Test
    fun resolverReportsWhyThereIsNoCleanup() = runTest {
        val r = FakeModelResolver().resolve(Settings(cleanup = CleanupChoice.None)) as Outcome.Ok
        assertNull(r.value.llm)
        assertEquals("none", r.value.llmUnavailable)
        assertEquals(Outcome.Err(MurmurError.NoVadModel), FakeModelResolver(noVad = true).resolve(Settings()))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun virtualClockFollowsTheTestScheduler() = runTest {
        val clock = VirtualClock { testScheduler.currentTime }
        kotlinx.coroutines.delay(1_500)
        assertEquals(1_500, clock.elapsedMs())
    }

    @Test
    fun benchmarkFakeEmitsStepsPerUsableUnit() = runTest {
        val catalog = app.murmur.core.catalog.CatalogParser.bundled()
        val entry = catalog.model(catalog.defaults.llm!!)!!
        val cands = ArtifactSelector.ranked(entry, FakeDeviceProfileRepository.FOLD7, UnitPreference.Auto).take(2)
        val events = FakeBenchmarkRunner().run(entry, cands).toList()
        val finished = events.last() as BenchProgress.Finished
        assertEquals(2, finished.records.size)
    }

    @Test
    fun fakeWorldBuildsDeps() {
        val d = FakeWorld().deps()
        assertTrue(d.prompts.systemPrompt("default.v4").isNotBlank())
    }
}
