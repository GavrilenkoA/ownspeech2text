package app.murmur.core.dictation

import app.murmur.core.engine.HostState
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.PromptOverride
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EnginePreparer (ADR-050, ARCHITECTURE 4.2 / 8.5). */
class EnginePreparerTest {

    @Test fun npuAsrArtifactThatFailsReportsNeedsCpuArtifact() = runTest {
        val h = harness {
            models.npuFirst = true; models.cpuFallback = false
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.EngineLoadFailed(app.murmur.core.model.ModelKind.ASR, "qnn")
        }
        val progress = mutableListOf<Float>()
        val r = h.o.prepareArtifact(h.w.models.asrNpu.artifactId) { progress += it } as Outcome.Ok
        assertEquals(h.w.models.asrCpu.artifactId, r.value.needsCpuArtifact)
        assertEquals(setOf(ComputeUnit.NPU), r.value.failed.keys)
        assertTrue(r.value.proven.isEmpty())
        assertEquals("engine.load.asr", h.w.unitHealth.failedUnits[h.w.models.asrNpu.artifactId to ComputeUnit.NPU])
        assertEquals(1f, progress.last())
        h.assertNoInternalErrors()
    }

    @Test fun cpuAsrArtifactIsProvenByDecodingTheBundledClip() = runTest {
        val h = harness()
        val r = h.o.prepareArtifact(h.w.models.asrCpu.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), r.value.proven)
        assertNull(r.value.needsCpuArtifact)
        assertTrue((h.w.models.asrCpu.artifactId to ComputeUnit.CPU) in h.w.unitHealth.provenUnits)
        assertEquals(16_000L, h.w.engines.asr.sessions.single().samples)    // 1 s clip, 16 kHz
        assertEquals(PrepareClip.samples.size, 16_000)
        h.assertNoInternalErrors()
    }

    @Test fun llmArtifactBuildsTheSnapshotOnEveryUsableUnit() = runTest {
        val h = harness { models.npuFirst = true; engines.llm.loadErrors[ComputeUnit.NPU] = MurmurError.UnitUnavailable(ComputeUnit.NPU) }
        val r = h.o.prepareArtifact(h.w.models.llmCpu.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), r.value.proven)
        assertEquals(setOf(ComputeUnit.NPU), r.value.failed.keys)
        assertEquals(1, h.w.engines.llm.prefixCalls.size)                    // snapshot built on the unit that loaded
        assertNull(r.value.needsCpuArtifact)
        h.assertNoInternalErrors()
    }

    /** Review F6: preparing an LLM obeys the memory gate (the warm :asr goes first); without the room nothing is marked. */
    @Test fun llmPreparationShutsAWarmAsrDownOrSkipsTheUnitWithoutTheMemory() = runTest {
        val h = harness { settings.edit { it.copy(cleanup = CleanupChoice.None) } }
        h.dictate()                                                          // :asr warm
        h.w.memory.source = { if (h.w.engines.asrHost.state.value is HostState.Up) 1_000 else 6_000 }
        val ok = h.o.prepareArtifact(h.w.models.llmCpu.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), ok.value.proven)
        assertEquals(1, h.w.engines.asrHost.shutdowns)
        h.w.engines.llmHost.shutdown()
        h.w.memory.source = null
        h.w.memory.mb = 1_000
        val attempts = h.w.engines.llm.loadAttempts.size
        val short = h.o.prepareArtifact(h.w.models.llmCpu.artifactId) {} as Outcome.Ok
        assertTrue(short.value.proven.isEmpty() && short.value.failed.isEmpty())
        assertEquals(attempts, h.w.engines.llm.loadAttempts.size)
        assertTrue(h.w.unitHealth.failedUnits.isEmpty())
        h.assertNoInternalErrors()
    }

    /** ADR-066 in Preparing: an OutOfMemory says nothing about the unit (no mark, no CPU artifact queued). */
    @Test fun anOutOfMemoryWhilePreparingNeitherMarksTheUnitNorQueuesTheCpuArtifact() = runTest {
        val h = harness {
            models.npuFirst = true; models.cpuFallback = false
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.ASR)
            engines.llm.loadErrors[ComputeUnit.NPU] = MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.LLM)
        }
        val asr = h.o.prepareArtifact(h.w.models.asrNpu.artifactId) {} as Outcome.Ok
        assertTrue(asr.value.proven.isEmpty() && asr.value.failed.isEmpty())
        assertEquals(setOf(ComputeUnit.NPU), asr.value.untested.keys)
        assertNull(asr.value.needsCpuArtifact)
        val llm = h.o.prepareArtifact(h.w.models.llmCpu.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), llm.value.proven)
        assertTrue(llm.value.failed.isEmpty())
        assertEquals(MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.LLM), llm.value.untested[ComputeUnit.NPU])
        assertTrue(h.w.unitHealth.failedUnits.isEmpty())
        h.assertNoInternalErrors()
    }

    /** ADR-083 in Preparing: a low-memory kill during the load is OutOfMemory (untested, no mark, no CPU artifact queued). */
    @Test fun aLowMemoryKillWhilePreparingIsOutOfMemory() = runTest {
        val h = harness {
            models.npuFirst = true; models.cpuFallback = false
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.ProcessDied(app.murmur.core.model.InferProcess.ASR)
            exits.lowMemory = true
        }
        val asr = h.o.prepareArtifact(h.w.models.asrNpu.artifactId) {} as Outcome.Ok
        assertEquals(MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.ASR), asr.value.untested[ComputeUnit.NPU])
        assertTrue(asr.value.failed.isEmpty())
        assertNull(asr.value.needsCpuArtifact)
        assertTrue(h.w.unitHealth.failedUnits.isEmpty())
        h.assertNoInternalErrors()
    }

    /** No process, a prefix error after a good load and a proof-decode timeout leave UnitHealth unchanged too. */
    @Test fun noProcessAPrefixErrorOrADecodeTimeoutLeaveUnitHealthUnchanged() = runTest {
        val h = harness()
        h.w.engines.asrHost.crashOnConnect = 2
        val unbound = h.o.prepareArtifact(h.w.models.asrCpu.artifactId) {} as Outcome.Ok
        assertEquals(MurmurError.ProcessDied(app.murmur.core.model.InferProcess.ASR), unbound.value.untested[ComputeUnit.CPU])
        h.w.engines.asr.finishDelayMs = 600_000
        val slow = h.o.prepareArtifact(h.w.models.asrCpu.artifactId) {} as Outcome.Ok
        assertEquals(MurmurError.Timeout(app.murmur.core.model.Stage.ASR_FINAL), slow.value.untested[ComputeUnit.CPU])
        h.w.engines.llm.prefixError = MurmurError.Timeout(app.murmur.core.model.Stage.LLM_PREFIX)
        val prefix = h.o.prepareArtifact(h.w.models.llmCpu.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), prefix.value.untested.keys)
        for (r in listOf(unbound, slow, prefix)) assertTrue(r.value.proven.isEmpty() && r.value.failed.isEmpty())
        assertTrue(h.w.unitHealth.failedUnits.isEmpty() && h.w.unitHealth.provenUnits.isEmpty())
        // A decode error that is not a timeout is the unit's (a runtime rejection the load did not catch).
        h.w.engines.asr.finishDelayMs = 0
        h.w.engines.asr.finishError = MurmurError.AsrFailed("qnn op")
        val rejected = h.o.prepareArtifact(h.w.models.asrCpu.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), rejected.value.failed.keys)
        assertEquals(1, h.w.unitHealth.failedUnits.size)
    }

    @Test fun vadIsProvenWithItsAsrTheOcrPairLoadsAndUnknownArtifactsAreRefused() = runTest {
        val h = harness()
        // The OCR detector alone has nothing to pair with yet; the VAD is proven with the ASR that uses it.
        val vad = h.o.prepareArtifact(h.w.models.vad.artifactId) {} as Outcome.Ok
        assertTrue(vad.value.proven.isEmpty() && vad.value.failed.isEmpty())
        val ocr = h.o.prepareArtifact(h.w.models.ocrDet.artifactId) {} as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), ocr.value.proven)
        assertEquals(Outcome.Err(MurmurError.CatalogInvalid("unknown artifact")), h.o.prepareArtifact(app.murmur.core.model.ArtifactId("nope@x")) {})
    }

    @Test fun aPromptEditRebuildsTheSnapshotOnceAfterFiveSecondsOfQuiet() = runTest {
        val h = harness()
        h.at(0)
        h.w.settings.edit { it.copy(promptOverride = PromptOverride("Пиши коротко.", baseProfile = "default.v4")) }
        h.at(2_000)
        h.w.settings.edit { it.copy(preferences = "Без эмодзи") }
        h.at(6_999)
        assertTrue(h.w.engines.llm.prefixCalls.isEmpty())
        h.at(7_000)
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(1, h.w.engines.llm.prefixCalls.size)
        val prefix = h.w.engines.llm.prefixCalls.single().text
        assertTrue(prefix.contains("Пиши коротко.") && prefix.contains("Без эмодзи"))
        // The next dictation restores it (same key): no second prefill.
        h.dictate(startAt = h.now + 1_000, stopAt = h.now + 3_800)
        assertEquals(1, h.w.engines.llm.prefixCalls.size)
        assertEquals("default.v4+edited", h.only().cleanup?.promptVersion)
        h.assertNoInternalErrors()
    }

    @Test fun aPressDuringPreparationCancelsItAndTheDictationProceeds() = runTest {
        val h = harness { engines.asr.loadDelayMs = 2_000 }
        var preparedAt = -1L
        val prep = async {
            (h.o.prepareArtifact(h.w.models.asrCpu.artifactId) {}).also { preparedAt = h.now }
        }
        h.at(500)
        assertEquals(1, h.w.engines.asr.loadAttempts.size)                  // preparation is loading
        h.tap()
        h.at(600)
        assertEquals(2, h.w.engines.asr.loadAttempts.size)                  // the dictation's own load started at once
        h.at(4_000); h.tap()
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(EntryStatus.INSERTED, h.only().status)
        val r = prep.await() as Outcome.Ok
        assertEquals(setOf(ComputeUnit.CPU), r.value.proven)
        val insertedAt = h.states.indexOfFirst { it is DictationState.Done }
        assertTrue(insertedAt >= 0)
        assertTrue("prepared at $preparedAt", preparedAt >= 4_000)          // resumed after the dictation
        h.assertNoInternalErrors()
    }
}
