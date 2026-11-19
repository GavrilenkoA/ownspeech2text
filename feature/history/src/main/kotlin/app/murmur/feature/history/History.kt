package app.murmur.feature.history

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.Outcome
import app.murmur.core.store.AudioStore
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.feature.history.ui.EntryDetail
import app.murmur.feature.history.ui.HistoryListDetailLayout
import app.murmur.feature.history.ui.HistoryListPane
import app.murmur.ui.design.AppInfoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
     * Display name of a model for the "Context used → Models" row ("Qwen3-ASR 0.6B"); :app answers from the catalog
     * (`CatalogRepository.catalog.value`, custom models included). null = the model id is shown.
     */
    val modelName: (ModelId) -> String? = { null },
    /**
     * Work that must outlive the screen: a delete whose Undo window is still open when the view model is cleared (the
     * user left the screen; the snackbar is gone, so the delete is final) is committed here at once. :app passes
     * `AppContainer.appScope`; the default is this module's own process-lifetime scope.
     */
    val appScope: CoroutineScope = HistoryProcessScope,
)

/** Lives as long as the process (never cancelled), like `AppContainer.appScope`. */
private val HistoryProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The History tab's list pane (DESIGN 6.3): header with search, retention line, day groups, search with app
 * filters, multi-select, the delete Undo snackbar and the empty / off / error states. [onOpenEntry] opens the detail
 * (the shell pushes [HistoryEntryDetail] on the cover display, or shows it in the detail pane on the inner one).
 * [selected] marks the entry shown in the detail pane (inner display). [onTryDictation] backs the empty state's
 * "Try a dictation" button (hidden when null).
 */
@Composable
fun HistoryScreen(
    deps: HistoryDeps,
    onOpenEntry: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
    selected: EntryId? = null,
    onTryDictation: (() -> Unit)? = null,
) {
    HistoryListPane(rememberHistoryViewModel(deps), onOpenEntry, modifier, selected, onTryDictation)
}

/**
 * One entry (DESIGN 6.3 detail), usable as the pushed screen on the cover display ([pane] false: back arrow, bg) or
 * as the detail pane of a list-detail layout ([pane] true: no back arrow, surface). [onClose] is called by Back and
 * after a delete (the Undo snackbar then shows on the list).
 */
@Composable
fun HistoryEntryDetail(
    deps: HistoryDeps,
    id: EntryId,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pane: Boolean = false,
) {
    EntryDetail(rememberHistoryViewModel(deps), id, onClose, modifier, pane)
}

/**
 * Self-contained History tab: list and detail side by side on medium+ widths (list 300 dp), a pushed detail on
 * compact widths; the selected entry is saved (rememberSaveable), so it survives fold/unfold and recreation. For
 * shells that do not run their own list-detail scene. [bottomBar] (the shell's bottom navigation) is drawn under the
 * list on compact widths only, so the pushed detail covers it as DESIGN 6.3 `hist-detail` shows.
 */
@Composable
fun HistoryListDetail(
    deps: HistoryDeps,
    modifier: Modifier = Modifier,
    onTryDictation: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    HistoryListDetailLayout(rememberHistoryViewModel(deps), modifier, onTryDictation, bottomBar)
}

/**
 * The shared [HistoryViewModel]: scoped to the activity (falling back to the nearest owner), so the list pane and the
 * detail pane — separate navigation entries in the shell — see the same selection, search and pending delete.
 */
@Composable
fun rememberHistoryViewModel(deps: HistoryDeps): HistoryViewModel {
    val owner = LocalContext.current.findOwner() ?: checkNotNull(LocalViewModelStoreOwner.current) { "no ViewModelStoreOwner" }
    return viewModel(owner, key = VM_KEY, factory = viewModelFactory { initializer { HistoryViewModel(deps) } })
}

private const val VM_KEY = "app.murmur.feature.history"

private tailrec fun Context.findOwner(): ViewModelStoreOwner? = when (this) {
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.findOwner()
    else -> null
}
