package app.murmur.feature.history

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.Settings
import app.murmur.testing.FakeClock
import app.murmur.testing.InMemoryAudioStore
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.VirtualClock
import app.murmur.testing.contrib.Wp16HistoryFixture
import app.murmur.testing.contrib.Wp16LiveHistoryRepository
import app.murmur.testing.contrib.Wp16HistoryFixture.TODAY
import app.murmur.testing.contrib.Wp16HistoryFixture.ZONE
import app.murmur.ui.design.StaticAppInfoProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * HistoryViewModel on the :testing fakes (InMemoryHistoryRepository, InMemoryAudioStore, FakeClock / VirtualClock,
 * StaticAppInfoProvider); no Robolectric. Virtual time drives the clock tick and the undo window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val store = ViewModelStore()

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private class World(
        val history: Wp16LiveHistoryRepository = Wp16LiveHistoryRepository(),
        val audio: InMemoryAudioStore = InMemoryAudioStore(),
        val settings: InMemorySettingsRepository = InMemorySettingsRepository(Settings()),
    ) {
        val retries = mutableListOf<EntryId>()
        val repolishes = mutableListOf<EntryId>()
        var retryResult: Outcome<EntryId>? = null
        var repolishGate: CompletableDeferred<Outcome<EntryId>>? = null
        var ids: List<EntryId> = emptyList()

        fun deps(clock: MurmurClock) = HistoryDeps(
            history = history,
            settings = settings,
            audio = audio,
            clock = clock,
            retry = { id -> retries += id; retryResult ?: Outcome.Ok(id) },
            repolish = { id -> repolishes += id; repolishGate?.await() ?: Outcome.Ok(id) },
            apps = StaticAppInfoProvider(Wp16HistoryFixture.LABELS),
            modelName = { Wp16HistoryFixture.MODEL_NAMES[it] },
        )
    }

    private fun TestScope.setUp(world: World, clock: MurmurClock, seed: Boolean = true): HistoryViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        if (seed) {
            val job = backgroundScope.launch { world.ids = Wp16HistoryFixture.seed(world.history, world.audio) }
            runCurrent()
            check(job.isCompleted)
        }
        val vm = provide { HistoryViewModel(world.deps(clock), compute = dispatcher) }
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.detail.collect {} }
        runCurrent()
        return vm
    }

    /** Created through a ViewModelStore, so that tearDown clears it (cancels viewModelScope) like the activity would. */
    private fun provide(create: () -> HistoryViewModel): HistoryViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[HistoryViewModel::class]

    private fun fixedClock(at: Instant = Wp16HistoryFixture.NOW, zone: ZoneId = ZONE) = FakeClock(at, zone)

    // ------------------------------------------------------------------ grouping

    @Test
    fun groupsByDayNewestFirstWithLabelsCountsAndRetentionLine() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val s = vm.state.value
        assertEquals(ListMode.LIST, s.mode)
        assertEquals(7, s.retentionDays)
        assertEquals(listOf(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(6)), s.days.map { it.day })
        assertEquals(listOf(DayKind.TODAY, DayKind.YESTERDAY, DayKind.DATE, DayKind.DATE), s.days.map { it.kind })
        assertEquals(listOf(6, 3, 3, 1), s.days.map { it.rows.size })
        // Rows newest first, times in the clock's zone, app names from the provider, conversation kept apart.
        val first = s.days[0].rows[0]
        assertEquals("Telegram", first.appName)
        assertEquals("Дима Соколов", first.conversation)
        assertEquals(LocalTime.of(14, 32), first.time.toLocalTime())
        assertEquals(listOf("14:32", "13:05", "12:41", "11:20", "09:58", "09:12"), s.days[0].rows.map { it.time.toLocalTime().toString() })
        // Status: Copied and Failed rows; the Failed row has no text, a no-speech failure and its recording kept.
        val termux = s.days[0].rows[3]
        assertEquals(EntryStatus.COPIED, termux.status)
        val chrome = s.days[0].rows[5]
        assertEquals(EntryStatus.FAILED, chrome.status)
        assertNull(chrome.text)
        assertEquals(FailureKind.NO_SPEECH, chrome.failure)
        assertTrue(chrome.audioKept)
        // Only the oldest kept day says "Deletes tonight" (12 Nov with 7 days).
        assertEquals(listOf(false, false, false, true), s.days.map { it.deletesTonight })
    }

    @Test
    fun midnightMovesTodayToYesterdayWhileShown() = runTest {
        val w = World()
        // 23:59:30 on Wednesday 18 November in Moscow, following virtual time.
        val origin = TODAY.atTime(23, 59, 30).atZone(ZONE).toInstant()
        val vm = setUp(w, VirtualClock(origin, ZONE) { testScheduler.currentTime })
        assertEquals(TODAY, vm.state.value.today)
        assertEquals(DayKind.TODAY, vm.state.value.days[0].kind)
        assertTrue(vm.state.value.days.last().deletesTonight)

        advanceTimeBy(31_000)
        runCurrent()
        val s = vm.state.value
        assertEquals(TODAY.plusDays(1), s.today)
        // The entries keep their stored day: 18 Nov is now Yesterday, 17 Nov a dated day.
        assertEquals(listOf(DayKind.YESTERDAY, DayKind.DATE, DayKind.DATE, DayKind.DATE), s.days.map { it.kind })
        // 12 Nov is past the window now (retention deletes it at midnight); nothing left is the oldest kept day 13 Nov.
        assertEquals(listOf(false, false, false, false), s.days.map { it.deletesTonight })
    }

    @Test
    fun timeZoneChangeRelabelsDaysAndTimesButKeepsTheStoredDay() = runTest {
        val w = World()
        // 01:00 on 18 Nov in Moscow = 22:00 UTC on 17 Nov.
        val clock = fixedClock(TODAY.atTime(1, 0).atZone(ZONE).toInstant(), ZONE)
        val vm = setUp(w, clock, seed = false)
        val created = TODAY.atTime(0, 30).atZone(ZONE).toInstant()
        val e = Wp16HistoryFixture.entry(Wp16HistoryFixture.ITEMS[1], created, null)
        backgroundScope.launch { w.history.insert(e) }
        runCurrent()
        assertEquals(DayKind.TODAY, vm.state.value.days.single().kind)
        assertEquals(LocalTime.of(0, 30), vm.state.value.days.single().rows.single().time.toLocalTime())

        // Travel to London: same instant, the local date there is still 17 November.
        clock.zoneId = ZoneId.of("Europe/London")
        advanceTimeBy(HistoryViewModel.TICK_MS + 1)
        runCurrent()
        val s = vm.state.value
        assertEquals(LocalDate.of(2026, 11, 17), s.today)
        val g = s.days.single()
        assertEquals(TODAY, g.day) // stored local day: what retention deletes by
        assertEquals(DayKind.DATE, g.kind)
        assertEquals(LocalTime.of(21, 30), g.rows.single().time.toLocalTime())
        assertEquals(LocalDate.of(2026, 11, 17), g.rows.single().time.toLocalDate())
    }

    @Test
    fun deletesTonightFollowsRetention7And3And1() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        fun flagged() = vm.state.value.days.filter { it.deletesTonight }.map { it.day }

        assertEquals(listOf(TODAY.minusDays(6)), flagged())
        w.settings.edit { it.copy(retentionDays = 3) }
        runCurrent()
        assertEquals(3, vm.state.value.retentionDays)
        assertEquals(listOf(TODAY.minusDays(2)), flagged())
        w.settings.edit { it.copy(retentionDays = 1) }
        runCurrent()
        assertEquals(listOf(TODAY), flagged())
        assertEquals(1, vm.state.value.retentionDays)
    }

    @Test
    fun retentionZeroShowsTheOffStateEmptyAndErrorStates() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock(), seed = false)
        assertEquals(ListMode.EMPTY, vm.state.value.mode)
        w.settings.edit { it.copy(retentionDays = 0) }
        runCurrent()
        assertEquals(ListMode.OFF, vm.state.value.mode)
        assertTrue(vm.state.value.days.isEmpty())
    }

    @Test
    fun aRepositoryErrorShowsTheErrorStateAndRetryReloads() = runTest {
        val w = World()
        var fail = true
        val flaky = object : app.murmur.core.store.HistoryRepository by w.history {
            override fun entries(): Flow<List<HistoryEntry>> = if (fail) flow { throw IllegalStateException("db") } else w.history.entries()
        }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        backgroundScope.launch { Wp16HistoryFixture.seed(w.history, w.audio) }
        runCurrent()
        val deps = w.deps(fixedClock())
        val vm = provide { HistoryViewModel(HistoryDeps(flaky, deps.settings, deps.audio, deps.clock, deps.retry, deps.repolish, deps.apps), compute = StandardTestDispatcher(testScheduler)) }
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(ListMode.ERROR, vm.state.value.mode)
        assertTrue(vm.state.value.error)
        fail = false
        vm.retryLoad()
        runCurrent()
        assertEquals(ListMode.LIST, vm.state.value.mode)
    }

    // ------------------------------------------------------------------ search

    @Test
    fun searchFindsFinalTextAndHighlightsWithFolding() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        vm.openSearch()
        runCurrent()
        // Search open, nothing typed: the whole list; chips by number of dictations, ties by the most recent one.
        val idle = vm.state.value.search!!
        assertFalse(idle.active)
        assertEquals(13, idle.count)
        assertEquals(listOf("Telegram", "Gmail", "Slack", "Notes", "Termux", "Chrome", "Google Docs"), idle.apps.map { it.label })

        vm.setQuery("созвон")
        runCurrent()
        val s = vm.state.value.search!!
        assertTrue(s.active)
        assertEquals(2, s.count)
        assertEquals(listOf(DayKind.TODAY, DayKind.DATE), s.results!!.map { it.kind })
        val hit = s.results[0].rows.single()
        assertEquals(MatchIn.FINAL, hit.matchIn)
        assertEquals(Wp16HistoryFixture.H1_FINAL, hit.text)
        val r = hit.highlights.single()
        assertEquals("созвон", hit.text!!.substring(r.first, r.last + 1))

        // Case and ё are folded like the stored search_text (ADR-072): "ПРОШЕЛ" highlights "прошёл". (The Room store
        // folds the query the same way; the in-memory fake only ignores case, so this is checked on the highlighter.)
        val folded = Highlight.ranges("Деплой на staging прошёл, но", "ПРОШЕЛ").single()
        assertEquals("прошёл", "Деплой на staging прошёл, но".substring(folded.first, folded.last + 1))
    }

    @Test
    fun searchHitOnlyInTheOriginalTranscriptShowsTheOriginal() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        vm.openSearch()
        vm.setQuery("одиннадцати")
        runCurrent()
        val s = vm.state.value.search!!
        assertEquals(1, s.count)
        val row = s.results!!.single().rows.single()
        assertEquals(w.ids[0], row.id)
        assertEquals(MatchIn.ORIGINAL, row.matchIn)
        assertEquals(Wp16HistoryFixture.H1_RAW, row.text)
        val r = row.highlights.single()
        assertEquals("одиннадцати", row.text!!.substring(r.first, r.last + 1))
    }

    @Test
    fun appFilterAloneListsThatAppAndNoResultsIsEmpty() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        vm.openSearch()
        vm.setAppFilter(Wp16HistoryFixture.TELEGRAM)
        runCurrent()
        val s = vm.state.value.search!!
        assertTrue(s.active)
        assertEquals(4, s.count)
        assertTrue(s.results!!.flatMap { it.rows }.all { it.entry.app.pkg == Wp16HistoryFixture.TELEGRAM && it.highlights.isEmpty() })
        vm.setQuery("созвон с юристом")
        runCurrent()
        assertEquals(0, vm.state.value.search!!.count)
        assertTrue(vm.state.value.search!!.results!!.isEmpty())
        vm.closeSearch()
        runCurrent()
        assertNull(vm.state.value.search)
    }

    @Test
    fun excerptKeepsALateMatchVisible() {
        val text = "Слово ".repeat(30) + "цель в конце."
        val ranges = Highlight.ranges(text, "цель")
        val (cut, shifted) = Highlight.excerpt(text, ranges)
        assertTrue(cut.startsWith("…"))
        assertEquals("цель", cut.substring(shifted[0].first, shifted[0].last + 1))
        assertTrue(shifted[0].first < Highlight.EXCERPT_CONTEXT + 12)
    }

    // ------------------------------------------------------------------ delete + undo

    @Test
    fun undoRestoresTheEntryAndKeepsItsAudio() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val id = w.ids[0]
        val ref = w.history.snapshot().first { it.id == id }.audio!!
        vm.delete(setOf(id))
        runCurrent()
        val s = vm.state.value
        assertNotNull(s.pendingDelete)
        assertFalse(s.days.flatMap { it.rows }.any { it.id == id })
        assertEquals(5, s.days[0].rows.size)

        advanceTimeBy(3_000)
        vm.undo()
        runCurrent()
        assertNull(vm.state.value.pendingDelete)
        assertTrue(vm.state.value.days[0].rows.any { it.id == id })
        // Past the original window nothing is committed.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(w.history.snapshot().any { it.id == id })
        assertTrue(w.audio.exists(ref))
    }

    @Test
    fun commitAfterTheUndoWindowDeletesRowAndAudio() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val id = w.ids[0]
        val ref = w.history.snapshot().first { it.id == id }.audio!!
        assertTrue(w.audio.exists(ref))
        vm.delete(setOf(id), undoWindowMs = 6_000)
        advanceTimeBy(5_999)
        runCurrent()
        assertTrue("still undoable at 5.999 s", w.audio.exists(ref))
        assertTrue(w.history.snapshot().any { it.id == id })
        advanceTimeBy(2)
        runCurrent()
        assertFalse(w.audio.exists(ref))
        assertFalse(w.history.snapshot().any { it.id == id })
        assertNull(vm.state.value.pendingDelete)
    }

    @Test
    fun aSecondDeleteCommitsTheFirstAndUndoOnlyRestoresTheSecond() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val (a, b) = w.ids[0] to w.ids[1]
        val refA = w.history.snapshot().first { it.id == a }.audio!!
        vm.delete(setOf(a))
        runCurrent()
        vm.delete(setOf(b))
        runCurrent()
        assertFalse(w.audio.exists(refA))
        assertFalse(w.history.snapshot().any { it.id == a })
        vm.undo()
        runCurrent()
        assertTrue(vm.state.value.days[0].rows.any { it.id == b })
        assertFalse(vm.state.value.days[0].rows.any { it.id == a })
    }

    @Test
    fun multiSelectCopiesAndDeletesSeveral() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        vm.toggleSelection(w.ids[0])
        vm.toggleSelection(w.ids[1])
        vm.toggleSelection(w.ids[5]) // Failed: no text to copy
        runCurrent()
        assertEquals(3, vm.state.value.selection.size)
        assertTrue(vm.state.value.selecting)
        val text = vm.textsOf(vm.state.value.selection)
        assertEquals(Wp16HistoryFixture.H1_FINAL + "\n\n" + Wp16HistoryFixture.ITEMS[1].text, text)
        vm.toggleSelection(w.ids[5])
        runCurrent()
        assertEquals(2, vm.state.value.selection.size)
        vm.delete(vm.state.value.selection)
        runCurrent()
        assertTrue(vm.state.value.selection.isEmpty())
        assertEquals(setOf(w.ids[0], w.ids[1]), vm.state.value.pendingDelete!!.ids)
        assertEquals(4, vm.state.value.days[0].rows.size)
        advanceTimeBy(HistoryViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()
        assertEquals(11, w.history.snapshot().size)
        assertEquals(10, w.audio.listAll().size)
    }

    // ------------------------------------------------------------------ detail, retry, repolish

    @Test
    fun detailHasPeaksDiffModelsAndTheExactDeletionTime() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        vm.open(w.ids[0])
        runCurrent()
        val d = vm.detail.value!!
        assertEquals(w.ids[0], d.id)
        assertEquals("Дима Соколов", d.title)
        assertEquals("Telegram", d.appName)
        assertEquals(DayKind.TODAY, d.dayKind)
        assertEquals(12_000L, d.durationMs)
        val audio = d.audio as AudioUi.Ready
        assertEquals(12_000L, audio.durationMs)
        assertTrue(audio.peaks.isNotEmpty() && audio.peaks.max() == 1f && audio.peaks.any { it < 0.5f })
        assertEquals(ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, ZONE), d.deletesAt)
        assertEquals(listOf("Qwen3-ASR 0.6B", "LFM3 1.7B"), d.models.map { it.name })
        assertTrue(WordDiff.hasChanges(d.diff))
        assertFalse(d.failed)
        assertTrue(d.canRepolish)
    }

    @Test
    fun failedEntryWithoutItsRecordingCannotBeRetried() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val chrome = w.ids[5]
        vm.open(chrome)
        runCurrent()
        assertTrue(vm.detail.value!!.failed)
        assertTrue(vm.detail.value!!.canRetry)
        // The recording disappears (file deleted): "Recording not available", Try again disabled.
        w.audio.delete(w.history.snapshot().first { it.id == chrome }.audio!!)
        vm.open(null)
        runCurrent()
        vm.open(chrome)
        runCurrent()
        assertEquals(AudioUi.Unavailable, vm.detail.value!!.audio)
        assertFalse(vm.detail.value!!.canRetry)
        // An entry stored without audio (Termux) is unavailable too.
        vm.open(w.ids[3])
        runCurrent()
        assertEquals(AudioUi.Unavailable, vm.detail.value!!.audio)
    }

    @Test
    fun retryAndRepolishCallTheOrchestratorAndReportFailures() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val events = mutableListOf<HistoryEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.open(w.ids[5])
        runCurrent()
        vm.retry(w.ids[5])
        runCurrent()
        assertEquals(listOf(w.ids[5]), w.retries)
        assertTrue(events.isEmpty())

        w.retryResult = Outcome.Err(MurmurError.NoSpeech)
        vm.retry(w.ids[5])
        runCurrent()
        assertEquals(HistoryEvent.RetryFailed(w.ids[5], MurmurError.NoSpeech), events.single())

        // Re-polish shows progress while it runs and ignores a second tap.
        val gate = CompletableDeferred<Outcome<EntryId>>()
        w.repolishGate = gate
        vm.open(w.ids[0])
        runCurrent()
        vm.repolish(w.ids[0])
        runCurrent()
        assertEquals(Busy.REPOLISH, vm.detail.value!!.busy)
        assertFalse(vm.detail.value!!.canRepolish)
        vm.repolish(w.ids[0])
        runCurrent()
        assertEquals(listOf(w.ids[0]), w.repolishes)
        gate.complete(Outcome.Err(MurmurError.LlmFailed("no_model")))
        runCurrent()
        assertNull(vm.detail.value!!.busy)
        assertEquals(HistoryEvent.RepolishFailed(w.ids[0], MurmurError.LlmFailed("no_model")), events.last())
    }

    @Test
    fun aRetriedEntryUpdatedInPlaceRefreshesTheDetail() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        val chrome = w.ids[5]
        vm.open(chrome)
        runCurrent()
        val e = w.history.entry(chrome).first()!!
        backgroundScope.launch { w.history.update(e.copy(status = EntryStatus.COPIED, finalText = "Готово.", rawText = "готово", failureCode = null)) }
        runCurrent()
        val d = vm.detail.value!!
        assertFalse(d.failed)
        assertEquals("Готово.", d.entry!!.finalText)
        assertEquals(EntryStatus.COPIED, vm.state.value.days[0].rows.first { it.id == chrome }.status)
    }

    @Test
    fun aDeletedOpenEntryReportsMissing() = runTest {
        val w = World()
        val vm = setUp(w, fixedClock())
        vm.open(w.ids[1])
        runCurrent()
        assertFalse(vm.detail.value!!.missing)
        vm.delete(setOf(w.ids[1]))
        runCurrent()
        assertTrue(vm.detail.value!!.missing)
        assertEquals("Gmail", vm.appNameOf(w.ids[1]))
    }
}
