package app.murmur.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.model.Outcome
import app.murmur.core.prompt.PromptAssets
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.ui.design.AppInfoProvider
import kotlinx.coroutines.flow.StateFlow

class SettingsDeps(
    val settings: SettingsRepository,
    val prompts: PromptAssets,
    val renderer: PromptRenderer,
    val catalog: CatalogRepository,
    val history: HistoryRepository,
    val serviceConnected: StateFlow<Boolean>,
    /** "Try it" card: DictationOrchestrator.tryCleanup(sample, unsaved override, unsaved preferences) (DESIGN 6.5). */
    val tryCleanup: suspend (sample: String, systemOverride: String?, preferences: String?) -> Outcome<String>,
    /** Retention shortening: run deletion now with the new period, returns the number of entries deleted (WP-12 Retention.runNow). */
    val retention: suspend (days: Int) -> Int,
    /** "Hidden in" and "Tone per app" pickers: labels, icons, launcher apps. */
    val apps: AppInfoProvider,
    /** Settings > Permissions rows and the "bubble is off" banner. */
    val permissions: PermissionMonitor,
)

/** WP-19. */
@Composable
fun SettingsScreen(deps: SettingsDeps, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("screen-settings")) { Text("Settings") }
}
