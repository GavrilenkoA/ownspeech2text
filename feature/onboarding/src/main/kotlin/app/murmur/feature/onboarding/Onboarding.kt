package app.murmur.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.dictation.InAppTarget
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

/** Onboarding (DESIGN 6.1): mic, accessibility (+restricted settings recovery), notifications, battery, models, try it. */
class OnboardingDeps(
    val settings: SettingsRepository,
    val catalog: CatalogRepository,
    val installed: InstalledModelsRepository,
    val serviceConnected: StateFlow<Boolean>,
    /** mic / notifications / a11y enabled+connected / battery / restrictedLikely; refresh() on return from system screens. */
    val permissions: PermissionMonitor,
    /** Pack sizes and which artifact each pack item resolves to: await device.ensure() before showing sizes. */
    val device: DeviceProfileRepository,
    /** ob-ready practice field: DictationOrchestrator.startInApp(target) behind the in-app bubble. */
    val practice: (InAppTarget) -> Unit,
)

enum class OnboardingStep { WELCOME, MIC, ACCESSIBILITY, RESTRICTED, NOTIFICATIONS, BATTERY, MODELS, DOWNLOADING, READY }

/** WP-15. */
@Composable
fun OnboardingScreen(deps: OnboardingDeps, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("screen-onboarding")) { Text("Onboarding") }
}
