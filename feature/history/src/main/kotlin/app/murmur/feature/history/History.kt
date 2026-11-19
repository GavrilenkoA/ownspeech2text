package app.murmur.feature.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.core.model.EntryId
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.Outcome
import app.murmur.core.store.AudioStore
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.ui.design.AppInfoProvider
import java.time.LocalDate

/** What :app passes in (ADR-010: constructor injection, no DI framework). */
class HistoryDeps(
    val history: HistoryRepository,
    val settings: SettingsRepository,
    val audio: AudioStore,
    val clock: MurmurClock,
    /** DictationOrchestrator.retry / repolish. */
    val retry: suspend (EntryId) -> Outcome<EntryId>,
    val repolish: suspend (EntryId) -> Outcome<EntryId>,
    /** Row and detail app icons + labels (PackageManager lives in :app). */
    val apps: AppInfoProvider,
)

/** DESIGN 6.3 list state. */
data class HistoryUiState(
    val retentionDays: Int = 7,
    val days: List<DayGroup> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val query: String? = null,
    val selection: Set<EntryId> = emptySet(),
)

data class DayGroup(val day: LocalDate, val deletesTonight: Boolean, val entries: List<HistoryEntry>)

/**
 * Entry point used by the app shell's navigation. WP-16 builds the real screen + ViewModel. [selected] = the entry shown
 * in the detail pane next to the list on the inner display (DESIGN 6.3: the selected row is filled accentSoft); null on
 * the cover display and when nothing is selected.
 */
@Composable
fun HistoryScreen(deps: HistoryDeps, onOpenEntry: (EntryId) -> Unit, modifier: Modifier = Modifier, selected: EntryId? = null) {
    Box(modifier.fillMaxSize().testTag("screen-history")) { Text("History") }
}

/**
 * Entry detail (DESIGN 6.3 hist-detail), hosted by the app shell: a pushed screen on the cover display ([onBack] = the
 * top bar's back arrow), the right pane of the list-detail scene on the inner display ([onBack] = null, no back arrow).
 * WP-16 builds it.
 */
@Composable
fun HistoryEntryDetail(deps: HistoryDeps, id: EntryId, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("screen-history-entry")) { Text("History entry") }
}
