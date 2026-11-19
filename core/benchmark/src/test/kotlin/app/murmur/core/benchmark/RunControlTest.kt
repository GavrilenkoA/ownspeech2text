@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.murmur.core.benchmark

import app.murmur.core.audio.PcmChunk
import app.murmur.core.engine.AsrEngine
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSession
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Engines
import app.murmur.core.engine.HostState
import app.murmur.core.engine.LlmEngine
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.SyntheticBench
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.Stage
import app.murmur.core.store.BenchmarkResultData
import app.murmur.testing.FakeAsrEngine
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakeInferenceHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tail measurement, unit timeouts, stop before a run starts, LLM step breakdown (review 2026-11-19, F1/F4/F5/F8). */
class RunControlTest {
    // ------------------------------------------------------------------------------------------------- tail (F1)

    @Test
    fun tailIsTheLastSegmentsDecodeNotTheWholeBacklog() = runTest {
        val entry = BenchRig.PARAKEET
        val asr = BacklogAsrEngine(backgroundScope, ArrayDeque(BenchRig.perfectScripts(entry)))
        val fake = FakeEngines()
        val rig = BenchRig(this, object : Engines by fake { override val asr: AsrEngine = asr })
        val events = rig.run(entry)

        val record = (events.last() as BenchProgress.Finished).records.single()
        val r = record.result as BenchmarkResultData.Asr
        // Decode = audio / 10. ru 10,080 ms = 3 closed 3 s segments + a 1,080 ms tail; en 10,200 = 3 + 1,200;
        // mixed 12,080 = 4 + 80. The whole backlog sits in finish(); the user waits only for the last segment.
        assertEquals(listOf(1_008L, 1_020L, 1_208L), r.perClip.map { it.processMs })
        assertEquals(108L, r.tailMs) // median of 108, 120, 8
        assertTrue(r.valid)
        assertNull(record.notes[BenchNotes.ASR_TAIL_WHOLE_DECODE])
    }

    @Test
    fun aSessionWithoutProgressEventsReportsTheWholeDecodeAsTail() = runTest {
        val entry = BenchRig.GIGAAM
        val host = FakeInferenceHost(InferProcess.ASR)
        val fake = FakeEngines(asrHost = host, asr = FakeAsrEngine(scripts = ArrayDeque(BenchRig.perfectScripts(entry)),
            finishDelayMs = 700, committedEveryChunks = 0, host = host))
        val rig = BenchRig(this, fake)
        val record = (rig.run(entry).last() as BenchProgress.Finished).records.single()
        val r = record.result as BenchmarkResultData.Asr
        assertEquals(700L, r.perClip.single().processMs)
        assertEquals(700L, r.tailMs) // no marker: an upper bound, and the record says so
        assertEquals("ru", record.notes[BenchNotes.ASR_TAIL_WHOLE_DECODE])
    }

    // --------------------------------------------------------------------------------------------- timeout (F4)

    @Test
    fun anAsrUnitThatHangsTimesOutCancelsItsSessionAndShutsTheProcessDown() = runTest {
        val entry = BenchRig.GIGAAM
        val host = FakeInferenceHost(InferProcess.ASR)
        val fake = FakeEngines(asrHost = host, asr = FakeAsrEngine(scripts = ArrayDeque(BenchRig.perfectScripts(entry)),
            finishDelayMs = 20 * 60_000L, host = host))
        val rig = BenchRig(this, fake, config = BenchConfig(unitTimeoutMs = 60_000))
        val events = rig.run(entry)

        val failed = events.filterIsInstance<BenchProgress.UnitFailed>().single()
        assertEquals(MurmurError.Timeout(Stage.BENCH), failed.error)
        assertEquals("timeout.bench", failed.error.code)
        assertEquals(BenchProgress.Failed(MurmurError.Timeout(Stage.BENCH)), events.last())
        assertEquals(60_000L, testScheduler.currentTime) // the budget, not the 20-minute decode
        assertTrue(fake.asr.sessions.last().cancelled)
        assertNull(fake.asr.loaded)
        assertEquals(HostState.Down, host.state.value)
        assertTrue(rig.saved(entry).isEmpty())
    }

    @Test
    fun anLlmUnitThatHangsTimesOutCancelsTheGenerationAndShutsTheProcessDown() = runTest {
        val entry = BenchRig.QWEN
        val rig = BenchRig(this, config = BenchConfig(unitTimeoutMs = 60_000))
        rig.fake.llm.hang = true
        val events = rig.run(entry)

        assertEquals(MurmurError.Timeout(Stage.BENCH), events.filterIsInstance<BenchProgress.UnitFailed>().single().error)
        assertEquals(BenchProgress.Failed(MurmurError.Timeout(Stage.BENCH)), events.last())
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(1, rig.fake.llm.cancelled)
        assertNull(rig.fake.llm.loadedUnit)
        assertEquals(HostState.Down, rig.fake.llmHost.state.value)
    }

    // ------------------------------------------------------------------------------------------------ stop (F5)

    @Test
    fun aStopBeforeCollectionStopsThatRun() = runTest {
        val entry = BenchRig.GIGAAM
        val rig = BenchRig(this, FakeEngines().also { it.asr.scripts.addAll(BenchRig.perfectScripts(entry)) })
        val flow = rig.runner.run(entry, rig.candidates(entry))
        rig.runner.stop()
        assertEquals(emptyList<BenchProgress>(), flow.toList())
        assertEquals(0, rig.fake.asrHost.connects) // nothing was started
        // A run created after the stop is a new run and finishes.
        assertTrue(rig.run(entry).last() is BenchProgress.Finished)
    }

    @Test
    fun aStopAlsoEndsARunWaitingForTheOneInFlight() = runTest {
        val entry = BenchRig.GIGAAM
        val rig = BenchRig(this, FakeEngines().also { it.asr.finishDelayMs = 60_000 })
        val first = mutableListOf<BenchProgress>()
        val second = mutableListOf<BenchProgress>()
        val a = launch { rig.runner.run(entry, rig.candidates(entry)).collect { first += it } }
        val b = launch { rig.runner.run(entry, rig.candidates(entry)).collect { second += it } }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(BenchStep.SAMPLE_RU, first.steps().last().step)
        assertTrue(second.isEmpty()) // queued behind the first run
        rig.runner.stop()
        runCurrent()
        assertTrue(a.isCompleted && b.isCompleted)
        assertTrue(first.none { it is BenchProgress.Finished })
        assertTrue(second.isEmpty())
        assertEquals(1, rig.fake.asrHost.connects) // the queued run never started
        assertEquals(HostState.Down, rig.fake.asrHost.state.value)
    }

    // --------------------------------------------------------------------------------------- LLM breakdown (F8)

    @Test
    fun theCleanupStepIsAccountedForInTheNotes() = runTest {
        val entry = BenchRig.QWEN
        val fake = FakeEngines()
        fake.llm.delayPerPieceMs = 100 // 60-word case: 6 s per pass
        val slowBench = object : LlmEngine by fake.llm {
            override suspend fun bench(nPrompt: Int, nGen: Int, reps: Int): Outcome<SyntheticBench> {
                delay(2_500)
                return fake.llm.bench(nPrompt, nGen, reps)
            }
        }
        val rig = BenchRig(this, object : Engines by fake { override val llm: LlmEngine = slowBench })
        val events = rig.run(entry)
        val notes = (events.last() as BenchProgress.Finished).records.single().notes
        assertEquals("6000", notes[BenchNotes.LLM_WARMUP_MS])
        assertEquals("6000", notes[BenchNotes.LLM_CASE_MS])
        assertEquals("2500", notes[BenchNotes.LLM_SYNTHETIC_MS])
        val step = events.steps().single { it.step == BenchStep.CLEANUP_CASE && it.done }
        assertEquals(14_500L, step.elapsedMs)
    }
}

/**
 * ASR that decodes like :asr (WP-06 AsrSessionCore): accept() only queues; an engine coroutine cuts [segmentMs]
 * segments, sends Speech(false) when one closes, decodes it in segment / [rtfx] of (virtual) time, then sends
 * Committed. finish() waits for the queue and decodes the open tail the same way.
 */
private class BacklogAsrEngine(
    private val scope: CoroutineScope,
    private val scripts: ArrayDeque<String>,
    private val segmentMs: Long = 3_000,
    private val rtfx: Long = 10,
) : AsrEngine {
    override suspend fun load(asr: LoadSpec, vad: LoadSpec?): Outcome<LoadInfo> = Outcome.Ok(LoadInfo(asr.unit, 5, "backlog-cpu"))
    override suspend fun unload() {}
    override suspend fun startSession(options: AsrSessionOptions): AsrSession = Session(scripts.removeFirst())

    private inner class Session(private val text: String) : AsrSession {
        private val inbox = Channel<ShortArray?>(Channel.UNLIMITED)
        private val out = Channel<AsrEvent>(Channel.UNLIMITED)
        override val events: Flow<AsrEvent> = out.receiveAsFlow()
        private val result = CompletableDeferred<Transcript>()
        private val worker = scope.launch {
            val segmentSamples = segmentMs * 16
            var open = 0L
            var total = 0L
            var index = 0
            suspend fun decode(samples: Long) {
                out.send(AsrEvent.Speech(false, total / 16))
                delay(samples / 16 / rtfx)
                out.send(AsrEvent.Committed(index++, "segment", (total - samples) / 16, total / 16, "ru"))
            }
            for (chunk in inbox) {
                if (chunk == null) break
                open += chunk.size
                total += chunk.size
                if (open >= segmentSamples) { decode(open); open = 0 }
            }
            if (open > 0) decode(open)
            result.complete(Transcript(text, "ru", index, total / 16, total / 16, tailDecodeMs = 0, unit = ComputeUnit.CPU))
        }

        override fun accept(chunk: PcmChunk) { inbox.trySend(chunk.samples) }
        override suspend fun finish(): Outcome<Transcript> { inbox.trySend(null); return Outcome.Ok(result.await()) }
        override fun cancel() { worker.cancel() }
    }
}
