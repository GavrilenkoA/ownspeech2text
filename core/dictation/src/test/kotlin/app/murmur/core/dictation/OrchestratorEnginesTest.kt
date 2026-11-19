package app.murmur.core.dictation

import app.murmur.core.engine.HostState
import app.murmur.core.engine.LlmStats
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.EntryStatus
import app.murmur.testing.syntheticSpeech
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Load fallbacks, process deaths, LLM timing and memory (ARCHITECTURE 4.4, 5.3, 6.5, 7). */
class OrchestratorEnginesTest {
    private val raw = "привет это тестовая диктовка"

    @Test fun asrNpuLoadFailureFallsBackToCpuReplaysTheBufferAndMarksUnitHealth() = runTest {
        val h = harness {
            models.npuFirst = true
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.UnitUnavailable(ComputeUnit.NPU)
            engines.asr.loadDelayMs = 500
        }
        h.dictate(stopAt = 2_800)
        assertEquals(listOf(ComputeUnit.NPU, ComputeUnit.CPU), h.w.engines.asr.loadAttempts)
        assertEquals("engine.unit_unavailable", h.w.unitHealth.failedUnits[h.w.models.asrNpu.artifactId to ComputeUnit.NPU])
        // The CPU session started ~1 s in and still saw every sample from 0 (replay), then the live stream.
        val session = h.w.engines.asr.sessions.single()
        assertEquals(h.w.audio.current!!.emittedSamples, session.samples)
        assertEquals(2_800L * 16, session.samples)
        assertTrue(h.states.any { it is DictationState.Listening && (it.engines.asr as? AsrReadiness.Ready)?.fellBack == true })
        assertEquals(ComputeUnit.CPU, h.only().asr?.unit)
        h.assertInserted("accept")
    }

    @Test fun asrNpuFailureWithoutFallbackIsUnitUnavailableWithOpenModelsAtTheStop() = runTest {
        val h = harness {
            models.npuFirst = true; models.cpuFallback = false
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.EngineLoadFailed(ModelKind.ASR, "qnn")
        }
        h.at(0); h.tap(); h.at(1_000)
        assertTrue(h.state is DictationState.Listening)                     // keeps recording
        assertEquals(AsrReadiness.Failed(MurmurError.UnitUnavailable(ComputeUnit.NPU)), (h.state as DictationState.Listening).engines.asr)
        h.at(2_800); h.tap(); h.at(3_000)
        assertEquals(DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.OPEN_MODELS), h.o.bubble.value)
        val e = h.only()
        assertEquals(EntryStatus.FAILED, e.status)
        assertEquals("engine.unit_unavailable", e.failureCode)
        assertNotNull(e.audio)                                               // "Try again" later
        h.o.onInput(UserInput.ResultAction); h.at(3_100)
        assertEquals(listOf(OpenTarget.Models(ModelKind.ASR)), h.w.navigator.opened)
        h.assertNoInternalErrors()
    }

    @Test fun llmNpuLoadFailureGeneratesOnCpuAndTheNextDictationGoesStraightToCpu() = runTest {
        val h = harness {
            models.npuFirst = true
            engines.llm.loadErrors[ComputeUnit.NPU] = MurmurError.EngineLoadFailed(ModelKind.LLM, "htp")
        }
        h.dictate(stopAt = 2_800)
        assertEquals(listOf(ComputeUnit.NPU, ComputeUnit.CPU), h.w.engines.llm.loadAttempts)
        assertEquals("engine.load.llm", h.w.unitHealth.failedUnits[h.w.models.llmNpu.artifactId to ComputeUnit.NPU])
        assertEquals("accept", h.only().cleanup?.outcome)
        assertEquals(ComputeUnit.CPU, h.only().cleanup?.model?.unit)
        // Second dictation: the selector skips the failed unit and the warm CPU model is reused (no NPU attempt, no load).
        h.dictate(startAt = h.now + 1_000, stopAt = h.now + 3_800)
        assertEquals(listOf(ComputeUnit.NPU, ComputeUnit.CPU), h.w.engines.llm.loadAttempts)
        assertEquals(2, h.history().size)
        assertTrue(h.history().all { it.cleanup?.outcome == "accept" && it.cleanup?.model?.unit == ComputeUnit.CPU })
        h.assertNoInternalErrors()
    }

    @Test fun asrCrashWhileListeningRebindsReplaysAndInserts() = runTest {
        val h = harness()
        h.at(0); h.tap(); h.at(1_000)
        h.w.engines.asrHost.crash()
        h.at(1_100)
        assertEquals(2, h.w.engines.asr.sessions.size)
        h.at(2_800); h.tap(); h.scope.testScheduler.advanceUntilIdle()
        assertEquals(2, h.w.engines.asrHost.connects)
        assertEquals(2_800L * 16, h.w.engines.asr.sessions.last().samples)   // replayed from sample 0
        h.assertInserted("accept")
    }

    @Test fun secondAsrCrashWhileListeningFailsAtTheStopWithAudio() = runTest {
        val h = harness()
        h.at(0); h.tap(); h.at(1_000)
        h.w.engines.asrHost.crash()
        h.at(2_000)
        h.w.engines.asrHost.crash()
        h.at(2_800); h.tap(); h.scope.testScheduler.advanceUntilIdle()
        val e = h.only()
        assertEquals(EntryStatus.FAILED, e.status)
        assertEquals("engine.process_died.asr", e.failureCode)
        assertNotNull(e.audio)
        assertEquals(DictationOutcome.Failed(MurmurError.ProcessDied(InferProcess.ASR), e.id, audioKept = true), h.outcomes().single())
        h.assertNoInternalErrors()
    }

    @Test fun firstAsrCrashInFinishingReplaysAndFinishes() = runTest {
        val h = harness { engines.asr.finishDelayMs = 1_000 }
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(3_000)
        assertTrue(h.state is DictationState.Finishing)
        h.w.engines.asrHost.crash()
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(2, h.w.engines.asr.sessions.size)
        assertEquals(2_800L * 16, h.w.engines.asr.sessions.last().samples)
        assertEquals(1, h.w.engines.asrHost.crashes)
        h.assertInserted("accept")
    }

    /** Review F1: the failing finish() reports the death before the host's Crashed notice, which lands in the restart. */
    @Test fun firstAsrCrashInFinishingSeenByFinishBeforeTheHostNoticeIsCountedOnce() = runTest {
        val h = harness {
            engines.asr.finishDelayMs = 1_000
            engines.asrHost.shutdownDelayMs = 100
        }
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(3_000)
        h.w.engines.asrHost.crash(publish = false)       // finish() returns ProcessDied(ASR) now
        h.at(3_060)
        val st = h.state as DictationState.Finishing
        assertEquals(1, st.engines.asrCrashes)
        h.w.engines.asrHost.publishCrash()               // binderDied for the same process, inside the restart's shutdown
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(2, h.w.engines.asr.sessions.size)
        assertEquals(2_800L * 16, h.w.engines.asr.sessions.last().samples)
        assertTrue(h.outcomes().single() is DictationOutcome.Inserted)
        h.assertInserted("accept")
    }

    /** Deaths are told apart per process instance: a death of the re-bound :asr still counts as the second (row 22). */
    @Test fun aDeathOfTheReboundAsrIsTheSecondDeath() = runTest {
        val h = harness { engines.asrHost.shutdownDelayMs = 100 }
        h.at(0); h.tap(); h.at(1_000)
        h.w.engines.asrHost.crash()
        h.at(1_500)
        assertEquals(1, (h.state as DictationState.Listening).engines.asrCrashes)
        assertTrue((h.state as DictationState.Listening).engines.asr is AsrReadiness.Ready)
        h.w.engines.asrHost.crash()
        h.at(1_600)
        assertEquals(AsrReadiness.Failed(MurmurError.ProcessDied(InferProcess.ASR)), (h.state as DictationState.Listening).engines.asr)
        h.at(2_800); h.tap()
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals("engine.process_died.asr", h.only().failureCode)
        h.assertNoInternalErrors()
    }

    /** Finishing: the restarted session's own death (finish() first, its notice later) fails at row 30. */
    @Test fun aDeathOfTheRestartedAsrInFinishingFailsWithAudio() = runTest {
        val h = harness {
            engines.asr.finishDelayMs = 1_000
            engines.asrHost.shutdownDelayMs = 100
        }
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(3_000)
        h.w.engines.asrHost.crash(publish = false)
        h.at(3_060)
        h.w.engines.asrHost.publishCrash()                // late notice of the first death, inside the restart: ignored
        h.at(3_200)
        assertEquals(2, h.w.engines.asr.sessions.size)
        assertTrue(h.state is DictationState.Finishing)
        h.w.engines.asrHost.crash()                       // the restarted process dies
        h.scope.testScheduler.advanceUntilIdle()
        val e = h.only()
        assertEquals("engine.process_died.asr", e.failureCode)
        assertNotNull(e.audio)
        h.assertNoInternalErrors()
    }

    @Test fun asrFinalTimeoutRestartsOnceThenFailsWithAudio() = runTest {
        val h = harness { engines.asr.finishDelayMs = 60_000 }
        h.dictate(stopAt = 2_800)
        assertEquals(2, h.w.engines.asr.sessions.size)                       // one restart (row 29), then row 30
        val e = h.only()
        assertEquals("timeout.asr_final", e.failureCode)
        assertNotNull(e.audio)
        h.assertNoInternalErrors()
    }

    @Test fun llmTimeoutInsertsRawAndShutsDownAHungLlm() = runTest {
        val h = harness { engines.llm.hang = true }
        h.dictate(stopAt = 2_800)
        assertEquals(listOf(raw), h.w.inserter.inserted)
        assertEquals(DictationBubble.Inserted(noCleanup = true), h.bubbles[h.bubbles.size - 2])
        assertEquals("timeout.llm", h.only().failureCode)
        assertTrue(h.w.engines.llm.cancelled >= 1)
        assertEquals(1, h.w.engines.llmHost.shutdowns)                       // no end within 1 s after cancel()
        h.assertInserted("skipped:timeout")
    }

    @Test fun llmDeathInPolishingInsertsRaw() = runTest {
        val h = harness { engines.llm.delayPerPieceMs = 1_000 }
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(3_300)
        assertTrue(h.state is DictationState.Polishing)
        h.w.engines.llmHost.crash()
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(raw), h.w.inserter.inserted)
        h.assertInserted("skipped:llm_died")
    }

    @Test fun llmStillLoadingBeyondLlmReadyInsertsRawAndTheLoadKeepsGoing() = runTest {
        val h = harness { engines.llm.loadDelayMs = 20_000 }
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(12_799)
        assertTrue(h.state is DictationState.Polishing)
        h.at(12_800)
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(raw), h.w.inserter.inserted)
        assertEquals("timeout.llm_ready", h.only().failureCode)
        assertEquals("skipped:llm_not_ready", h.only().cleanup?.outcome)
        assertEquals(listOf(ComputeUnit.CPU), h.w.engines.llm.loadedUnits)   // the load finished after the dictation
        // The next dictation is warm: no second load, cleanup runs.
        h.dictate(startAt = h.now + 1_000, stopAt = h.now + 3_800)
        assertEquals(1, h.w.engines.llm.loadAttempts.size)
        assertEquals("accept", h.history().maxBy { it.createdAt }.cleanup?.outcome)
        h.assertNoInternalErrors()
    }

    @Test fun llmTimerStartsOnlyAfterLlmReady() = runTest {
        // 5 s load, 2 s generation (4 pieces x 500 ms), adaptive timeout ~4.3 s: from the transcript it would fire.
        val h = harness {
            engines.llm.loadDelayMs = 5_000
            engines.llm.delayPerPieceMs = 500
            models.llmSpeed = LlmSpeed(prefillTps = 5_000.0, decodeTps = 1_000.0)
        }
        h.dictate(stopAt = 1_500)
        val budget = CleanupBudget.plan(raw.length, 3_129, 9_216, LlmSpeed(5_000.0, 1_000.0), DictationConfig())
        assertTrue("timeout ${budget.timeoutMs}", budget.timeoutMs < 5_000 + 2_000 - 1_500)
        h.assertInserted("accept")
    }

    @Test fun lowMemoryWithoutARunningAsrSkipsCleanupWithoutLoadingTheLlm() = runTest {
        val h = harness { memory.mb = 1_000 }
        h.dictate()
        assertTrue(h.w.engines.llm.loadAttempts.isEmpty())
        assertFalse(DictationBubble.Polishing in h.bubbles)
        assertEquals(DictationOutcome.Inserted(h.only().id, noCleanup = true, skipReason = "skipped:low_memory"), h.outcomes().single())
        h.assertInserted("skipped:low_memory")
    }

    @Test fun lowMemoryNextToAWarmAsrLoadsTheLlmAfterTheTailOnceAsrIsShutDown() = runTest {
        val h = harness { settings.edit { it.copy(cleanup = CleanupChoice.None) } }
        h.dictate()                                                          // :asr warm, :llm never loaded
        assertTrue(h.w.engines.asrHost.state.value is HostState.Up)
        h.w.settings.edit { it.copy(cleanup = CleanupChoice.Default) }
        h.w.memory.source = { if (h.w.engines.asrHost.state.value is HostState.Up) 1_000 else 6_000 }
        h.at(h.now + 1_000); h.tap(); h.at(h.now + 2_000)
        assertTrue(h.w.engines.llm.loadAttempts.isEmpty())                  // no parallel load
        h.at(h.now + 800); h.tap(); h.scope.testScheduler.advanceUntilIdle()
        assertEquals(1, h.w.engines.asrHost.shutdowns)
        assertEquals(listOf(ComputeUnit.CPU), h.w.engines.llm.loadAttempts)
        assertEquals("accept", h.history().maxBy { it.createdAt }.cleanup?.outcome)
        h.assertNoInternalErrors()
    }

    /** Review F5: a transcript that ends as NoSpeech keeps :asr warm and loads no LLM that nothing would use. */
    @Test fun lowMemoryWithANoSpeechTranscriptKeepsAsrWarmAndLoadsNoLlm() = runTest {
        val h = harness { settings.edit { it.copy(cleanup = CleanupChoice.None) } }
        h.dictate()
        h.w.settings.edit { it.copy(cleanup = CleanupChoice.Default) }
        h.w.memory.source = { if (h.w.engines.asrHost.state.value is HostState.Up) 1_000 else 6_000 }
        h.w.engines.asr.script = "   "                                     // blank text: row 26
        h.dictate(startAt = h.now + 1_000)
        assertEquals(DictationOutcome.Failed(MurmurError.NoSpeech, h.history().maxBy { it.createdAt }.id, audioKept = true), h.outcomes().last())
        assertEquals(0, h.w.engines.asrHost.shutdowns)
        assertTrue(h.w.engines.asrHost.state.value is HostState.Up)
        assertTrue(h.w.engines.llm.loadAttempts.isEmpty())
        h.assertNoInternalErrors()
    }

    @Test fun lowMemoryThatStaysShortAfterTheTailIsSkippedLowMemory() = runTest {
        val h = harness { settings.edit { it.copy(cleanup = CleanupChoice.None) } }
        h.dictate()
        h.w.settings.edit { it.copy(cleanup = CleanupChoice.Default) }
        h.w.memory.mb = 1_000
        val before = h.bubbles.size
        val start = h.now + 1_000
        h.at(start); h.tap(); h.at(start + 2_800); h.tap(); h.at(start + 3_500)
        assertTrue(h.outcomes().last() is DictationOutcome.Inserted)
        assertTrue(h.w.engines.llm.loadAttempts.isEmpty())                  // neither next to the ASR nor after it
        assertEquals(1, h.w.engines.asrHost.shutdowns)                       // :asr was shut down for it, still short
        assertFalse(DictationBubble.Polishing in h.bubbles.drop(before))
        assertEquals("skipped:low_memory", h.history().maxBy { it.createdAt }.cleanup?.outcome)
        h.scope.testScheduler.advanceUntilIdle()
        // The background prefix rebuild for the new cleanup choice respects the memory gate too.
        assertTrue(h.w.engines.llm.loadAttempts.isEmpty())
        h.assertNoInternalErrors()
    }

    @Test fun fiveMinuteTranscriptOnASlowUnitIsTooLongWithoutAnLlmCall() = runTest {
        val h = harness {
            audio.pcm = syntheticSpeech(totalMs = 300_000, speechMs = 290_000)
            engines.asr.script = List(850) { "слово" }.joinToString(" ")
            models.llmSpeed = LlmSpeed(prefillTps = 100.0, decodeTps = 10.0)
        }
        h.dictate(stopAt = 299_000)
        assertTrue(h.w.engines.llm.requests.isEmpty())
        assertEquals("llm.too_long", h.only().failureCode)
        h.assertInserted("skipped:too_long")
    }

    @Test fun contextOverflowFromTheLlmIsTooLong() = runTest {
        val h = harness { engines.llm.contextLimit = 10 }
        h.dictate()
        assertEquals(1, h.w.engines.llm.requests.size)
        assertEquals(listOf(raw), h.w.inserter.inserted)
        h.assertInserted("skipped:too_long")
    }

    @Test fun truncatedOutputIsNeverInserted() = runTest {
        val h = harness { engines.llm.stopReason = LlmStats.STOP_MAX_TOKENS }
        h.dictate()
        assertEquals(listOf(raw), h.w.inserter.inserted)
        h.assertInserted("fallback:truncated")
    }

    @Test fun fillerOnlyCleanupIsNothingToInsertStoredAsNoSpeech() = runTest {
        val h = harness { engines.asr.script = "ну э эм" }
        h.dictate()
        assertTrue(h.w.inserter.inserted.isEmpty())
        val e = h.only()
        assertEquals(EntryStatus.FAILED, e.status)
        assertEquals("audio.no_speech", e.failureCode)
        assertEquals(DictationOutcome.Failed(MurmurError.NoSpeech, e.id), h.outcomes().single())
        h.assertNoInternalErrors()
    }

    @Test fun llmDeathBeforeTheTranscriptReloadsOnce() = runTest {
        val h = harness()
        h.at(0); h.tap(); h.at(1_000)
        h.w.engines.llmHost.crash()
        h.at(2_800); h.tap(); h.scope.testScheduler.advanceUntilIdle()
        assertEquals(2, h.w.engines.llm.loadAttempts.size)
        assertEquals(2, h.w.engines.llm.prefixCalls.size)
        h.assertInserted("accept")
    }
}
