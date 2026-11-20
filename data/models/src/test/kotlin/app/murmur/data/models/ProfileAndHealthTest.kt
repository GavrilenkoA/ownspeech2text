package app.murmur.data.models

import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit.CPU
import app.murmur.core.model.ComputeUnit.GPU
import app.murmur.core.model.ComputeUnit.NPU
import app.murmur.core.model.InferProcess
import app.murmur.testing.FakeInferenceHost
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileProberTest {
    private val dir = tempDir("profile")
    private val cache = File(dir, "device-profile.json")
    private val base = { BaseDeviceInfo("SM8750", "arm64-v8a", 11_500) }

    @Test
    fun mergesBothProbesCachesPerAppVersionAndSchedulesIdleExit() = runBlocking {
        val asr = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU), facts = mapOf(ProcessProbe.FACT_NATIVE_BUILD to "sherpa 2.0.1 ort 1.32"))
        val llm = FakeInferenceHost(InferProcess.LLM, units = setOf(NPU, GPU, CPU), facts = mapOf(ProcessProbe.FACT_NATIVE_BUILD to "llama.cpp b12520"))
        val p = DeviceProfileProber(cache, "1.0+1", asr, llm, base).ensure()
        assertEquals("SM8750", p.soc)
        assertEquals(setOf(NPU, GPU, CPU), p.availableUnits)
        assertEquals(RuntimeKeys.known, p.supportedRuntimes)
        assertEquals(mapOf("asr" to "sherpa 2.0.1 ort 1.32", "llm" to "llama.cpp b12520"), p.nativeBuilds)
        assertEquals(DeviceProfileProber.IDLE_AFTER_PROBE_MS, asr.idleExitMs)
        assertTrue(cache.isFile)
        // A second process of the same app version reads the cache without binding.
        val asr2 = FakeInferenceHost(InferProcess.ASR); val llm2 = FakeInferenceHost(InferProcess.LLM)
        assertEquals(p, DeviceProfileProber(cache, "1.0+1", asr2, llm2, base).ensure())
        assertEquals(0, asr2.connects)
        // A new app version probes again.
        val p3 = DeviceProfileProber(cache, "1.1+2", asr2, llm2, base).ensure()
        assertEquals(1, asr2.connects)
        assertEquals(setOf(CPU), p3.availableUnits)
    }

    @Test
    fun crashOnBindOrTimeoutGivesCpuOnlyNotCachedAndReprobeRecovers() = runBlocking {
        val asr = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU)).also { it.crashOnConnect = 1 }
        val llm = FakeInferenceHost(InferProcess.LLM)
        val prober = DeviceProfileProber(cache, "1.0+1", asr, llm, base)
        val p = prober.ensure()
        assertEquals(setOf(CPU), p.availableUnits)
        assertEquals(null, p.soc)
        assertEquals(RuntimeKeys.implemented, p.supportedRuntimes)
        assertFalse(cache.exists())
        assertEquals(p, prober.ensure())   // kept for this process
        assertEquals(setOf(NPU, CPU), prober.reprobe().availableUnits)
        assertTrue(cache.isFile)

        val hanging = object : app.murmur.core.engine.InferenceHost by FakeInferenceHost(InferProcess.LLM) {
            override suspend fun probe(): ProcessProbe { delay(60_000); error("unreachable") }
        }
        val slow = DeviceProfileProber(File(dir, "other.json"), "1.0+1", FakeInferenceHost(InferProcess.ASR), hanging, base, timeoutMs = 200)
        assertEquals(setOf(CPU), slow.ensure().availableUnits)
    }

    @Test
    fun aCpuOnlyFallbackIsRetriedOnItsOwnAndEnsureNeverProbesWhileAProfileIsHeld() = runTest {
        // ADR-092/093: a probe that failed at :main start (boot, a slow first QNN EP registration) must not keep a
        // long-lived :main on CPU only, and the retry never runs inside ensure() (a press resolves models with it).
        val asr = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU)).also { it.crashOnConnect = 1 }
        val llm = FakeInferenceHost(InferProcess.LLM)
        val prober = DeviceProfileProber(cache, "1.0+1", asr, llm, base, retryAfterMs = 60_000,
            retryScope = backgroundScope, retryContext = EmptyCoroutineContext)
        assertEquals(setOf(CPU), prober.ensure().availableUnits)
        val connects = asr.connects
        advanceTimeBy(59_999); runCurrent()
        assertEquals("no probe before the retry is due", connects, asr.connects)
        assertEquals(setOf(CPU), prober.ensure().availableUnits)
        assertEquals("ensure() returns the held fallback without probing", connects, asr.connects)
        assertFalse(cache.exists())
        advanceTimeBy(1); runCurrent()
        assertEquals(connects + 1, asr.connects)
        assertEquals(setOf(NPU, CPU), prober.profile.value!!.availableUnits)
        assertEquals(setOf(NPU, CPU), prober.ensure().availableUnits)
        assertTrue(cache.isFile)
        // A probed profile is final for the process: no more probes, however much time passes.
        assertNull(prober.pendingRetry)
        advanceTimeBy(10 * 60 * 60_000L); runCurrent()
        assertEquals(connects + 1, asr.connects)
    }

    @Test
    fun aFailedRetryDoublesTheWaitUpToTheMaximum() = runTest {
        val asr = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU)).also { it.crashOnConnect = 4 }
        val prober = DeviceProfileProber(cache, "1.0+1", asr, FakeInferenceHost(InferProcess.LLM), base,
            retryAfterMs = 1_000, retryMaxMs = 3_000, retryScope = backgroundScope, retryContext = EmptyCoroutineContext)
        assertEquals(setOf(CPU), prober.ensure().availableUnits)            // t = 0: fails
        val probedAt = mutableListOf<Long>()
        var seen = asr.connects
        repeat(4) {
            while (asr.connects == seen) { advanceTimeBy(1); runCurrent() }
            seen = asr.connects
            probedAt += currentTime
        }
        // 1 s, then 2 s, then 3 s (capped), then 3 s: the fourth retry succeeds.
        assertEquals(listOf(1_000L, 3_000L, 6_000L, 9_000L), probedAt)
        assertEquals(setOf(NPU, CPU), prober.profile.value!!.availableUnits)
        assertNull(prober.pendingRetry)
    }

    @Test
    fun aProbeCallThatTimedOutIsAFailedProbeNotAProfileWithoutItsRuntimes() = runTest {
        // BinderHost.probe() answers a PROBE timeout (2 s, e.g. a slow first QNN EP registration) with no runtimes and
        // FACT_ERROR. Merged and cached, that read as "this phone has no ASR runtime" until the next app version.
        val asr = object : InferenceHost by FakeInferenceHost(InferProcess.ASR) {
            override suspend fun probe() = ProcessProbe(InferProcess.ASR, emptySet(), emptySet(), mapOf(ProcessProbe.FACT_ERROR to "timeout"))
        }
        val prober = DeviceProfileProber(cache, "1.0+1", asr, FakeInferenceHost(InferProcess.LLM), base,
            retryScope = backgroundScope, retryContext = EmptyCoroutineContext)
        val p = prober.ensure()
        assertEquals(setOf(CPU), p.availableUnits)
        assertEquals(RuntimeKeys.implemented, p.supportedRuntimes)
        assertFalse(cache.exists())
        assertTrue("a retry is scheduled", prober.pendingRetry?.isActive == true)
    }

    @Test
    fun onlyAProcessTheProbeStartedGetsTheShortIdleExit() = runTest {
        // ADR-086/093: a process that is already up (a dictation, preparation, a warm keep) keeps its own idle timer.
        val asr = FakeInferenceHost(InferProcess.ASR).also { it.connect(); it.scheduleIdleExit(400_000) }
        val llm = FakeInferenceHost(InferProcess.LLM)
        val released = mutableListOf<InferProcess>()
        DeviceProfileProber(cache, "1.0+1", asr, llm, base, releaseAfterProbe = { released += it.process; it.scheduleIdleExit(5_000) }).ensure()
        assertEquals(listOf(InferProcess.LLM), released)
        assertEquals(400_000L, asr.idleExitMs)
        assertEquals(DeviceProfileProber.IDLE_AFTER_PROBE_MS, llm.idleExitMs)
    }
}

class FileUnitHealthRepositoryTest {
    private val storage = ModelStorage(tempDir("health"))
    private val a = ArtifactId("parakeet-tdt-0.6b-v3@qnn-sm8750-20s")

    @Test
    fun persistsAcrossInstancesAndResetsWithTheAppVersion() = runBlocking {
        val h = FileUnitHealthRepository(storage, "1.0+1")
        h.markFailed(a, NPU, "engine.load.asr")
        h.markProven(a, CPU)
        assertTrue(h.failed(a, NPU))
        assertEquals("engine.load.asr", h.failureCode(a, NPU))
        val again = FileUnitHealthRepository(storage, "1.0+1")
        assertTrue(again.failed(a, NPU) && again.proven(a, CPU) && !again.failed(a, CPU))
        val updated = FileUnitHealthRepository(storage, "1.1+2")
        assertFalse(updated.failed(a, NPU))
        assertFalse(updated.proven(a, CPU))
    }

    @Test
    fun benchmarkClearAndProvenLoadRemoveTheFailure() = runBlocking {
        val h = FileUnitHealthRepository(storage, "1")
        h.markFailed(a, NPU, "engine.load.asr")
        h.clear(a, NPU)
        assertFalse(FileUnitHealthRepository(storage, "1").failed(a, NPU))
        h.markFailed(a, GPU, "x")
        h.markProven(a, GPU)
        assertFalse(h.failed(a, GPU))
        assertTrue(h.proven(a, GPU))
        h.markFailed(a, GPU, "y")
        assertFalse(h.proven(a, GPU))
    }

    @Test
    fun unreadableFileStartsClean() = runBlocking {
        storage.root.mkdirs()
        storage.unitHealthFile.writeText("{not json")
        val h = FileUnitHealthRepository(storage, "1")
        assertFalse(h.failed(a, NPU))
        h.markFailed(a, NPU, "z")
        assertTrue(FileUnitHealthRepository(storage, "1").failed(a, NPU))
    }
}
