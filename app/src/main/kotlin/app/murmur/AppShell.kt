package app.murmur

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.feature.dashboard.DashboardScreen
import app.murmur.feature.history.HistoryEntryDetail
import app.murmur.feature.history.HistoryScreen
import app.murmur.feature.models.ModelDetail
import app.murmur.feature.models.ModelsScreen
import app.murmur.feature.onboarding.PracticeScreen
import app.murmur.feature.settings.SettingsScreen
import app.murmur.shell.DeepLink
import app.murmur.shell.ShellNav
import app.murmur.ui.design.Layout
import app.murmur.ui.design.Motion
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.MurmurNavigationBar
import app.murmur.ui.design.components.MurmurNavigationRail
import app.murmur.ui.design.components.NavItem
import app.murmur.ui.design.rememberIsWide
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Top-level destinations (BRIEF: 4 bottom tabs). History is the start destination (DESIGN decision 4). */
@Serializable sealed interface TopRoute : NavKey
@Serializable data object DashboardRoute : TopRoute
@Serializable data object HistoryRoute : TopRoute
@Serializable data object ModelsRoute : TopRoute
@Serializable data object SettingsRoute : TopRoute

/** Detail destinations: pushed on the cover display, the right pane on the inner display. */
@Serializable data class HistoryEntryRoute(val id: Long) : NavKey
@Serializable data class ModelDetailRoute(val id: String) : NavKey
/** "Try a dictation" (History and Dashboard empty states): the practice field, pushed over the current tab. */
@Serializable data object PracticeRoute : NavKey

data class Tab(val route: TopRoute, val label: Int)

/** The four tab screens' dependencies, built by AppContainer (ADR-010). */
class ShellDeps(
    val dashboard: app.murmur.feature.dashboard.DashboardDeps,
    val history: app.murmur.feature.history.HistoryDeps,
    val models: app.murmur.feature.models.ModelsDeps,
    val settings: app.murmur.feature.settings.SettingsDeps,
    /** The practice screen reuses the onboarding's practice field and in-app bubble. */
    val practice: app.murmur.feature.onboarding.OnboardingDeps,
)

val TABS = listOf(
    Tab(DashboardRoute, R.string.tab_dashboard),
    Tab(HistoryRoute, R.string.tab_history),
    Tab(ModelsRoute, R.string.tab_models),
    Tab(SettingsRoute, R.string.tab_settings),
)

private val TAB_ICONS = mapOf(
    DashboardRoute to MurmurIcons.dash, HistoryRoute to MurmurIcons.hist,
    ModelsRoute to MurmurIcons.models, SettingsRoute to MurmurIcons.sliders,
)

/** Entry metadata key marking a detail destination (shared-axis X push/pop on the cover display). */
private const val DETAIL = "murmur.detail"

/**
 * The frame every screen lives in (WP-15, DESIGN 2/4). Compact width (cover display): content + bottom bar (72 dp +
 * gesture inset, ink pill). Medium and wider (inner display): navigation rail (88 dp, mark on top) + content, with
 * list-detail scenes for History (300 dp list) and Models (316 dp list); Settings lays out its own two panes. Chosen by
 * the window size class, never by device. [links] = deep links from MainActivity (bubble: open entry / open Models).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppShell(deps: ShellDeps, links: StateFlow<DeepLink?>, onLinkHandled: () -> Unit, onExit: () -> Unit) {
    val backStack = rememberNavBackStack(HistoryRoute)
    val wide = rememberIsWide()
    val c = MurmurTheme.colors

    fun replace(stack: List<NavKey>) {
        backStack.clear()
        backStack.addAll(stack)
    }

    // The hub segment an "Open Models" link asked for, until the Models screen has shown it (saved across fold/unfold).
    var modelsSegment by rememberSaveable { mutableStateOf<ModelKind?>(null) }
    val link = links.collectAsStateValue()
    LaunchedEffect(link) {
        if (link != null) {
            modelsSegment = ShellNav.segmentFor(link)
            replace(ShellNav.forLink(link))
            onLinkHandled()
        }
    }
    BackHandler { ShellNav.back(backStack.toList())?.let(::replace) ?: onExit() }

    val tab = ShellNav.tabOf(backStack)
    val items = TABS.map { NavItem(stringResource(it.label), TAB_ICONS.getValue(it.route)) }
    val select: (Int) -> Unit = { i ->
        modelsSegment = null
        replace(ShellNav.selectTab(TABS[i].route))
    }
    val selectedEntry = (backStack.lastOrNull() as? HistoryEntryRoute)?.let { EntryId(it.id) }
    val selectedModel = (backStack.lastOrNull() as? ModelDetailRoute)?.let { ModelId(it.id) }
    val tryDictation: () -> Unit = { replace(ShellNav.openPractice(backStack.toList())) }
    val close: (TopRoute) -> Unit = { root -> replace(ShellNav.back(backStack.toList()) ?: listOf(root)) }

    // DESIGN 2: two panes on the inner display (medium width), no gap between them; the list widths are tokens.
    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2())
        .copy(horizontalPartitionSpacerSize = 0.dp)
    val listDetail = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    // Material shared axis X travels 30 dp.
    val shiftPx = with(LocalDensity.current) { 30.dp.roundToPx() }

    val content: @Composable (Modifier) -> Unit = { m ->
        NavDisplay(
            backStack = backStack,
            onBack = { ShellNav.back(backStack.toList())?.let(::replace) ?: onExit() },
            modifier = m,
            sceneStrategies = listOf(listDetail),
            // ViewModels belong to their back-stack entry (kept across fold/unfold, cleared when the entry leaves the
            // stack): the practice screen's controller stops watching the shared bubble once the screen is closed.
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            transitionSpec = { forward(wide, shiftPx) },
            popTransitionSpec = { backward(wide, shiftPx) },
            predictivePopTransitionSpec = { backward(wide, shiftPx) },
            entryProvider = entryProvider {
                entry<DashboardRoute> {
                    DashboardScreen(deps.dashboard, onTryDictation = tryDictation)
                }
                entry<HistoryRoute>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { EmptyDetail() }) +
                        ListDetailSceneStrategy.preferredPaneSize(Layout.historyListPane),
                ) {
                    ListPane {
                        HistoryScreen(
                            deps.history, onOpenEntry = { replace(ShellNav.openEntry(it)) },
                            selected = if (wide) selectedEntry else null, onTryDictation = tryDictation,
                        )
                    }
                }
                entry<HistoryEntryRoute>(metadata = ListDetailSceneStrategy.detailPane() + mapOf(DETAIL to true)) { r ->
                    DetailPane("detail-entry-${r.id}", wide) {
                        HistoryEntryDetail(deps.history, EntryId(r.id), onClose = { close(HistoryRoute) }, pane = wide)
                    }
                }
                entry<ModelsRoute>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { EmptyDetail() }) +
                        ListDetailSceneStrategy.preferredPaneSize(Layout.modelsListPane),
                ) {
                    ListPane {
                        // A deep link's Speech / Cleanup request is shown once, then cleared (ADR-087).
                        ModelsScreen(
                            deps.models, onOpenModel = { replace(ShellNav.openModel(it.value)) },
                            selected = if (wide) selectedModel else null,
                            segment = modelsSegment, onSegmentShown = { modelsSegment = null },
                        )
                    }
                }
                entry<ModelDetailRoute>(metadata = ListDetailSceneStrategy.detailPane() + mapOf(DETAIL to true)) { r ->
                    DetailPane("detail-model-${r.id}", wide) {
                        ModelDetail(deps.models, ModelId(r.id), onClose = { close(ModelsRoute) }, pane = wide)
                    }
                }
                // WIRING SLOT (WP-19): Settings lays out its own panes on the inner display (260 dp list).
                entry<SettingsRoute> { SettingsScreen(deps.settings) }
                entry<PracticeRoute>(metadata = mapOf(DETAIL to true)) {
                    PracticeScreen(deps.practice, onClose = { close(ShellNav.tabOf(backStack.toList())) }, Modifier.testTag("shell-practice"))
                }
            },
        )
    }

    val insets = WindowInsets.safeDrawing
    if (wide) {
        Row(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
                .testTag("shell"),
        ) {
            MurmurNavigationRail(items, ShellNav.indexOf(tab), select, Modifier.testTag("nav-rail"))
            content(Modifier.weight(1f).windowInsetsPadding(insets.only(WindowInsetsSides.End)))
        }
    } else {
        Column(Modifier.fillMaxSize().background(c.bg).testTag("shell")) {
            content(Modifier.weight(1f).windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)))
            // The bar pads itself by the gesture inset (72 dp + inset, DESIGN 4). A pushed screen covers it (hist-detail).
            if (!ShellNav.isPushed(backStack.toList())) MurmurNavigationBar(items, ShellNav.indexOf(tab), select, Modifier.testTag("nav-bar"))
        }
    }
}

/** The list pane on bg; the detail pane next to it draws the 1 dp line between them. */
@Composable
private fun ListPane(content: @Composable () -> Unit) {
    val c = MurmurTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg)) { content() }
}

/**
 * The detail destination. Inner display: the right pane on surface, a 1 dp line from the list (prototype: background
 * var(--surface), border-right of the list). Cover display: a pushed screen on bg like every other screen.
 */
@Composable
private fun DetailPane(tag: String, wide: Boolean, content: @Composable () -> Unit) {
    val c = MurmurTheme.colors
    val pane = if (wide) Modifier.background(c.surface).leftLine(c.line) else Modifier.background(c.bg)
    Box(Modifier.fillMaxSize().then(pane).testTag(tag)) { content() }
}

/** Nothing selected on the inner display: the detail pane stays an empty surface (no frame defines a placeholder). */
@Composable
private fun EmptyDetail() {
    val c = MurmurTheme.colors
    Box(Modifier.fillMaxSize().background(c.surface).leftLine(c.line).testTag("detail-empty"))
}

/** 1 dp line on the pane's left edge, drawn over the content (prototype: the list's border-right 1px line). */
private fun Modifier.leftLine(color: Color): Modifier = drawWithContent {
    drawContent()
    drawLine(color, Offset(0.5.dp.toPx(), 0f), Offset(0.5.dp.toPx(), size.height), 1.dp.toPx())
}

// ------------------------------------------------------------------ transitions (DESIGN 3.5)

private fun Scene<*>.isDetail(): Boolean = entries.lastOrNull()?.metadata?.get(DETAIL) == true

private fun AnimatedContentTransitionScope<*>.forward(wide: Boolean, shiftPx: Int): ContentTransform =
    if (!wide && (targetState as? Scene<*>)?.isDetail() == true) sharedAxisX(forward = true, shiftPx) else crossFade()

private fun AnimatedContentTransitionScope<*>.backward(wide: Boolean, shiftPx: Int): ContentTransform =
    if (!wide && (initialState as? Scene<*>)?.isDetail() == true) sharedAxisX(forward = false, shiftPx) else crossFade()

/**
 * Shared-axis X push/pop on the cover display (DESIGN 3.5, 240 ms): both screens move [shiftPx] along X; the outgoing
 * one fades out in the first quarter, the incoming one fades in after it (Material shared axis).
 */
private fun sharedAxisX(forward: Boolean, shiftPx: Int): ContentTransform {
    val d = Motion.mediumMs
    val dir = if (forward) 1 else -1
    val enter = slideInHorizontally(tween(d, easing = Motion.standardEasing)) { dir * shiftPx } +
        fadeIn(tween(d * 3 / 4, delayMillis = d / 4, easing = Motion.decelerateEasing))
    val exit = slideOutHorizontally(tween(d, easing = Motion.standardEasing)) { -dir * shiftPx } +
        fadeOut(tween(d / 4, easing = Motion.accelerateEasing))
    return enter togetherWith exit
}

/** Tab switches, and every change on the inner display (the detail pane cross-fades): 150 ms. */
private fun crossFade(): ContentTransform = fadeIn(tween(Motion.shortMs)) togetherWith fadeOut(tween(Motion.shortMs))

@Composable
private fun <T> StateFlow<T>.collectAsStateValue(): T {
    val s = androidx.compose.runtime.produceState(value, this) { collect { value = it } }
    return s.value
}
