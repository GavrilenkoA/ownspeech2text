package app.murmur

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import kotlinx.serialization.Serializable

/** Top-level destinations (BRIEF: 4 bottom tabs). History is the start destination (DESIGN decision 4). */
@Serializable sealed interface TopRoute : NavKey
@Serializable data object DashboardRoute : TopRoute
@Serializable data object HistoryRoute : TopRoute
@Serializable data object ModelsRoute : TopRoute
@Serializable data object SettingsRoute : TopRoute

data class Tab(val route: TopRoute, val label: Int, val testTag: String)

val TABS = listOf(
    Tab(DashboardRoute, R.string.tab_dashboard, "tab-dashboard"),
    Tab(HistoryRoute, R.string.tab_history, "tab-history"),
    Tab(ModelsRoute, R.string.tab_models, "tab-models"),
    Tab(SettingsRoute, R.string.tab_settings, "tab-settings"),
)

/**
 * Shell (WP-01): bottom bar on compact width (cover display), navigation rail on medium+ (inner display),
 * decided by WindowSizeClass, never by device detection (DESIGN 2). Screens are placeholders until the
 * feature packages land; icons come from the design system (prototype ICONS map) in WP-01.
 */
@Composable
fun AppShell(container: AppContainer?) {
    val backStack = rememberNavBackStack(HistoryRoute)
    val current = backStack.lastOrNull() as? TopRoute ?: HistoryRoute
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val select: (TopRoute) -> Unit = { r -> backStack.clear(); backStack.add(r) }

    val content: @Composable (Modifier) -> Unit = { m ->
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            modifier = m,
            entryProvider = entryProvider {
                entry<DashboardRoute> { Placeholder("screen-dashboard") }
                entry<HistoryRoute> { Placeholder("screen-history") }
                entry<ModelsRoute> { Placeholder("screen-models") }
                entry<SettingsRoute> { Placeholder("screen-settings") }
            },
        )
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.testTag("nav-rail")) {
                TABS.forEach { t ->
                    NavigationRailItem(selected = current == t.route, onClick = { select(t.route) },
                        icon = { TabIcon() }, label = { Text(stringResource(t.label)) }, modifier = Modifier.testTag(t.testTag))
                }
            }
            content(Modifier.fillMaxSize())
        }
    } else {
        Scaffold(bottomBar = {
            NavigationBar(Modifier.testTag("nav-bar")) {
                TABS.forEach { t ->
                    NavigationBarItem(selected = current == t.route, onClick = { select(t.route) },
                        icon = { TabIcon() }, label = { Text(stringResource(t.label)) }, modifier = Modifier.testTag(t.testTag))
                }
            }
        }) { pad -> content(Modifier.padding(pad)) }
    }
}

@Composable
private fun TabIcon(icon: ImageVector? = null) {
    if (icon != null) Icon(icon, contentDescription = null)
}

@Composable
private fun Placeholder(tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag)) { Text(tag) }
}
