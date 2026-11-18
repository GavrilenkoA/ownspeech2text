package app.murmur.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.core.store.StatsCalculator
import kotlinx.coroutines.flow.StateFlow

class DashboardDeps(
    val metrics: MetricsRepository,
    val settings: SettingsRepository,
    val stats: StatsCalculator,
    val clock: MurmurClock,
    /** false -> red "The bubble is off" banner (dash-off). */
    val serviceConnected: StateFlow<Boolean>,
)

/** WP-17. */
@Composable
fun DashboardScreen(deps: DashboardDeps, onTryDictation: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("screen-dashboard")) { Text("Dashboard") }
}
