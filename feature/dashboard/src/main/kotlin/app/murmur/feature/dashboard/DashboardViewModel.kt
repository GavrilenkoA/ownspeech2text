package app.murmur.feature.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.DashboardStats
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.EntryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate

/** The range chip (DESIGN 6.2). History keeps 7 days, so these are the only two. */
enum class DashboardRange { Week, Today }

sealed interface DashboardState {
    /** Before the first metrics read: skeleton (dash-loading). */
    data object Loading : DashboardState

    /** The stats could not be read: "Couldn't read your stats. History is not affected." + Retry. */
    data object Error : DashboardState

    /**
     * Numbers to show. [week] = the 7 days ending [today] (Today card and chart); [ranged] = the chip's range (the
     * metrics, languages and apps), equal to [week] for [DashboardRange.Week]. [copiedInTopApp] = how many of the
     * range's copy-only dictations happened in [DashboardStats.copiedOnlyTopApp] ("4 copied only, all in Termux").
     * [empty] = no delivered dictation in the 7 days (dash-empty).
     */
    data class Ready(
        val today: LocalDate,
        val week: DashboardStats,
        val ranged: DashboardStats,
        val range: DashboardRange,
        val copiedInTopApp: Int,
        val typingWpm: Int,
        val empty: Boolean,
    ) : DashboardState
}

/**
 * Dashboard view model (WP-17): `metrics.since(today − 6)` → `StatsCalculator.compute` → [DashboardState]. "Today"
 * follows [MurmurClock]: the view model moves to the next day at local midnight while it is observed. A failing read
 * shows [DashboardState.Error]; [retry] subscribes again. Numbers only: nothing here sees user text (D9).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val deps: DashboardDeps,
    /** Tests pass their own scope; production uses [viewModelScope]. */
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val rangeFlow = MutableStateFlow(DashboardRange.Week)
    private val attempt = MutableStateFlow(0)

    val range: StateFlow<DashboardRange> = rangeFlow.asStateFlow()

    val typingWpm: StateFlow<Int> = deps.settings.settings.map { it.typingWpm }
        .stateIn(this.scope, SharingStarted.Eagerly, deps.settings.settings.value.typingWpm)

    val state: StateFlow<DashboardState> = attempt.flatMapLatest {
        localDays(deps.clock).flatMapLatest { today ->
            combine(
                deps.metrics.since(today.minusDays((DefaultStatsCalculator.WINDOW_DAYS - 1).toLong())),
                typingWpm,
                rangeFlow,
            ) { rows, wpm, range -> build(rows, today, wpm, range) }
        }
            .onStart<DashboardState> { emit(DashboardState.Loading) }
            .catch { e ->
                // D9: the exception type only, never a message that could carry a path or text.
                Log.w(TAG, "stats read failed: ${e.javaClass.simpleName}")
                emit(DashboardState.Error)
            }
    }.stateIn(this.scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardState.Loading)

    fun setRange(range: DashboardRange) {
        rangeFlow.value = range
    }

    fun retry() {
        attempt.value++
    }

    fun setTypingWpm(wpm: Int) {
        val v = wpm.coerceIn(MIN_WPM, MAX_WPM)
        scope.launch { deps.settings.update { it.copy(typingWpm = v) } }
    }

    private fun build(rows: List<DictationMetrics>, today: LocalDate, wpm: Int, range: DashboardRange): DashboardState.Ready {
        val first = today.minusDays((DefaultStatsCalculator.WINDOW_DAYS - 1).toLong())
        val window = rows.filter { !it.localDay.isBefore(first) && !it.localDay.isAfter(today) }
        val week = deps.stats.compute(window, today, wpm)
        val rangeRows = if (range == DashboardRange.Week) window else window.filter { it.localDay == today }
        val ranged = if (range == DashboardRange.Week) week else deps.stats.compute(rangeRows, today, wpm)
        val top = ranged.copiedOnlyTopApp
        val inTop = if (top == null) 0 else rangeRows.count { it.status == EntryStatus.COPIED && it.pkg == top }
        val delivered = window.any { it.status == EntryStatus.INSERTED || it.status == EntryStatus.COPIED }
        return DashboardState.Ready(today, week, ranged, range, inTop, wpm, empty = !delivered)
    }

    companion object {
        private const val TAG = "Dashboard"
        const val STOP_TIMEOUT_MS = 5_000L
        /** Typing speed presets of the sheet (DESIGN 6.2) and the custom bounds. */
        val PRESETS = listOf(30, 40, 50, 60)
        const val MIN_WPM = 10
        const val MAX_WPM = 200

        /** The local day of [clock], re-emitted just after each local midnight. */
        fun localDays(clock: MurmurClock): Flow<LocalDate> = flow {
            while (true) {
                val now = clock.now()
                val zone = clock.zone()
                val day = now.atZone(zone).toLocalDate()
                emit(day)
                val next = day.plusDays(1).atStartOfDay(zone).toInstant()
                delay(Duration.between(now, next).toMillis().coerceAtLeast(0) + MIDNIGHT_SLACK_MS)
            }
        }.distinctUntilChanged()

        private const val MIDNIGHT_SLACK_MS = 50L
    }
}
