package app.murmur.feature.models

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.murmur.core.benchmark.BenchmarkRunner
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.CustomModelResolver
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.UnitHealth
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Model hub (DESIGN 6.4, D2/D3). Everything shown comes from the catalog entry + install state + benchmark
 * records: no model-specific code in this module (`NoModelLiteralsTest` scans the sources).
 */
class ModelsDeps(
    val catalog: CatalogRepository,
    val installed: InstalledModelsRepository,
    val benchmarks: BenchmarkRepository,
    val runner: BenchmarkRunner,
    val settings: SettingsRepository,
    val custom: CustomModelResolver,
    val device: StateFlow<DeviceProfile?>,
    /** Units that failed to load on this phone ("Failed to load on NPU, using CPU"; Blocker.UNIT_FAILED). */
    val health: UnitHealth,
    /**
     * `DeviceProfileRepository.ensure` (never throws), e.g. `deviceProfile::ensure`. Called once when [device] is still
     * null: until the profile is known the hub shows its rows without controls (nothing to download, select or delete),
     * so it must not wait for someone else to probe (first visit right after onboarding).
     */
    val ensureDevice: suspend () -> DeviceProfile,
    /** "Device" row of the benchmark result (the phone's model name; RAM comes from the device profile). */
    val deviceName: String = Build.MODEL ?: "",
    /** Wall clock for "Last run today, 14:05". */
    val now: () -> Long = System::currentTimeMillis,
)

/** One controller per screen instance, kept across configuration changes (fold/unfold keeps sheets and drafts). */
internal class ModelsViewModel(deps: ModelsDeps) : ViewModel() {
    val controller = ModelsController(deps, viewModelScope)
}

@Composable
internal fun rememberModelsController(deps: ModelsDeps, surface: Surface): ModelsController =
    viewModel(key = "models-hub-${surface.name}-${System.identityHashCode(deps)}") { ModelsViewModel(deps) }.controller

/**
 * The model list (DESIGN 6.4 `models`, `models-llm`, `models-storage`). [onOpenModel] opens the detail (pushed on the
 * cover display, the right pane on the inner one). [selected] = the model shown in the detail pane on the inner
 * display (its row is highlighted), else null. [segment] = a one-shot request from a deep link (the bubble's "Open
 * Models" for a cleanup problem asks for [ModelKind.LLM], every other link for [ModelKind.ASR]): when it is non-null
 * the hub switches to that segment (Speech = ASR, Cleanup = LLM) and then calls [onSegmentShown], which clears the
 * request; null keeps the user's own choice (ADR-087).
 */
@Composable
fun ModelsScreen(
    deps: ModelsDeps,
    onOpenModel: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
    selected: ModelId? = null,
    segment: ModelKind? = null,
    onSegmentShown: () -> Unit = {},
) {
    val controller = rememberModelsController(deps, Surface.LIST)
    LaunchedEffect(segment) {
        if (segment != null) {
            controller.selectTab(if (segment == ModelKind.LLM) HubTab.CLEANUP else HubTab.SPEECH)
            onSegmentShown()
        }
    }
    ModelsListScreen(controller, deps, onOpenModel, selected, modifier)
}

/**
 * Model detail (DESIGN 6.4 `model-detail`), hosted by the app shell with the same convention as the History detail:
 * the pushed screen on the cover display ([pane] false: top bar with the back arrow, which calls [onClose]), the right
 * pane next to the 316 dp list on the inner display ([pane] true: no bar). [onClose] is also called when the model
 * disappears from the catalog (a deleted custom model), so the shell never shows an empty page. It hosts the benchmark
 * sheet.
 */
@Composable
fun ModelDetail(deps: ModelsDeps, id: ModelId, onClose: () -> Unit, modifier: Modifier = Modifier, pane: Boolean = false) {
    ModelDetailScreen(rememberModelsController(deps, Surface.DETAIL), deps, id, onClose, pane, modifier)
}
