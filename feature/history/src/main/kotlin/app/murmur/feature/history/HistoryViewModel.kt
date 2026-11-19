package app.murmur.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmur.core.model.EntryId
import app.murmur.core.model.Outcome
import app.murmur.core.store.AudioRef
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.RetentionPolicy
import app.murmur.ui.design.AppInfoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * History list, search, selection, delete-with-undo and the entry detail (DESIGN 6.3). One instance serves both
 * panes (list and detail) so that a delete made in the detail shows its Undo snackbar on the list.
 *
 * Days: rows are grouped by the entry's stored local day (the day retention deletes by, ARCHITECTURE 12); "today"
 * follows [HistoryDeps.clock] and is re-read every minute and at local midnight, so the labels move across midnight and
 * follow a time-zone change while the screen is shown. Times are shown in the current zone.
 *
 * Delete: [delete] soft-deletes at once ([app.murmur.core.store.HistoryRepository.markDeleted]); after the undo
 * window the audio files are deleted, then the rows ([commitDeletes][app.murmur.core.store.HistoryRepository.commitDeletes],
 * audio first as in the Room store). A new delete commits the previous one first. If the view model is cleared
 * inside the window, the soft-deleted rows are committed by the retention run at the next app start (WP-12).
 *
 * [compute] runs the waveform-peak computation (CPU).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val deps: HistoryDeps,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    // ------------------------------------------------------------------ inputs

    private val reload = MutableStateFlow(0)
    private val selection = MutableStateFlow<Set<EntryId>>(emptySet())
    private val pending = MutableStateFlow<PendingDelete?>(null)
    private val searchInput = MutableStateFlow<SearchInput?>(null)
    private val openId = MutableStateFlow<EntryId?>(null)
    private val busy = MutableStateFlow<Map<EntryId, Busy>>(emptyMap())
    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)

    /** Snackbar-worthy results of Retry / Re-polish. */
    val events: Flow<HistoryEvent> = eventChannel.receiveAsFlow()

    private data class SearchInput(val query: String, val pkg: String?)
    private data class Now(val today: LocalDate, val zone: ZoneId)
    private sealed interface Load {
        data object Loading : Load
        data object Failed : Load
        data class Ok(val entries: List<HistoryEntry>) : Load
    }

    /** Every entry seen, by id: the audio refs a delete must remove after the rows have disappeared from the flow. */
    private val known = HashMap<EntryId, HistoryEntry>()
    private val deleteLock = Mutex()
    private var commitJob: Job? = null
    private var pendingAudio: List<AudioRef> = emptyList()
    private var serial = 0L

    private val now: Flow<Now> = flow {
        while (true) {
            val instant = deps.clock.now()
            val zone = deps.clock.zone()
            val today = LocalDate.ofInstant(instant, zone)
            emit(Now(today, zone))
            val untilMidnight = Duration.between(instant, today.plusDays(1).atStartOfDay(zone).toInstant()).toMillis()
            delay(untilMidnight.coerceIn(1, TICK_MS))
        }
    }.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    private val retention: Flow<Int> = deps.settings.settings.map { it.retentionDays }.distinctUntilChanged()

    private val entries: Flow<Load> = reload.flatMapLatest {
        deps.history.entries()
            .map<List<HistoryEntry>, Load> { list -> remember(list); Load.Ok(list) }
            .onStart { emit(Load.Loading) }
            .catch { e -> if (e is CancellationException) throw e; emit(Load.Failed) }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    private val searchResults: Flow<Pair<SearchInput, List<HistoryEntry>?>?> = searchInput.flatMapLatest { input ->
        when {
            input == null -> flowOf(null)
            input.query.isBlank() && input.pkg == null -> flowOf(input to null)
            else -> deps.history.search(input.query.trim(), input.pkg)
                .map<List<HistoryEntry>, Pair<SearchInput, List<HistoryEntry>?>> { remember(it); input to it }
                .onStart { emit(input to null) }
                .catch { e -> if (e is CancellationException) throw e; emit(input to emptyList()) }
        }
    }

    private val listBase: Flow<HistoryUiState> = combine(entries, now, retention, selection, pending) { load, n, days, sel, p ->
        val mode = when {
            days <= 0 -> ListMode.OFF
            load is Load.Loading -> ListMode.LOADING
            load is Load.Failed -> ListMode.ERROR
            (load as Load.Ok).entries.isEmpty() -> ListMode.EMPTY
            else -> ListMode.LIST
        }
        val list = (load as? Load.Ok)?.entries.orEmpty()
        HistoryUiState(
            mode = mode,
            retentionDays = days,
            today = n.today,
            days = if (mode == ListMode.LIST) group(list, n, days, null) else emptyList(),
            selection = sel.filterTo(LinkedHashSet()) { id -> list.any { it.id == id } },
            pendingDelete = p,
        )
    }

    /** The list pane state. */
    val state: StateFlow<HistoryUiState> = combine(listBase, searchResults, entries, now) { base, search, load, n ->
        if (search == null) base else {
            val (input, results) = search
            val all = (load as? Load.Ok)?.entries.orEmpty()
            val shown = results ?: if (!input.active()) all else null
            base.copy(
                search = SearchUiState(
                    query = input.query,
                    pkg = input.pkg,
                    apps = appChips(all),
                    results = shown?.let { group(it, n, base.retentionDays, input.query.takeIf { q -> q.isNotBlank() }) },
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    /** The open entry's detail, null when none is open. */
    val detail: StateFlow<DetailUiState?> = openId.flatMapLatest { id -> if (id == null) flowOf(null) else detailOf(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** App icons and labels for the rows (the shell's PackageManager-backed provider). */
    val apps: AppInfoProvider get() = deps.apps

    // ------------------------------------------------------------------ list actions

    /** "Couldn't open history" → Retry. */
    fun retryLoad() { reload.value++ }

    fun openSearch() { if (searchInput.value == null) searchInput.value = SearchInput("", null) }

    fun closeSearch() { searchInput.value = null }

    fun setQuery(query: String) { searchInput.value = (searchInput.value ?: SearchInput("", null)).copy(query = query) }

    /** App filter chip; null = "All apps". */
    fun setAppFilter(pkg: String?) { searchInput.value = (searchInput.value ?: SearchInput("", null)).copy(pkg = pkg) }

    /** Long-press starts multi-select; taps while selecting toggle. */
    fun toggleSelection(id: EntryId) { selection.value = if (id in selection.value) selection.value - id else selection.value + id }

    fun clearSelection() { selection.value = emptySet() }

    /** Final texts of [ids], newest first, one per paragraph (multi-select Copy and Share). Failed entries are skipped. */
    fun textsOf(ids: Set<EntryId>): String =
        ids.mapNotNull { known[it] }.sortedByDescending { it.createdAt }.mapNotNull { it.finalText?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")

    /** The label used in the delete dialog ("Text already pasted into Telegram stays there"). */
    fun appNameOf(id: EntryId): String? = known[id]?.let { appName(it) }

    // ------------------------------------------------------------------ delete + undo

    /**
     * Soft-deletes [ids] now and commits them (audio first, then rows) after [undoWindowMs] unless [undo] is called.
     * Any earlier pending delete is committed first. Returns the job that performs the soft delete.
     */
    fun delete(ids: Set<EntryId>, undoWindowMs: Long = UNDO_WINDOW_MS): Job = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        deleteLock.withLock {
            commitJob?.cancel()
            commitPendingLocked()
            val audio = ids.mapNotNull { known[it]?.audio }
            deps.history.markDeleted(ids)
            pendingAudio = audio
            val p = PendingDelete(ids, ++serial)
            pending.value = p
            selection.value = selection.value - ids
            commitJob = viewModelScope.launch {
                delay(undoWindowMs)
                deleteLock.withLock { if (pending.value == p) commitPendingLocked() }
            }
        }
    }

    /** Snackbar Undo: restores the pending delete. */
    fun undo(): Job = viewModelScope.launch {
        deleteLock.withLock {
            val p = pending.value ?: return@withLock
            commitJob?.cancel()
            deps.history.restore(p.ids)
            pending.value = null
            pendingAudio = emptyList()
        }
    }

    /** Commits the pending delete now (the snackbar went away early, e.g. replaced by another one). */
    fun commitPending(): Job = viewModelScope.launch {
        deleteLock.withLock {
            commitJob?.cancel()
            commitPendingLocked()
        }
    }

    private suspend fun commitPendingLocked() {
        if (pending.value == null) return
        for (ref in pendingAudio) {
            try {
                deps.audio.delete(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left for the orphan sweep at the next app start (WP-12); the rows still go.
            }
        }
        deps.history.commitDeletes()
        pendingAudio = emptyList()
        pending.value = null
    }

    // ------------------------------------------------------------------ detail

    /** Shows [id] in the detail pane (the detail composable calls this). */
    fun open(id: EntryId?) { openId.value = id }

    /** "Try again" for a Failed entry: re-transcribes the stored recording (ADR-064; the entry is updated in place). */
    fun retry(id: EntryId): Job = runBusy(id, Busy.RETRY) {
        when (val r = deps.retry(id)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> eventChannel.send(HistoryEvent.RetryFailed(id, r.error))
        }
    }

    /** "Re-polish": re-runs cleanup with the current prompt, tone and model; Original stays. */
    fun repolish(id: EntryId): Job = runBusy(id, Busy.REPOLISH) {
        when (val r = deps.repolish(id)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> eventChannel.send(HistoryEvent.RepolishFailed(id, r.error))
        }
    }

    private fun runBusy(id: EntryId, kind: Busy, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (busy.value[id] != null) return@launch
        busy.value = busy.value + (id to kind)
        try {
            block()
        } finally {
            busy.value = busy.value - id
        }
    }

    private fun detailOf(id: EntryId): Flow<DetailUiState> {
        val entry = deps.history.entry(id).map { e -> e?.also { remember(listOf(it)) } }
        val audio = entry.map { it?.audio }.distinctUntilChanged().flatMapLatest { ref -> audioOf(ref) }
        return combine(entry, audio, now, retention, busy) { e, a, n, days, b ->
            if (e == null) return@combine DetailUiState(id, loading = false, missing = true)
            val created = LocalDateTime.ofInstant(e.createdAt, n.zone)
            DetailUiState(
                id = id,
                loading = false,
                entry = e,
                appName = appName(e),
                title = e.app.conversation?.takeIf { it.isNotBlank() } ?: appName(e),
                created = created,
                dayKind = dayKind(e.localDay, n.today),
                durationMs = e.audio?.durationMs ?: e.timings.recordMs.takeIf { it > 0 },
                audio = a,
                deletesAt = if (days > 0) RetentionPolicy.deletesAt(e.localDay, days, n.zone) else null,
                diff = diffOf(e),
                models = modelsOf(e),
                busy = b[id],
            )
        }
    }

    private fun audioOf(ref: AudioRef?): Flow<AudioUi> = flow {
        if (ref == null) { emit(AudioUi.Unavailable); return@flow }
        emit(AudioUi.Loading)
        val pcm = try {
            if (deps.audio.exists(ref)) deps.audio.readPcm(ref) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (pcm == null || pcm.isEmpty()) { emit(AudioUi.Unavailable); return@flow }
        val peaks = withContext(compute) { Peaks.of(pcm) }
        emit(AudioUi.Ready(peaks, pcm, pcm.size * 1000L / SAMPLE_RATE))
    }

    private fun diffOf(e: HistoryEntry): List<DiffSpan> {
        val raw = e.rawText?.takeIf { it.isNotBlank() } ?: return emptyList()
        val fin = e.finalText?.takeIf { it.isNotBlank() } ?: return emptyList()
        return WordDiff.diff(Highlight.nfc(raw), Highlight.nfc(fin))
    }

    private fun modelsOf(e: HistoryEntry): List<ModelLine> = listOfNotNull(e.asr, e.cleanup?.model).map {
        ModelLine(deps.modelName(it.modelId) ?: it.modelId.value, it.unit)
    }

    // ------------------------------------------------------------------ grouping

    private fun remember(list: List<HistoryEntry>) { for (e in list) known[e.id] = e }

    private fun appName(e: HistoryEntry): String = deps.apps.label(e.app.pkg) ?: e.app.label?.takeIf { it.isNotBlank() } ?: e.app.pkg

    private fun appChips(all: List<HistoryEntry>): List<AppChip> =
        all.groupBy { it.app.pkg }.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<HistoryEntry>>> { it.value.size }
                    .thenByDescending { e -> e.value.maxOf { it.createdAt } },
            )
            .map { AppChip(it.key, appName(it.value.first())) }

    private fun group(list: List<HistoryEntry>, n: Now, days: Int, query: String?): List<DayGroup> =
        list.sortedByDescending { it.createdAt }
            .groupBy { it.localDay }
            .toSortedMap(compareByDescending { it })
            .map { (day, items) ->
                DayGroup(
                    day = day,
                    kind = dayKind(day, n.today),
                    deletesTonight = RetentionPolicy.deletesTonight(day, n.today, days),
                    rows = items.map { row(it, n.zone, query) },
                )
            }

    private fun row(e: HistoryEntry, zone: ZoneId, query: String?): HistoryRow {
        val failed = e.status == EntryStatus.FAILED
        val final = e.finalText?.takeIf { it.isNotBlank() }?.let(Highlight::nfc)
        var text: String? = if (failed) null else final ?: e.rawText?.let(Highlight::nfc)
        var ranges = emptyList<IntRange>()
        var match: MatchIn? = null
        if (query != null) {
            val inFinal = final?.let { Highlight.ranges(it, query) }.orEmpty()
            if (inFinal.isNotEmpty()) {
                text = final; ranges = inFinal; match = MatchIn.FINAL
            } else {
                val raw = e.rawText?.let(Highlight::nfc)
                val inRaw = raw?.let { Highlight.ranges(it, query) }.orEmpty()
                if (raw != null && inRaw.isNotEmpty()) { text = raw; ranges = inRaw; match = MatchIn.ORIGINAL }
            }
            if (text != null && ranges.isNotEmpty()) Highlight.excerpt(text, ranges).let { (t, r) -> text = t; ranges = r }
        }
        return HistoryRow(
            entry = e,
            appName = appName(e),
            conversation = e.app.conversation?.takeIf { it.isNotBlank() },
            time = LocalDateTime.ofInstant(e.createdAt, zone),
            text = text,
            failure = if (failed && match == null) failureKind(e.failureCode) else null,
            audioKept = e.audio != null,
            highlights = ranges,
            matchIn = match,
        )
    }

    private fun SearchInput.active() = query.isNotBlank() || pkg != null

    companion object {
        /** DESIGN 4: a snackbar with an action stays 6 s. */
        const val UNDO_WINDOW_MS = 6_000L
        /** Re-read the clock this often (midnight and time-zone changes while the screen is shown). */
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
        private const val SAMPLE_RATE = 16_000

        internal fun dayKind(day: LocalDate, today: LocalDate): DayKind = when (day) {
            today -> DayKind.TODAY
            today.minusDays(1) -> DayKind.YESTERDAY
            else -> DayKind.DATE
        }
    }
}
