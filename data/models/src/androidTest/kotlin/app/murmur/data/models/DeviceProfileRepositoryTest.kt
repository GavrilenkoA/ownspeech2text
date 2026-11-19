package app.murmur.data.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.HostState
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DefaultDeviceProfileRepository on the x86_64 emulator: SoC, ABI and RAM from the platform, the cache file in
 * noBackupFilesDir per app version, CPU only when the probe fails. The inference processes are stand-ins (the real
 * binder hosts are WP-05's; the emulator profile is soc null, units {CPU}: ARCHITECTURE 8.4).
 */
@RunWith(AndroidJUnit4::class)
class DeviceProfileRepositoryTest {
    private val context = TestModelsApp.app
    private val cache = File(context.noBackupFilesDir, "device-profile.json")

    private class Host(override val process: InferProcess, val fail: Boolean = false) : InferenceHost {
        var idleExit: Long? = null
        override val state: StateFlow<HostState> = MutableStateFlow(HostState.Down)
        override suspend fun connect() = !fail
        override suspend fun probe() = ProcessProbe(process, RuntimeKeys.implemented, setOf(ComputeUnit.CPU), mapOf(ProcessProbe.FACT_NATIVE_BUILD to "${process.name.lowercase()}-test"))
        override suspend fun memory() = ProcessMemory(0, 0, 0, 0)
        override fun scheduleIdleExit(ms: Long) { idleExit = ms }
        override suspend fun shutdown() = Unit
    }

    @Test
    fun emulatorProfileIsCpuOnlyWithPlatformFactsAndIsCached(): Unit = runBlocking {
        cache.delete()
        val asr = Host(InferProcess.ASR)
        val repo = DefaultDeviceProfileRepository(context, asr, Host(InferProcess.LLM), appVersion = "test+1")
        val p = repo.ensure()
        assertNull("emulator has no SoC model", p.soc)
        assertEquals("x86_64", p.abi)
        assertTrue(p.totalRamMb > 1_000)
        assertEquals(setOf(ComputeUnit.CPU), p.availableUnits)
        assertEquals(mapOf("asr" to "asr-test", "llm" to "llm-test"), p.nativeBuilds)
        assertTrue(cache.isFile)
        assertEquals(DeviceProfileProber.IDLE_AFTER_PROBE_MS, asr.idleExit)
        assertEquals(p, repo.profile.value)
    }

    @Test
    fun failedBindGivesCpuOnlyAndNothingIsCached(): Unit = runBlocking {
        cache.delete()
        val p = DefaultDeviceProfileRepository(context, Host(InferProcess.ASR, fail = true), Host(InferProcess.LLM), appVersion = "test+2").ensure()
        assertEquals(setOf(ComputeUnit.CPU), p.availableUnits)
        assertEquals(RuntimeKeys.implemented, p.supportedRuntimes)
        assertTrue(!cache.exists())
    }
}
