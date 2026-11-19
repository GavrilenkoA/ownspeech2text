package app.murmur.feature.dashboard

import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.Settings
import app.murmur.testing.FakeClock
import app.murmur.testing.InMemoryMetricsRepository
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.VirtualClock
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.testing.contrib.Wp12MetricsFixture.TELEGRAM
import app.murmur.testing.contrib.Wp12MetricsFixture.TODAY
import app.murmur.testing.contrib.Wp12MetricsFixture.row
import app.murmur.core.model.MurmurClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * View model with the WP-12 fixture: metrics.since(today − 6) → StatsCalculator → state, and the texts of every
 * element in English and Russian from that state; loading, error + retry, empty, range, typing speed, midnight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val en = ResFileStrings(ResFileStrings.EN)
    private val ru = ResFileStrings(ResFileStrings.RU)

    private fun deps(
        metrics: MetricsRepository,
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        clock: MurmurClock = FakeClock(),
        connected: Boolean = true,
    ) = DashboardDeps(metrics, settings, DefaultStatsCalculator(), clock, MutableStateFlow(connected), FixtureTexts.labels)

    private suspend fun seeded(rows: List<DictationMetrics> = Wp12MetricsFixture.week) =
        InMemoryMetricsRepository().apply { rows.forEach { add(it) } }

    /** Starts the view model with a subscriber (the screen) in [TestScope.backgroundScope]. */
    private fun TestScope.start(d: DashboardDeps): DashboardViewModel {
        val vm = DashboardViewModel(d, backgroundScope)
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    @Test
    fun loadingThenFixtureTextsInEnglishAndRussian() = runTest {
        val vm = start(deps(seeded()))
        assertEquals(DashboardState.Loading, vm.state.value)
        runCurrent()
        val ready = vm.state.value as DashboardState.Ready
        assertEquals(TODAY, ready.today)
        assertFalse(ready.empty)
        assertEquals(40, ready.typingWpm)
        assertEquals(2, ready.copiedInTopApp)
        FixtureTexts.assertTexts(FixtureTexts.en, FixtureTexts.formatter(en).format(ready))
        FixtureTexts.assertTexts(FixtureTexts.ru, FixtureTexts.formatter(ru).format(ready))
    }

    @Test
    fun readsTheSevenDaysEndingToday() = runTest {
        val asked = mutableListOf<LocalDate>()
        val store = seeded()
        val spy = object : MetricsRepository by store {
            override fun since(day: LocalDate): Flow<List<DictationMetrics>> = store.since(day).also { asked += day }
        }
        val vm = start(deps(spy))
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 11, 12)), asked)
        // The row of 19 November (tomorrow) is in the slice since() returns but never counted.
        assertEquals(414, (vm.state.value as DashboardState.Ready).week.last7Days.sumOf { it.second })
    }

    @Test
    fun newRowsUpdateTheState() = runTest {
        val store = seeded()
        val vm = start(deps(store))
        runCurrent()
        store.add(row(TODAY, TELEGRAM, wordsFinal = 16, recordMs = 8_000, speechMs = 6_000, stopToDoneMs = 900))
        runCurrent()
        assertEquals(200, (vm.state.value as DashboardState.Ready).week.todayWords)
    }

    @Test
    fun errorThenRetry() = runTest {
        val store = seeded()
        var fail = true
        val flaky = object : MetricsRepository by store {
            override fun since(day: LocalDate): Flow<List<DictationMetrics>> =
                if (fail) flow { throw IOException("disk") } else store.since(day)
        }
        val vm = start(deps(flaky))
        runCurrent()
        assertEquals(DashboardState.Error, vm.state.value)
        fail = false
        vm.retry()
        runCurrent()
        assertTrue(vm.state.value is DashboardState.Ready)
    }

    @Test
    fun emptyWhenNothingWasDelivered() = runTest {
        val none = start(deps(seeded(emptyList())))
        runCurrent()
        assertTrue((none.state.value as DashboardState.Ready).empty)
        // A failed dictation alone counts 0 everywhere, so the dashboard is still empty.
        val failedOnly = start(deps(seeded(Wp12MetricsFixture.week.filter { it.status == EntryStatus.FAILED })))
        runCurrent()
        assertTrue((failedOnly.state.value as DashboardState.Ready).empty)
        // Rows outside the 7 days do not count either.
        val old = start(deps(seeded(listOf(row(TODAY.minusDays(7), TELEGRAM, wordsFinal = 5, recordMs = 4_000, speechMs = 3_000, stopToDoneMs = 500)))))
        runCurrent()
        assertTrue((old.state.value as DashboardState.Ready).empty)
    }

    @Test
    fun rangeTodayScopesMetricsLanguagesAndApps() = runTest {
        val vm = start(deps(seeded()))
        runCurrent()
        vm.setRange(DashboardRange.Today)
        runCurrent()
        val ready = vm.state.value as DashboardState.Ready
        assertEquals(DashboardRange.Today, ready.range)
        assertEquals(414, ready.week.last7Days.sumOf { it.second })
        // Today: Telegram 100 + 50, Termux 30 (copied), WhatsApp 4; the week's languages were 6/4/1.
        assertEquals(listOf(TELEGRAM to 150, Wp12MetricsFixture.TERMUX to 30, Wp12MetricsFixture.WHATSAPP to 4), ready.ranged.topApps)
        assertEquals(mapOf("ru" to 2, "en" to 1, DefaultStatsCalculator.MIXED to 1), ready.ranged.languages)
        assertEquals(1, ready.copiedInTopApp)
        vm.setRange(DashboardRange.Week)
        runCurrent()
        assertEquals(ready.week, (vm.state.value as DashboardState.Ready).ranged)
    }

    @Test
    fun typingSpeedComesFromSettingsAndIsWrittenBack() = runTest {
        val settings = InMemorySettingsRepository(Settings(typingWpm = 30))
        val vm = start(deps(seeded(), settings))
        runCurrent()
        val ready = vm.state.value as DashboardState.Ready
        assertEquals(30, ready.typingWpm)
        // 184 / 30 = 6.1 -> 6 min of typing, 1 min of speaking.
        assertEquals(
            "1 min of speaking. Typing this at 30 wpm would take about 6 min, so roughly 5 min saved.",
            FixtureTexts.formatter(en).format(ready).today.sentence.text,
        )
        vm.setTypingWpm(55)
        runCurrent()
        assertEquals(55, settings.settings.value.typingWpm)
        assertEquals(55, (vm.state.value as DashboardState.Ready).typingWpm)
        vm.setTypingWpm(5000)
        runCurrent()
        assertEquals(DashboardViewModel.MAX_WPM, settings.settings.value.typingWpm)
        vm.setTypingWpm(0)
        runCurrent()
        assertEquals(DashboardViewModel.MIN_WPM, settings.settings.value.typingWpm)
    }

    @Test
    fun movesToTheNextDayAtLocalMidnight() = runTest {
        // 23:59 in Moscow on 18 November.
        val clock = VirtualClock(origin = Instant.parse("2026-11-18T20:59:00Z")) { testScheduler.currentTime }
        val asked = mutableListOf<LocalDate>()
        val store = seeded()
        val spy = object : MetricsRepository by store {
            override fun since(day: LocalDate): Flow<List<DictationMetrics>> = store.since(day).also { asked += day }
        }
        val vm = start(deps(spy, clock = clock))
        runCurrent()
        assertEquals(TODAY, (vm.state.value as DashboardState.Ready).today)
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(TODAY, (vm.state.value as DashboardState.Ready).today)
        advanceTimeBy(2_000)
        runCurrent()
        val next = vm.state.value as DashboardState.Ready
        assertEquals(TODAY.plusDays(1), next.today)
        assertEquals(listOf(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)), asked)
        // On the 19th the fixture's "tomorrow" row (999 words) is today, and 12 November left the window.
        assertEquals(999, next.week.todayWords)
        assertEquals(LocalDate.of(2026, 11, 13), next.week.last7Days.first().first)
    }
}
