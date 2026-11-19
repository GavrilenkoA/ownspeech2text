package app.murmur.feature.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.CustomModelRequest
import app.murmur.core.catalog.GgufTemplates
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.Origin
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.InstallState
import app.murmur.core.store.StorageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "Add a model" prompt format choice (ADR-046): Auto = from the GGUF; else an explicit profile. */
enum class PromptFormat { AUTO, FINETUNE, STANDARD }

/** The "Add a model" sheet (no frame). [preview] = the resolved entry shown before saving. */
data class AddUi(
    val kind: ModelKind,
    val repo: String = "",
    val revision: String = "",
    val file: String = "",
    val format: PromptFormat = PromptFormat.AUTO,
    val busy: Boolean = false,
    val error: MurmurError? = null,
    /** The repo field is not "owner/name" (checked before any network call). */
    val repoInvalid: Boolean = false,
    val preview: ModelEntry? = null,
)

/** A pending confirmation: delete [model], opened from [origin]. [inUse] = deleting switches the model of record. */
data class DeleteConfirm(val model: ModelId, val name: String, val origin: Surface, val inUse: Boolean, val support: Boolean, val custom: Boolean)

/**
 * State and actions of the hub (DESIGN 6.4) over [ModelsDeps]. Plain class with an injected [scope] so that JVM tests
 * drive it with a test dispatcher; [ModelsViewModel] owns one per screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsController(private val deps: ModelsDeps, private val scope: CoroutineScope) {

    /** Bumped after events that change [app.murmur.core.catalog.UnitHealth] (it has no flow): a finished benchmark. */
    private val healthTick = MutableStateFlow(0)

    private val catalog = deps.catalog.catalog

    private val states: Flow<Map<ArtifactId, InstallState>> = catalog
        .map { c -> c.models.flatMap { m -> m.artifacts.map { it.id } }.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> deps.installed.state(id).map { id to it } }) { it.toMap() }
        }

    private val records: Flow<Map<ArtifactId, List<BenchmarkRecord>>> = catalog
        .map { c -> c.models.flatMap { m -> m.artifacts.map { it.id } }.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> deps.benchmarks.latest(id).map { id to it } }) { it.toMap() }
        }

    /** Free space and model bytes: re-read whenever the set of installed or stalled artifacts changes. */
    private val storage: Flow<StorageInfo?> = states
        .map { m -> m.filterValues { it is InstallState.Installed || it is InstallState.Paused || it is InstallState.Failed }.keys }
        .distinctUntilChanged()
        .mapLatest { runCatching { deps.installed.storage() }.getOrNull() }
        .onStart { emit(null) }

    /** The snapshot every view is computed from; null until the first values arrive. */
    val input: StateFlow<HubInput?> = combine(
        catalog, states, deps.settings.settings, deps.device, records, storage, healthTick,
    ) { a ->
        @Suppress("UNCHECKED_CAST")
        HubInput(
            catalog = a[0] as app.murmur.core.catalog.Catalog,
            states = a[1] as Map<ArtifactId, InstallState>,
            settings = a[2] as app.murmur.core.store.Settings,
            device = a[3] as app.murmur.core.catalog.DeviceProfile?,
            health = deps.health,
            records = a[4] as Map<ArtifactId, List<BenchmarkRecord>>,
            storage = a[5] as StorageInfo?,
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _tab = MutableStateFlow(HubTab.SPEECH)
    val tab: StateFlow<HubTab> = _tab.asStateFlow()

    private val _bench = MutableStateFlow<BenchUi?>(null)
    val bench: StateFlow<BenchUi?> = _bench.asStateFlow()
    private var benchJob: Job? = null

    private val _add = MutableStateFlow<AddUi?>(null)
    val add: StateFlow<AddUi?> = _add.asStateFlow()

    private val _delete = MutableStateFlow<DeleteConfirm?>(null)
    val delete: StateFlow<DeleteConfirm?> = _delete.asStateFlow()

    init {
        // The device profile flow is null until someone probed (ARCHITECTURE 8.4); ensure() never throws.
        val ensure = deps.ensureDevice
        if (deps.device.value == null && ensure != null) scope.launch { runCatching { ensure() } }
    }

    fun selectTab(t: HubTab) { _tab.value = t }

    private fun entry(id: ModelId): ModelEntry? = input.value?.catalog?.model(id)

    // ------------------------------------------------------------------ selection

    /** Makes [id] the speech model / cleanup model of record. Ignored unless it is installed and selectable. */
    fun select(id: ModelId) {
        val inp = input.value ?: return
        val e = inp.catalog.model(id) ?: return
        if (!HubLogic.row(e, inp).selectable) return
        scope.launch {
            deps.settings.update { s ->
                when (e.kind) {
                    ModelKind.ASR -> s.copy(asrModel = e.id)
                    ModelKind.LLM -> s.copy(cleanup = CleanupChoice.Model(e.id))
                    else -> s
                }
            }
        }
    }

    /** "None" in Cleanup (ADR-033): insert the transcript as recognised. */
    fun selectNone() { scope.launch { deps.settings.update { it.copy(cleanup = CleanupChoice.None) } } }

    /** Unit picker: [unit] null = Auto (the key is removed), else pinned; stored per model id (Settings.unitPreference). */
    fun pinUnit(id: ModelId, unit: ComputeUnit?) {
        scope.launch {
            deps.settings.update { s ->
                val prefs = if (unit == null) s.unitPreference - id.value else s.unitPreference + (id.value to UnitPreference.Pinned(unit))
                s.copy(unitPreference = prefs)
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    /** Queues the model's artifact (+ VAD / OCR pair by the download rules); [artifact] forces one (pinned, CPU version). */
    fun download(id: ModelId, artifact: Artifact? = null) {
        val inp = input.value ?: return
        val e = inp.catalog.model(id) ?: return
        val plan = HubLogic.downloadPlan(e, inp, artifact)
        scope.launch { for (a in plan) deps.installed.download(a, inp.settings.wifiOnlyDownloads) }
    }

    fun pause(id: ModelId) {
        val inp = input.value ?: return
        val e = inp.catalog.model(id) ?: return
        val active = e.artifacts.filter { val s = inp.state(it.id); s is InstallState.Downloading || s is InstallState.Queued }
        scope.launch { for (a in active) deps.installed.pause(a.id) }
    }

    /** Resume a paused download, or download again after a failure (checksum mismatch: "Download again"). */
    fun resume(id: ModelId) {
        val inp = input.value ?: return
        val e = inp.catalog.model(id) ?: return
        val stalled = e.artifacts.filter { val s = inp.state(it.id); s is InstallState.Paused || s is InstallState.Failed }
        scope.launch { for (a in stalled) deps.installed.download(a.id, inp.settings.wifiOnlyDownloads) }
    }

    // ------------------------------------------------------------------ delete

    /** Delete from [origin]: asks first when the model is in use, a support model, or a custom entry; else deletes. */
    fun requestDelete(id: ModelId, origin: Surface) {
        val inp = input.value ?: return
        val e = inp.catalog.model(id) ?: return
        val row = HubLogic.row(e, inp)
        val support = e.kind != ModelKind.ASR && e.kind != ModelKind.LLM
        val inUse = row.selected && row.state == RowState.InUse
        val custom = e.origin == Origin.CUSTOM
        if (inUse || support || custom) _delete.value = DeleteConfirm(id, e.displayName, origin, inUse, support, custom)
        else performDelete(e, inp)
    }

    fun confirmDelete() {
        val c = _delete.value ?: return
        _delete.value = null
        val inp = input.value ?: return
        val e = inp.catalog.model(c.model) ?: return
        performDelete(e, inp)
    }

    fun cancelDelete() { _delete.value = null }

    private fun performDelete(e: ModelEntry, inp: HubInput) {
        val selected = HubLogic.isSelected(e, inp)
        val next = if (selected) HubLogic.nextAfterDelete(e, inp) else null
        val targets = e.artifacts.filter { inp.state(it.id) !is InstallState.NotInstalled }
        scope.launch {
            for (a in targets) deps.installed.delete(a.id)
            if (selected) {
                deps.settings.update { s ->
                    when (e.kind) {
                        // DESIGN 6.4: switch to the next downloaded model; Speech without one has no model of record.
                        ModelKind.ASR -> s.copy(asrModel = next)
                        ModelKind.LLM -> s.copy(cleanup = next?.let { CleanupChoice.Model(it) } ?: CleanupChoice.None)
                        else -> s
                    }
                }
            }
            if (e.origin == Origin.CUSTOM) deps.catalog.removeCustom(e.id)
        }
    }

    // ------------------------------------------------------------------ benchmark

    /** "Run benchmark": every installed candidate of the model (the runner measures usable + failed units). */
    fun runBenchmark(id: ModelId, origin: Surface) {
        val inp = input.value ?: return
        val e = inp.catalog.model(id) ?: return
        val candidates = HubLogic.benchCandidates(e, inp)
        if (candidates.isEmpty()) return
        benchJob?.cancel()
        _bench.value = BenchUi(e.id, e.displayName, e.kind, origin)
        benchJob = scope.launch {
            try {
                deps.runner.run(e, candidates).collect { ev -> _bench.update { s -> s?.let { BenchReducer.reduce(it, ev) } } }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _bench.update { it?.copy(phase = BenchUi.Phase.FAILED, error = MurmurError.Internal(t.javaClass.simpleName)) }
            } finally {
                // A valid run clears UnitHealth for its unit (WP-14): re-read the health.
                healthTick.update { it + 1 }
            }
        }
    }

    /** Stop: cancels the run at once (BenchmarkRunner.stop) and closes the sheet. */
    fun stopBenchmark() {
        deps.runner.stop()
        benchJob?.cancel()
        benchJob = null
        _bench.value = null
    }

    fun showBenchUnit(unit: ComputeUnit) { _bench.update { it?.copy(shownUnit = unit) } }

    /** Done / dismiss: a run still going is stopped. */
    fun closeBenchmark() {
        if (_bench.value?.phase == BenchUi.Phase.RUNNING) stopBenchmark() else _bench.value = null
    }

    // ------------------------------------------------------------------ add a model

    fun openAdd(kind: ModelKind) { _add.value = AddUi(kind) }
    fun closeAdd() { _add.value = null }
    fun editAdd(transform: (AddUi) -> AddUi) {
        _add.update { s -> s?.let { transform(it).copy(error = null, repoInvalid = false, preview = null) } }
    }

    /** Back from the preview to the form, keeping what was typed. */
    fun backToForm() { _add.update { it?.copy(preview = null) } }

    fun resolveAdd() {
        val s = _add.value ?: return
        val repo = s.repo.trim().removePrefix("https://huggingface.co/").trim('/')
        if (!REPO.matches(repo)) { _add.value = s.copy(repoInvalid = true); return }
        val request = CustomModelRequest(
            kind = s.kind,
            repo = repo,
            revision = s.revision.trim().ifEmpty { null },
            file = s.file.trim().ifEmpty { null }.takeIf { s.kind == ModelKind.LLM },
            promptProfile = when (s.format) {
                PromptFormat.AUTO -> null
                PromptFormat.FINETUNE -> GgufTemplates.FINETUNE_PROFILE
                PromptFormat.STANDARD -> GgufTemplates.STOCK_PROFILE
            }.takeIf { s.kind == ModelKind.LLM },
        )
        _add.value = s.copy(busy = true, error = null, repoInvalid = false, preview = null)
        scope.launch {
            val r = try { deps.custom.resolve(request) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                Outcome.Err(MurmurError.Internal(t.javaClass.simpleName))
            }
            _add.update { cur ->
                cur?.let {
                    when (r) {
                        is Outcome.Ok -> it.copy(busy = false, preview = r.value)
                        is Outcome.Err -> it.copy(busy = false, error = r.error)
                    }
                }
            }
        }
    }

    /** Saves the previewed entry (it behaves like any catalog model from now on) and shows its tab. */
    fun saveAdd() {
        val s = _add.value ?: return
        val entry = s.preview ?: return
        _add.value = null
        _tab.value = if (entry.kind == ModelKind.LLM) HubTab.CLEANUP else HubTab.SPEECH
        scope.launch { deps.catalog.addCustom(entry) }
    }

    private companion object {
        val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}
