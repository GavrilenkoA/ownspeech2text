package app.murmur.core.dictation

import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.InsertMethod
import app.murmur.testing.FakeTargetReader
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry (WP-11 Deliver 4, ARCHITECTURE 5.2 row 49): the stored audio of a Failed entry runs through the normal flow
 * from Finishing, with the bubble showing progress and the result, and the same entry is updated.
 */
class RetryTest {
    private val raw = "привет это тестовая диктовка"
    private val cleaned = "Привет это тестовая диктовка."
    /** Not a unit failure (EngineKeeper.mark): the model stays selectable, so Retry can load it once memory is free. */
    private val loadError = MurmurError.OutOfMemory(ModelKind.ASR)

    /** A dictation whose ASR fails to load (CPU, no fallback): "Couldn't load the speech model" + Retry, audio kept. */
    private fun Harness.failToLoad(): Long {
        w.engines.asr.loadError = loadError
        at(0); tap(); at(2_800); tap(); at(3_000)
        assertEquals(DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY), o.bubble.value)
        val e = only()
        assertEquals(EntryStatus.FAILED, e.status)
        assertNotNull(e.audio)
        assertTrue(w.unitHealth.failedUnits.isEmpty())
        w.engines.asr.loadError = null
        return e.id.value
    }

    @Test fun errorPillRetryRunsTheStoredAudioFromFinishingAndInsertsIt() = runTest {
        val h = harness()
        val id = EntryId(h.failToLoad())
        val failed = h.only()
        val screenshots = h.w.target.screenshots
        val before = h.bubbles.size
        h.press(UserInput.ResultAction)
        h.scope.testScheduler.advanceUntilIdle()
        // Progress and result on the pill, like a dictation after its stop.
        assertEquals(listOf(DictationBubble.Transcribing, DictationBubble.Polishing, DictationBubble.Inserted(noCleanup = false), DictationBubble.Idle),
            h.bubbles.drop(before))
        assertEquals(DictationOutcome.Inserted(id, noCleanup = false), h.outcomes().last())
        // Delivered into the focused field, clipboard first.
        assertEquals(listOf(cleaned), h.w.inserter.inserted)
        assertEquals(listOf("clipboard", "insert"), h.w.inserter.calls)
        // The same entry, now inserted; its recording, app and time stay.
        val e = h.only()
        assertEquals(id, e.id)
        assertEquals(EntryStatus.INSERTED, e.status)
        assertEquals(cleaned, e.finalText)
        assertEquals(raw, e.rawText)
        assertEquals("accept", e.cleanup?.outcome)
        assertEquals(InsertMethod.COMMIT_TEXT, e.insertMethod)
        assertNull(e.failureCode)
        assertEquals(failed.audio, e.audio)
        assertEquals(failed.createdAt, e.createdAt)
        assertEquals(failed.app, e.app)
        // The stored recording, all of it, fed at once; no microphone, no screenshot, no second metrics row.
        assertEquals(1, h.w.audio.starts)
        assertEquals(2_800L * 16, h.w.engines.asr.sessions.last().samples)
        assertEquals(screenshots, h.w.target.screenshots)
        assertEquals(1, h.w.metrics.snapshot().size)
        h.assertNoInternalErrors()
    }

    /** The ASR is warm and attaches at once; the stored audio arrives later and still reaches it, all of it. */
    @Test fun aSlowAudioReadStillFeedsTheWholeRecording() = runTest {
        val h = harness()
        h.dictate()                                                          // :asr warm with the model loaded
        val first = h.only()
        h.w.history.update(first.copy(status = EntryStatus.FAILED, finalText = null, failureCode = loadError.code))
        h.w.audioStore.readDelayMs = 3_000                                    // beyond MIC_START's 2 s: a 5 min file on a slow day
        assertEquals(Outcome.Ok(first.id), h.o.retry(first.id))
        assertEquals(2_800L * 16, h.w.engines.asr.sessions.last().samples)
        assertEquals(EntryStatus.INSERTED, h.only().status)
        h.assertNoInternalErrors()
    }

    @Test fun aRetryThatFailsAgainShowsTheErrorAgainAndKeepsTheEntry() = runTest {
        val h = harness()
        val id = EntryId(h.failToLoad())
        h.w.engines.asr.loadError = loadError
        h.press(UserInput.ResultAction)
        h.at(3_500)
        assertEquals(DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY), h.o.bubble.value)
        assertEquals(DictationOutcome.Failed(loadError, id, audioKept = true), (h.state as DictationState.Done).outcome)
        val e = h.only()
        assertEquals(EntryStatus.FAILED, e.status)
        assertEquals(loadError.code, e.failureCode)
        assertNotNull(e.audio)
        assertTrue(h.w.inserter.calls.isEmpty())
        // Once the model loads, Retry on that pill inserts.
        h.w.engines.asr.loadError = null
        h.press(UserInput.ResultAction)
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(EntryStatus.INSERTED, h.only().status)
        assertEquals(listOf(cleaned), h.w.inserter.inserted)
        h.assertNoInternalErrors()
    }

    @Test fun retryTappedBeforeTheEntryIsWrittenRunsOnceItIs() = runTest {
        val h = harness { history.writeDelayMs = 400 }
        h.w.engines.asr.loadError = loadError
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(2_810)
        val done = h.state as DictationState.Done
        assertTrue(done.recording)
        h.press(UserInput.ResultAction)
        h.w.engines.asr.loadError = null
        assertEquals(2_810L, (h.state as DictationState.Done).retryAtMs)    // remembered, nothing started yet
        assertEquals(DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY), h.o.bubble.value)
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(EntryStatus.INSERTED, h.only().status)
        assertEquals(listOf(cleaned), h.w.inserter.inserted)
        assertTrue(h.outcomes().last() is DictationOutcome.Inserted)
        h.assertNoInternalErrors()
    }

    @Test fun withNoRecordingKeptRetryIsANewDictation() = runTest {
        val h = harness { settings.edit { it.copy(keepRecordings = false) } }
        h.w.engines.asr.loadError = loadError
        h.at(0); h.tap(); h.at(2_800); h.tap(); h.at(3_000)
        assertNull(h.only().audio)
        h.w.engines.asr.loadError = null
        h.press(UserInput.ResultAction); h.at(3_200)
        assertTrue(h.state is DictationState.Listening)
        assertEquals(2, h.w.audio.starts)
        h.assertNoInternalErrors()
    }

    @Test fun historyRetryFromInsideTheAppIsCopiedAndTheNpuFailureMovesToCpu() = runTest {
        val h = harness {
            models.npuFirst = true; models.cpuFallback = false
            engines.asr.loadErrors[ComputeUnit.NPU] = MurmurError.EngineLoadFailed(ModelKind.ASR, "qnn")
        }
        h.dictate()
        val failed = h.only()
        assertEquals(EntryStatus.FAILED, failed.status)
        // The CPU artifact got installed; the NPU stays marked failed, so the resolver picks CPU. The user is in History.
        h.w.models.cpuFallback = true
        h.w.target.target = FakeTargetReader.defaultTarget("app.murmur").let { it.copy(field = it.field?.copy(beforeCursor = "поиск")) }
        assertEquals(Outcome.Ok(failed.id), h.o.retry(failed.id))
        val e = h.only()
        assertEquals(EntryStatus.COPIED, e.status)
        assertEquals(InsertMethod.CLIPBOARD_ONLY, e.insertMethod)
        assertEquals(cleaned, e.finalText)
        assertEquals(raw, e.rawText)
        assertEquals("accept", e.cleanup?.outcome)
        assertEquals(ComputeUnit.CPU, e.asr?.unit)
        assertEquals(listOf(cleaned), h.w.inserter.clipboard)
        assertTrue(h.w.inserter.inserted.isEmpty())                          // never into this app's own field
        assertTrue(!h.w.engines.llm.requests.last().suffix.contains("поиск"))  // nor with its text as context
        assertEquals(DictationOutcome.Copied(failed.id), h.outcomes().last())
        h.assertNoInternalErrors()
    }

    @Test fun historyRetryOfSilenceIsNoSpeechAndKeepsTheRecording() = runTest {
        val h = harness()
        h.failToLoad()
        val failed = h.only()
        h.w.audioStore.files[failed.audio!!.path] = ShortArray(48_000)
        assertEquals(Outcome.Err(MurmurError.NoSpeech), h.o.retry(failed.id))
        val e = h.only()
        assertEquals(EntryStatus.FAILED, e.status)
        assertEquals(MurmurError.NoSpeech.code, e.failureCode)
        assertEquals(failed.audio, e.audio)
        assertTrue(failed.audio!!.path in h.w.audioStore.files)
        h.assertNoInternalErrors()
    }

    @Test fun historyRetryOfAMissingRecordingFails() = runTest {
        val h = harness()
        h.failToLoad()
        val failed = h.only()
        h.w.audioStore.files.remove(failed.audio!!.path)
        assertEquals(Outcome.Err(MurmurError.StorageFailed("audio")), h.o.retry(failed.id))
        assertEquals(EntryStatus.FAILED, h.only().status)
        assertEquals(1, h.w.audio.starts)
        h.assertNoInternalErrors()
    }

    @Test fun aStoredRecordingThatCannotBeReadInTimeFailsTheRetry() = runTest {
        val h = harness()
        h.failToLoad()
        val failed = h.only()
        h.w.audioStore.readDelayMs = 10_001
        assertEquals(Outcome.Err(MurmurError.StorageFailed("audio_read")), h.o.retry(failed.id))
        assertEquals(failed, h.only())                                       // the entry and its audio untouched
        assertTrue(h.w.engines.asr.sessions.all { it.samples == 0L })     // the late read feeds nothing
        h.assertNoInternalErrors()
    }

    @Test fun historyRetryWaitsForARunningDictation() = runTest {
        val h = harness()
        val id = EntryId(h.failToLoad())
        h.at(10_000); h.tap(); h.at(10_500)
        var result: Outcome<EntryId>? = null
        h.scope.launch { result = h.o.retry(id) }
        h.at(11_000)
        assertTrue(h.state is DictationState.Listening)
        assertNull(result)
        h.at(12_800); h.tap()
        h.scope.testScheduler.advanceUntilIdle()
        assertEquals(Outcome.Ok(id), result)
        assertEquals(listOf(EntryStatus.INSERTED, EntryStatus.INSERTED), h.history().map { it.status })
        assertEquals(2, h.history().size)
        h.assertNoInternalErrors()
    }
}
