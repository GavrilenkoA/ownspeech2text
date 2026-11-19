package app.murmur.feature.models

import android.os.Build
import androidx.compose.runtime.Composable
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
     * `DeviceProfileRepository.ensure` (never throws). Called once when [device] is still null, so that the hub can
     * decide units before anything else probed. Null = wait for someone else to probe.
     */
    val ensureDevice: (suspend () -> DeviceProfile)? = null,
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
 * display (its row is highlighted), else null.
 */
@Composable
fun ModelsScreen(deps: ModelsDeps, onOpenModel: (ModelId) -> Unit, modifier: Modifier = Modifier, selected: ModelId? = null) {
    ModelsListScreen(rememberModelsController(deps, Surface.LIST), deps, onOpenModel, selected, modifier)
}

/**
 * Model detail (DESIGN 6.4 `model-detail`), hosted by the app shell: pushed on the cover display ([onBack] = back
 * arrow), the right pane next to the 316 dp list on the inner display ([onBack] = null). It hosts the benchmark sheet.
 */
@Composable
fun ModelDetail(deps: ModelsDeps, id: ModelId, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    ModelDetailScreen(rememberModelsController(deps, Surface.DETAIL), deps, id, onBack, modifier)
}
