package app.murmur.data.models

import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit.CPU
import app.murmur.core.model.ComputeUnit.GPU
import app.murmur.core.model.ComputeUnit.NPU
import app.murmur.core.model.InferProcess
import app.murmur.testing.FakeInferenceHost
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aCpuOnlyFallbackIsProbedAgainOnceTheRetryIsDue() = runBlocking {
        // ADR-092: a probe that failed at :main start (boot, a slow first QNN EP registration) must not keep a
        // long-lived :main on CPU only; the first ensure() after retryAfterMs probes again, nothing earlier.
        var now = 1_000L
        val asr = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU)).also { it.crashOnConnect = 1 }
        val llm = FakeInferenceHost(InferProcess.LLM)
        val prober = DeviceProfileProber(cache, "1.0+1", asr, llm, base, retryAfterMs = 60_000, clock = { now })
        assertEquals(setOf(CPU), prober.ensure().availableUnits)
        val connects = asr.connects
        now += 59_999
        assertEquals(setOf(CPU), prober.ensure().availableUnits)
        assertEquals("no probe before the retry is due", connects, asr.connects)
        assertFalse(cache.exists())
        now += 1
        assertEquals(setOf(NPU, CPU), prober.ensure().availableUnits)
        assertEquals(connects + 1, asr.connects)
        assertTrue(cache.isFile)
        // A probed profile is final for the process: no more probes, however much time passes.
        now += 10 * 60_000
        assertEquals(setOf(NPU, CPU), prober.ensure().availableUnits)
        assertEquals(connects + 1, asr.connects)
    }

    @Test
    fun aRetryThatFailsAgainWaitsAnotherFullInterval() = runBlocking {
        var now = 0L
        val asr = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU)).also { it.crashOnConnect = 2 }
        val prober = DeviceProfileProber(cache, "1.0+1", asr, FakeInferenceHost(InferProcess.LLM), base, retryAfterMs = 1_000, clock = { now })
        assertEquals(setOf(CPU), prober.ensure().availableUnits)
        now = 1_000
        assertEquals(setOf(CPU), prober.ensure().availableUnits)   // retry 1 fails
        now = 1_999
        assertEquals(setOf(CPU), prober.ensure().availableUnits)   // not due: counted from the failed retry
        now = 2_000
        assertEquals(setOf(NPU, CPU), prober.ensure().availableUnits)
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
