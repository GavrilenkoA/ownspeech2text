@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.murmur.core.benchmark

import app.murmur.core.engine.AsrEngine
import app.murmur.core.engine.AsrSession
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Engines
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.BenchmarkResultData
import app.murmur.testing.FakeAsrEngine
import app.murmur.testing.FakeDeviceProfileRepository
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakeInferenceHost
import app.murmur.core.model.InferProcess
import app.murmur.core.engine.HostState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBenchmarkTest {
    private fun engines(scripts: List<String>, host: FakeInferenceHost = FakeInferenceHost(InferProcess.ASR)): FakeEngines =
        FakeEngines(asrHost = host, asr = FakeAsrEngine(scripts = ArrayDeque(scripts), host = host))

    @Test
    fun ruEnModelRunsTheDesignChecklistAndSavesAValidRecord() = runTest {
        val entry = BenchRig.PARAKEET
        val rig = BenchRig(this, engines(BenchRig.perfectScripts(entry)))
        val events = rig.run(entry)

        // DESIGN bench-run: Load model / Russian sample / English sample / (mixed sample) / Unload and measure memory.
        assertEquals(
            listOf(BenchStep.LOAD, BenchStep.SAMPLE_RU, BenchStep.SAMPLE_EN, BenchStep.SAMPLE_MIXED, BenchStep.UNLOAD_MEMORY),
            events.startedSteps(),
        )
        assertTrue(events.first() is BenchProgress.Planned)
        assertEquals(events.startedSteps(), (events.first() as BenchProgress.Planned).steps.map { it.step })
        assertEquals(listOf(10_080L, 10_200L, 12_080L), events.steps().filter { it.sampleId != null && !it.done }.map { it.audioMs })
        // Every step closes before the next one opens.
        events.steps().chunked(2).forEach { (start, end) -> assertEquals(start.step, end.step); assertFalse(start.done); assertTrue(end.done) }

        val finished = events.last() as BenchProgress.Finished
        val record = finished.records.single()
        assertEquals(record, rig.saved(entry).single())
        assertEquals(ComputeUnit.CPU, record.unit)
        assertEquals("parakeet-tdt-0.6b-v3@onnx-int8", record.artifactId.value)
        assertEquals("1.0.0-test", record.appVersion)
        assertEquals(rig.environment.buildFingerprint, record.buildFingerprint)
        assertEquals("none", record.notes[BenchNotes.THERMAL_BEFORE])
        assertEquals("none", record.notes[BenchNotes.THERMAL_AFTER])
        val asr = record.result as BenchmarkResultData.Asr
        assertTrue(asr.valid)
        assertNull(asr.invalidReason)
        assertEquals(listOf("ru", "en", "mixed"), asr.perClip.map { it.clip })
        assertEquals(listOf(0.0, 0.0, 0.0), asr.perClip.map { it.wer })
        assertEquals("fake-cpu", asr.ranOn)
        // FakeInferenceHost.memory(): VmHWM 100,000 kB, PSS 80,000 kB -> peak = max / 1024.
        assertEquals(97L, asr.peakMemMb)
        assertEquals("80000", record.notes[BenchNotes.MEM_PSS_KB])
        // Unloaded at the end, and the process is gone.
        assertNull(rig.fake.asr.loaded)
        assertEquals(HostState.Down, rig.fake.asrHost.state.value)
    }

    @Test
    fun ruOnlyEntrySkipsTheEnglishAndMixedClips() = runTest {
        val entry = BenchRig.GIGAAM
        val rig = BenchRig(this, engines(BenchRig.perfectScripts(entry)))
        val events = rig.run(entry)
        assertEquals(listOf(BenchStep.LOAD, BenchStep.SAMPLE_RU, BenchStep.UNLOAD_MEMORY), events.startedSteps())
        assertEquals(2, rig.fake.asr.sessions.size) // one warm-up + one measured pass of the Russian clip only
        val asr = (events.last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Asr
        assertEquals(listOf("ru"), asr.perClip.map { it.clip })
        assertTrue(asr.valid)
    }

    @Test
    fun timingsAreMediansAndSumsOfTheMeasuredPassesInVirtualTime() = runTest {
        val entry = BenchRig.PARAKEET
        val fake = engines(BenchRig.perfectScripts(entry))
        val rig = BenchRig(this, fake)
        fake.asr.loadDelayMs = 800
        // Scripted decode time per clip (warm-up and measured pass of the same clip take the same time here).
        val decodeMs = mapOf("ru" to 400L, "en" to 600L, "mixed" to 900L)
        val events = rig.runner.run(entry, rig.candidates(entry)).onEach { e ->
            if (e is BenchProgress.Step && !e.done && e.sampleId != null) fake.asr.finishDelayMs = decodeMs.getValue(e.sampleId)
        }.toList()

        val asr = (events.last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Asr
        assertEquals(listOf(400L, 600L, 900L), asr.perClip.map { it.processMs })
        assertEquals(600L, asr.tailMs) // median of the three finish() latencies
        assertEquals((10_080 + 10_200 + 12_080) / 1_900.0, asr.rtfx, 1e-9)
        assertEquals(800L, asr.loadMsCold)
        assertEquals(800L, asr.loadMsWarm)
        // Step timings shown by the sheet: warm-up + measured pass per clip, load = cold + warm load.
        val durations = events.steps().filter { it.done }.associate { (it.sampleId ?: it.step.name) to it.elapsedMs }
        assertEquals(mapOf("LOAD" to 1_600L, "ru" to 800L, "en" to 1_200L, "mixed" to 1_800L, "UNLOAD_MEMORY" to 0L), durations)
        assertEquals(1_600L + 800 + 1_200 + 1_800, rig.saved(entry).single().notes[BenchNotes.WALL_MS]!!.toLong())
    }

    @Test
    fun clipsArePushedAsFastAsAcceptTakesThemNotInRealTime() = runTest {
        val entry = BenchRig.PARAKEET
        val rig = BenchRig(this, engines(BenchRig.perfectScripts(entry)))
        val events = rig.run(entry)
        val asr = (events.last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Asr
        // The 12 s mixed clip (and the whole run) take no virtual time with a zero-delay fake: nothing waits for the audio.
        assertEquals(12_080L, asr.perClip.single { it.clip == "mixed" }.audioMs)
        assertTrue(asr.perClip.all { it.processMs < 1_000 })
        assertTrue("virtual time ${testScheduler.currentTime} ms", testScheduler.currentTime < 1_000)
        // Every sample reached the session, in 20 ms frames.
        val sessions = rig.fake.asr.sessions
        assertEquals(BenchRig.INPUTS.clips.flatMap { listOf(it.pcm16k.size.toLong(), it.pcm16k.size.toLong()) }, sessions.map { it.samples })
        assertTrue(asr.rtfx > 1_000) // clamped at 1 ms of processing: finite, serialisable
    }

    @Test
    fun eightKilohertzModelGetsTheSame16kHzStreamTheCapturePathFeeds() = runTest {
        val entry = BenchRig.T_ONE
        assertEquals(8_000, entry.asr!!.sampleRate)
        val rig = BenchRig(this, engines(BenchRig.perfectScripts(entry)))
        rig.run(entry)
        // :asr resamples to the feature rate itself (WP-06); a pre-resampled stream would be decoded at double speed.
        assertEquals(BenchRig.clip("ru").pcm16k.size.toLong(), rig.fake.asr.sessions.last().samples)
    }

    @Test
    fun stopCancelsTheRunEmitsNoFinishedAndLeavesNoProcessLoaded() = runTest {
        val entry = BenchRig.PARAKEET
        val fake = engines(BenchRig.perfectScripts(entry))
        val rig = BenchRig(this, fake)
        val events = rig.runner.run(entry, rig.candidates(entry)).onEach { e ->
            if (e is BenchProgress.Step && e.step == BenchStep.SAMPLE_EN && !e.done) rig.runner.stop()
        }.toList()

        assertTrue(events.none { it is BenchProgress.Finished })
        assertTrue(events.none { it is BenchProgress.Failed })
        assertEquals(BenchStep.SAMPLE_EN, events.steps().last().step)
        assertTrue(rig.saved(entry).isEmpty())
        assertNull(fake.asr.loaded)
        assertEquals(HostState.Down, fake.asrHost.state.value)
        assertEquals(2, fake.asrHost.shutdowns) // the cold start + the stop
    }

    @Test
    fun stopAbandonsALongStepInFlight() = runTest {
        val entry = BenchRig.GIGAAM
        val fake = engines(BenchRig.perfectScripts(entry))
        fake.asr.finishDelayMs = 60_000 // a decode that would take a minute
        val rig = BenchRig(this, fake)
        val events = mutableListOf<BenchProgress>()
        val job = launch { rig.runner.run(entry, rig.candidates(entry)).collect { events += it } }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(BenchStep.SAMPLE_RU, events.steps().last().step)
        rig.runner.stop()
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(testScheduler.currentTime < 60_000)
        assertTrue(events.none { it is BenchProgress.Finished })
        assertTrue(fake.asr.sessions.last().cancelled) // the abandoned session is cancelled, not left decoding
        assertNull(fake.asr.loaded)
        assertEquals(HostState.Down, fake.asrHost.state.value)
    }

    @Test
    fun closingTheSheetCancelsTheCollectorAndStillShutsTheProcessDown() = runTest {
        val entry = BenchRig.GIGAAM
        val fake = engines(BenchRig.perfectScripts(entry))
        fake.asr.finishDelayMs = 60_000
        val rig = BenchRig(this, fake)
        val job = launch { rig.runner.run(entry, rig.candidates(entry)).collect() }
        advanceTimeBy(5_000)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(fake.asr.loaded)
        assertEquals(HostState.Down, fake.asrHost.state.value)
        assertTrue(rig.saved(entry).isEmpty())
    }

    @Test
    fun everyUnitStartsColdWithAShutdownBeforeItsLoad() = runTest {
        val entry = BenchRig.PARAKEET
        val fake = engines(BenchRig.perfectScripts(entry) + BenchRig.perfectScripts(entry))
        val log = mutableListOf<String>()
        val engines = LoggingEngines(fake, log)
        val rig = BenchRig(this, engines)
        val events = rig.runner.run(entry, rig.candidates(entry, FakeDeviceProfileRepository.FOLD7)).toList()

        val records = (events.last() as BenchProgress.Finished).records
        assertEquals(listOf(ComputeUnit.CPU, ComputeUnit.NPU), records.map { it.unit }) // CPU first (WER reference)
        // Per unit: shutdown -> connect -> cold load -> unload -> warm load, all before any session.
        val firstLoads = listOf("load:CPU", "load:NPU").map { log.indexOf(it) }
        for (i in firstLoads) assertEquals(listOf("shutdown", "connect", "memory"), log.subList(i - 3, i))
        assertEquals(2, log.count { it == "connect" })
        assertTrue(log.indexOf("load:NPU") > log.lastIndexOf("session:CPU"))
        assertEquals("shutdown", log.last()) // the run leaves no process behind
    }

    @Test
    fun garbageTranscriptsGiveAnInvalidRecordThatNeverScores() = runTest {
        val entry = BenchRig.PARAKEET
        val fake = FakeEngines().also { it.asr.script = "привет это тестовая диктовка" }
        val rig = BenchRig(this, fake)
        val artifact = entry.artifacts.single { it.units == listOf(ComputeUnit.CPU) }.id
        rig.health.markFailed(artifact, ComputeUnit.CPU, "engine.load.asr")
        val events = rig.run(entry)

        val record = (events.last() as BenchProgress.Finished).records.single()
        assertFalse(record.result.valid)
        assertEquals("wer_high", record.result.invalidReason)
        assertEquals(record, rig.saved(entry).single()) // stored and shown ("Results look wrong")
        assertNull(BenchScores.lookup(rig.saved(entry)).score(artifact, ComputeUnit.CPU))
        assertTrue(rig.health.failed(artifact, ComputeUnit.CPU)) // an invalid run does not clear the failure
    }

    @Test
    fun aValidRecordClearsTheFailedUnit() = runTest {
        val entry = BenchRig.GIGAAM
        val rig = BenchRig(this, engines(BenchRig.perfectScripts(entry)))
        val artifact = entry.artifacts.single().id
        rig.health.markFailed(artifact, ComputeUnit.CPU, "engine.load.asr")
        val candidates = rig.candidates(entry)
        assertEquals(app.murmur.core.catalog.Blocker.UNIT_FAILED, candidates.single().blocker) // still benchmarked
        val events = rig.runner.run(entry, candidates).toList()
        assertTrue((events.last() as BenchProgress.Finished).records.single().result.valid)
        assertFalse(rig.health.failed(artifact, ComputeUnit.CPU))
    }

    @Test
    fun medianWerToleratesOneBadClip() = runTest {
        val entry = BenchRig.PARAKEET
        val scripts = BenchRig.perfectScripts(entry).toMutableList()
        scripts[3] = "совсем не то" // measured English pass is garbage, Russian and mixed are right
        val rig = BenchRig(this, engines(scripts))
        val asr = (rig.run(entry).last() as BenchProgress.Finished).records.single().result as BenchmarkResultData.Asr
        assertEquals(1.0, asr.perClip.single { it.clip == "en" }.wer!!, 1e-9)
        assertTrue(asr.valid)
    }

    @Test
    fun acceleratorIsJudgedAgainstTheCpuRecordOfTheSameRun() = runTest {
        val entry = BenchRig.PARAKEET
        // CPU is perfect; the NPU gets about 10 % of the words wrong in every clip: under the 20 % ceiling but above
        // CPU (0 %) + 5 pp -> wer_above_cpu.
        val cpu = BenchRig.perfectScripts(entry)
        val npuBad = BenchRig.INPUTS.clips.filter { it.runsOn(entry.languages.codes) }.flatMap { c ->
            val words = c.reference.split(' ').toMutableList()
            val wrong = (words.size + 9) / 10
            for (k in 0 until wrong) words[k] = "ошибка"
            listOf(c.reference, words.joinToString(" "))
        }
        val rig = BenchRig(this, engines(cpu + npuBad))
        val records = (rig.runner.run(entry, rig.candidates(entry, FakeDeviceProfileRepository.FOLD7)).toList().last() as BenchProgress.Finished).records
        val (cpuRec, npuRec) = records
        assertTrue(cpuRec.result.valid)
        assertFalse(npuRec.result.valid)
        assertEquals("wer_above_cpu", npuRec.result.invalidReason)
        val lookup = BenchScores.lookup(rig.saved(entry))
        assertNotNull(lookup.score(cpuRec.artifactId, ComputeUnit.CPU))
        assertNull(lookup.score(npuRec.artifactId, ComputeUnit.NPU))
    }

    @Test
    fun aUnitThatFailsToLoadIsMarkedAndTheRunGoesOn() = runTest {
        val entry = BenchRig.PARAKEET
        val fake = engines(BenchRig.perfectScripts(entry))
        fake.asr.loadErrors[ComputeUnit.NPU] = MurmurError.EngineLoadFailed(app.murmur.core.model.ModelKind.ASR, "qnn")
        val rig = BenchRig(this, fake)
        val events = rig.runner.run(entry, rig.candidates(entry, FakeDeviceProfileRepository.FOLD7)).toList()
        val failed = events.filterIsInstance<BenchProgress.UnitFailed>().single()
        assertEquals(ComputeUnit.NPU, failed.unit)
        assertEquals("engine.load.asr", failed.error.code)
        assertTrue(rig.health.failed(failed.artifact, ComputeUnit.NPU))
        assertEquals(listOf(ComputeUnit.CPU), (events.last() as BenchProgress.Finished).records.map { it.unit })
        assertEquals(HostState.Down, fake.asrHost.state.value)
    }

    /** ADR-066 / ADR-083: running out of memory says nothing about the unit, in the benchmark as in a dictation. */
    @Test
    fun anOutOfMemoryOrALowMemoryKillDuringTheLoadIsNotMarked() = runTest {
        val entry = BenchRig.PARAKEET
        val oom = engines(BenchRig.perfectScripts(entry))
        oom.asr.loadErrors[ComputeUnit.NPU] = MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.ASR)
        val rigOom = BenchRig(this, oom)
        val failedOom = rigOom.runner.run(entry, rigOom.candidates(entry, FakeDeviceProfileRepository.FOLD7)).toList()
            .filterIsInstance<BenchProgress.UnitFailed>().single()
        assertEquals("engine.oom.asr", failedOom.error.code)
        assertFalse(rigOom.health.failed(failedOom.artifact, ComputeUnit.NPU))

        // A death during the load: lmkd (exit reason low memory) -> OutOfMemory, no mark; any other death -> marked.
        for (lowMemory in listOf(true, false)) {
            val died = engines(BenchRig.perfectScripts(entry))
            died.asr.loadErrors[ComputeUnit.NPU] = MurmurError.ProcessDied(InferProcess.ASR)
            val rig = BenchRig(this, died, exits = app.murmur.testing.FakeProcessExits(lowMemory))
            val failed = rig.runner.run(entry, rig.candidates(entry, FakeDeviceProfileRepository.FOLD7)).toList()
                .filterIsInstance<BenchProgress.UnitFailed>().single()
            assertEquals(if (lowMemory) "engine.oom.asr" else "engine.process_died.asr", failed.error.code)
            assertEquals(!lowMemory, rig.health.failed(failed.artifact, ComputeUnit.NPU))
            assertTrue(rig.exits.asked.isNotEmpty() && rig.exits.asked.all { it > 0 })
        }
    }

    @Test
    fun nothingToMeasureIsAFailureNotAnEmptyResult() = runTest {
        val rig = BenchRig(this)
        val npuOnly = BenchRig.model("nemotron-speech-streaming-en-0.6b") // NPU artifact only; the emulator has no NPU
        val events = rig.run(npuOnly)
        assertEquals("internal", (events.single() as BenchProgress.Failed).error.code)
        val failedEverywhere = BenchRig(this, FakeEngines().also { it.asr.loadError = MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.ASR) })
        val last = failedEverywhere.run(BenchRig.GIGAAM).last()
        assertEquals("engine.oom.asr", (last as BenchProgress.Failed).error.code)
    }
    @Test
    fun anEngineThatThrowsFailsTheUnitNotTheRun() = runTest {
        val fake = engines(BenchRig.perfectScripts(BenchRig.GIGAAM))
        val throwing = object : Engines by fake {
            override val asr: AsrEngine = object : AsrEngine by fake.asr {
                override suspend fun startSession(options: AsrSessionOptions): AsrSession = throw IllegalStateException("binder died")
            }
        }
        val rig = BenchRig(this, throwing)
        val events = rig.run(BenchRig.GIGAAM)
        val failed = events.filterIsInstance<BenchProgress.UnitFailed>().single()
        assertEquals("internal", failed.error.code)
        assertEquals("bench.exception:IllegalStateException", failed.error.detail)
        assertEquals(failed.error, (events.last() as BenchProgress.Failed).error)
        assertNull(fake.asr.loaded)
        assertEquals(HostState.Down, fake.asrHost.state.value)
        assertFalse(rig.health.failed(failed.artifact, ComputeUnit.CPU)) // not a load failure: the unit stays usable
    }
}

/** Records the order of host and engine calls (cold-start protocol test). */
private class LoggingEngines(private val fake: FakeEngines, private val log: MutableList<String>) : Engines by fake {
    override val asrHost: InferenceHost = object : InferenceHost by fake.asrHost {
        override suspend fun connect(): Boolean { log += "connect"; return fake.asrHost.connect() }
        override suspend fun shutdown() { log += "shutdown"; fake.asrHost.shutdown() }
        override suspend fun memory(): ProcessMemory { log += "memory"; return fake.asrHost.memory() }
    }
    override val asr: AsrEngine = object : AsrEngine {
        override suspend fun load(asr: LoadSpec, vad: LoadSpec?): Outcome<LoadInfo> { log += "load:${asr.unit}"; return fake.asr.load(asr, vad) }
        override suspend fun startSession(options: AsrSessionOptions): AsrSession {
            log += "session:${fake.asr.loaded?.unit}"; return fake.asr.startSession(options)
        }
        override suspend fun unload() { log += "unload"; fake.asr.unload() }
    }
}
