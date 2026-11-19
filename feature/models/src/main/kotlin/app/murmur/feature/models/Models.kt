package app.murmur.feature.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * records: no model-specific code in this module (enforced by review: no ModelId literals).
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
)

/** WP-18. [selected] = the model shown in the detail pane on the inner display (its row is highlighted), else null. */
@Composable
fun ModelsScreen(deps: ModelsDeps, onOpenModel: (ModelId) -> Unit, modifier: Modifier = Modifier, selected: ModelId? = null) {
    Box(modifier.fillMaxSize().testTag("screen-models")) { Text("Models") }
}

/**
 * Model detail (DESIGN 6.4 model-detail), hosted by the app shell with the same convention as WP-16's History detail:
 * the pushed screen on the cover display ([pane] false: back arrow), the right pane next to the 316 dp list on the
 * inner display ([pane] true). [onClose] = Back. WP-18 builds it.
 */
@Composable
fun ModelDetail(
    deps: ModelsDeps,
    id: ModelId,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pane: Boolean = false,
) {
    Box(modifier.fillMaxSize().testTag("screen-model-detail")) { Text("Model") }
}
