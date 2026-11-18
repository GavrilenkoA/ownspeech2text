package app.murmur.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

/** Onboarding (DESIGN 6.1): mic, accessibility (+restricted settings recovery), notifications, battery, models, try it. */
class OnboardingDeps(
    val settings: SettingsRepository,
    val catalog: CatalogRepository,
    val installed: InstalledModelsRepository,
    val serviceConnected: StateFlow<Boolean>,
)

enum class OnboardingStep { WELCOME, MIC, ACCESSIBILITY, RESTRICTED, NOTIFICATIONS, BATTERY, MODELS, DOWNLOADING, READY }

/** WP-15. */
@Composable
fun OnboardingScreen(deps: OnboardingDeps, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("screen-onboarding")) { Text("Onboarding") }
}
