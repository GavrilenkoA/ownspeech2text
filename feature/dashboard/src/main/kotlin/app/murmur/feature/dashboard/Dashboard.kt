package app.murmur.feature.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository
import app.murmur.core.store.StatsCalculator
import app.murmur.ui.design.AppInfoProvider
import kotlinx.coroutines.flow.StateFlow

class DashboardDeps(
    val metrics: MetricsRepository,
    val settings: SettingsRepository,
    val stats: StatsCalculator,
    val clock: MurmurClock,
    /** false -> red "The bubble is off" banner (dash-off). */
    val serviceConnected: StateFlow<Boolean>,
    /** "Where you dictate": metrics store package names only; labels and icons come from here. */
    val apps: AppInfoProvider,
)

/**
 * WP-17 Dashboard (DESIGN 6.2): today's words with the estimated time saved, the 7-day chart, pace, latency,
 * typed vs copied, cleanup changes, languages and apps; cover and inner layouts; loading, empty, error and
 * bubble-off states; the range chip and the typing speed sheet. [onTryDictation] = the empty state's button.
 */
@Composable
fun DashboardScreen(deps: DashboardDeps, onTryDictation: () -> Unit, modifier: Modifier = Modifier) {
    val vm: DashboardViewModel = viewModel(key = VM_KEY) { DashboardViewModel(deps) }
    val state by vm.state.collectAsStateWithLifecycle()
    val connected by deps.serviceConnected.collectAsStateWithLifecycle()
    val wpm by vm.typingWpm.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val strings = remember(context, configuration) { AndroidDashboardStrings(context.resources) }
    val formatter = remember(strings, deps.apps) { DashboardFormatter(strings) { pkg -> deps.apps.label(pkg) ?: pkg } }
    val texts = remember(state, formatter) { (state as? DashboardState.Ready)?.takeIf { !it.empty }?.let(formatter::format) }
    val emptyRows = remember(strings) { emptyMetrics(strings) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val actions = remember(vm, onTryDictation, context) {
        DashboardActions(
            onToggleRange = {
                vm.setRange(if (vm.range.value == DashboardRange.Week) DashboardRange.Today else DashboardRange.Week)
            },
            onEditTypingSpeed = { sheet = true },
            onTryDictation = onTryDictation,
            onRetry = vm::retry,
            onOpenAccessibility = {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w("Dashboard", "no accessibility settings activity")
                }
            },
        )
    }
    DashboardContent(
        state = state,
        texts = texts,
        emptyRows = emptyRows,
        bubbleOff = !connected,
        apps = deps.apps,
        actions = actions,
        modifier = modifier,
    )
    if (sheet) {
        TypingSpeedSheet(
            current = wpm,
            onPick = { vm.setTypingWpm(it); sheet = false },
            onDismiss = { sheet = false },
        )
    }
}

private const val VM_KEY = "dashboard"
