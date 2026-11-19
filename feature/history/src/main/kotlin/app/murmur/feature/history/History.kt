package app.murmur.feature.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
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
    /**
     * Display name of a model for the "Context used → Models" row; :app answers from the catalog
     * (`CatalogRepository.catalog.value`, custom models included). null = the model id is shown.
     */
    val modelName: (ModelId) -> String? = { null },
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
 * The History tab's list pane (DESIGN 6.3). Same signature as WP-16's entry point (wp/wp-16), whose implementation
 * replaces this skeleton at the merge. [selected] marks the entry shown in the detail pane (inner display);
 * [onTryDictation] backs the empty state's "Try a dictation" button (hidden when null).
 */
@Composable
fun HistoryScreen(
    deps: HistoryDeps,
    onOpenEntry: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
    selected: EntryId? = null,
    onTryDictation: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().testTag("screen-history")) { Text("History") }
}

/**
 * One entry (DESIGN 6.3 detail), WP-16's signature: the pushed screen on the cover display ([pane] false: back arrow)
 * or the detail pane of the list-detail layout ([pane] true: no back arrow). [onClose] = Back, and after a delete.
 */
@Composable
fun HistoryEntryDetail(
    deps: HistoryDeps,
    id: EntryId,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pane: Boolean = false,
) {
    Box(modifier.fillMaxSize().testTag("screen-history-entry")) { Text("History entry") }
}
