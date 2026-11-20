package app.murmur.data.models

import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.UserInput
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit.CPU
import app.murmur.core.model.ComputeUnit.NPU
import app.murmur.core.model.InferProcess
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakeInferenceHost
import app.murmur.testing.FakeWorld
import app.murmur.testing.VirtualClock
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-093 on the real pair: [DeviceProfileProber] wired to a [DictationOrchestrator] the way AppContainer wires them
 * (retries through `whenNoDictation`, the post-probe idle exit through `releaseAfterProbe`). The verification of pass 8
 * found that a retry due at a press ran inside the resolver and replaced the ADR-086 session hold with the 5 s probe
 * exit, so :llm unloaded while the user was still speaking.
 */
class DeviceProfileRetryInDictationTest {
    private val base = { BaseDeviceInfo("SM8750", "arm64-v8a", 11_500) }

    /** The orchestrator's own host, with a probe that can fail (a PROBE timeout's reply) and take virtual time. */
    private class ProbeHost(val inner: FakeInferenceHost) : InferenceHost by inner {
        var failProbes = 0
        var probeMs = 0L
        var probes = 0
        override suspend fun probe(): ProcessProbe {
            probes++
            if (probeMs > 0) delay(probeMs)
            if (failProbes > 0) {
                failProbes--
                return ProcessProbe(process, emptySet(), emptySet(), mapOf(ProcessProbe.FACT_ERROR to "timeout"))
            }
            return inner.probe()
        }
    }

    private class Rig(val test: TestScope, dir: File) {
        val scope = CoroutineScope(test.coroutineContext[ContinuationInterceptor]!! + SupervisorJob())
        val w = FakeWorld(
            clock = VirtualClock { test.testScheduler.currentTime },
            engines = FakeEngines(asrHost = FakeInferenceHost(InferProcess.ASR, units = setOf(NPU, CPU))),
        )
        val errors = mutableListOf<Throwable>()
        val o: DictationOrchestrator = w.orchestrator(scope, errors = errors)
        val asr = ProbeHost(w.engines.asrHost)
        val llm = ProbeHost(w.engines.llmHost)
        val prober = DeviceProfileProber(
            File(dir, "device-profile.json"), "1.0+1", asr, llm, { BaseDeviceInfo("SM8750", "arm64-v8a", 11_500) },
            retryAfterMs = 60_000, retryScope = scope, retryContext = EmptyCoroutineContext,
            runRetry = { o.whenNoDictation(it) },
            releaseAfterProbe = { o.releaseAfterProbe(it, DeviceProfileProber.IDLE_AFTER_PROBE_MS) },
        )

        fun at(ms: Long) { test.advanceTimeBy(ms - test.currentTime); test.runCurrent() }
        fun tap() { o.onInput(UserInput.Tap); test.runCurrent() }
        val running get() = o.state.value.let { it !is DictationState.Idle && it !is DictationState.Done }
        fun holds(): List<Long?> = listOf(w.engines.asrHost.idleExitMs, w.engines.llmHost.idleExitMs)
    }

    @Test
    fun aRetryDueDuringADictationWaitsForItsEndAndTheSessionHoldStands() = runTest {
        val r = Rig(this, tempDir("retry-in-dictation"))
        r.asr.failProbes = 1
        assertEquals(setOf(CPU), r.prober.ensure().availableUnits)        // boot probe at t = 0 fails
        r.at(50_000); r.tap()                                                 // press; the retry falls due at 60 s
        assertTrue(r.running)
        val hold = r.w.engines.llmHost.idleExitMs!!
        assertTrue("session hold $hold", hold > 300_000)
        r.at(65_000)
        assertTrue(r.running)
        assertEquals("no probe during the dictation", 1, r.asr.probes)
        assertEquals(listOf(hold, hold), r.holds())
        assertEquals(setOf(CPU), r.prober.ensure().availableUnits)
        r.at(68_000); r.tap()                                                 // stop
        advanceUntilIdle()
        assertTrue(!r.running)
        assertEquals("the retry ran after the dictation", 2, r.asr.probes)
        assertEquals(setOf(NPU, CPU), r.prober.profile.value!!.availableUnits)
        assertTrue(r.w.history.snapshot().isNotEmpty())
        assertTrue(r.errors.joinToString { it.stackTraceToString() }, r.errors.isEmpty())
        r.scope.cancel()
    }

    @Test
    fun aPressDuringARunningRetryPreemptsItAndItsProbeNeverShortensTheHold() = runTest {
        val r = Rig(this, tempDir("retry-preempted"))
        r.asr.failProbes = 1
        assertEquals(setOf(CPU), r.prober.ensure().availableUnits)
        r.w.engines.asrHost.shutdown()                                        // :asr exited after the boot probe
        r.asr.probeMs = 5_000                                                 // the retry probe takes 5 s from t = 60 s
        r.at(62_000)
        assertEquals("the retry is probing", 2, r.asr.probes)
        val before = r.w.engines.asrHost.idleExitHistory.size
        r.tap()                                                               // press at 62 s cancels it (ARCH 5.4)
        assertTrue(r.running)
        val hold = r.w.engines.asrHost.idleExitMs!!
        assertTrue("session hold $hold", hold > 300_000)
        r.at(66_000)
        // The cancelled probe released the :asr it started: through the orchestrator, a no-op in a dictation.
        assertEquals(listOf(hold, hold), r.holds())
        assertEquals(listOf(hold), r.w.engines.asrHost.idleExitHistory.drop(before))
        assertEquals(setOf(CPU), r.prober.profile.value!!.availableUnits)
        r.at(70_000); r.tap()
        advanceUntilIdle()
        assertEquals("the preempted retry ran again after the dictation", 3, r.asr.probes)
        assertEquals(setOf(NPU, CPU), r.prober.profile.value!!.availableUnits)
        assertTrue(r.errors.joinToString { it.stackTraceToString() }, r.errors.isEmpty())
        r.scope.cancel()
    }
}
