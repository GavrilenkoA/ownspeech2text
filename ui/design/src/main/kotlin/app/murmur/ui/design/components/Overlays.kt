package app.murmur.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.Motion
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.REDUCED_MOTION_FADE_MS
import app.murmur.ui.design.elevation
import app.murmur.ui.design.rememberIsWide
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

// ---------------------------------------------------------------- Snackbar

/** How a snackbar ended. */
enum class SnackbarResult { Dismissed, ActionPerformed }

/** Snackbar durations (DESIGN 4): 4 s, or 6 s when it has an action. */
const val SNACKBAR_MS = 4000L
const val SNACKBAR_ACTION_MS = 6000L

/** One shown snackbar. */
@Stable
class SnackbarMessage internal constructor(
    val message: String,
    val actionLabel: String?,
    private val cont: CancellableContinuation<SnackbarResult>,
) {
    fun performAction() { if (cont.isActive) cont.resume(SnackbarResult.ActionPerformed) }
    fun dismiss() { if (cont.isActive) cont.resume(SnackbarResult.Dismissed) }
}

/** Queue of snackbars; one at a time. `show` suspends until the snackbar times out or its action is tapped. */
@Stable
class MurmurSnackbarState {
    private val mutex = Mutex()
    var current: SnackbarMessage? by mutableStateOf(null)
        private set

    suspend fun show(message: String, actionLabel: String? = null): SnackbarResult = mutex.withLock {
        try {
            suspendCancellableCoroutine { cont -> current = SnackbarMessage(message, actionLabel, cont) }
        } finally {
            current = null
        }
    }
}

/**
 * Hosts [state]'s snackbars at the bottom of its bounds, [bottomPadding] above the bottom edge and 12 dp from the
 * sides. On the cover display the snackbar sits 84 dp above the screen bottom (12 dp above the 72 dp bottom bar).
 * Timeouts follow the accessibility manager's recommended timeout (longer for TalkBack users).
 */
@Composable
fun MurmurSnackbarHost(state: MurmurSnackbarState, modifier: Modifier = Modifier, bottomPadding: Dp = 12.dp) {
    val msg = state.current
    val a11y = LocalAccessibilityManager.current
    LaunchedEffect(msg) {
        if (msg != null) {
            val base = if (msg.actionLabel != null) SNACKBAR_ACTION_MS else SNACKBAR_MS
            val ms = a11y?.calculateRecommendedTimeoutMillis(base, containsIcons = false, containsText = true, containsControls = msg.actionLabel != null) ?: base
            delay(ms)
            msg.dismiss()
        }
    }
    val reduced = MurmurTheme.reducedMotion
    val visible = remember { MutableTransitionState(false) }
    var shown by remember { mutableStateOf<SnackbarMessage?>(null) }
    if (msg != null) shown = msg
    visible.targetState = msg != null
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visibleState = visible,
            enter = if (reduced) fadeIn(tween(REDUCED_MOTION_FADE_MS)) else fadeIn(tween(Motion.shortMs)) + slideInVertically(tween(Motion.mediumMs, easing = Motion.decelerateEasing)) { it / 2 },
            exit = fadeOut(tween(Motion.shortMs, easing = Motion.accelerateEasing)),
        ) {
            shown?.let { s ->
                MurmurSnackbar(
                    s.message, s.actionLabel, onAction = { s.performAction() },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = bottomPadding),
                )
            }
        }
    }
}

/** The snackbar itself (DESIGN 4): inverse fill, md radius, >= 52 dp, e2; the action in inverseAccent. */
@Composable
fun MurmurSnackbar(message: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .elevation(Elevation.e2, MurmurShapes.md)
            .clip(MurmurShapes.md)
            .background(c.inverse)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = if (actionLabel != null) 6.dp else 16.dp, top = 2.dp, bottom = 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = MurmurTheme.type.body, color = c.onInverse, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        if (actionLabel != null) {
            InverseTextButton(actionLabel, onAction)
        }
    }
}

@Composable
private fun InverseTextButton(text: String, onClick: () -> Unit) {
    val c = MurmurTheme.colors
    Box(
        Modifier
            .heightIn(min = MinTouch)
            .clip(MurmurShapes.full)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MurmurTheme.type.button, color = c.inverseAccent, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Dialog

/**
 * The dialog card (DESIGN 4): surface, xl radius, 24 dp padding, `title` + `body` in ink2, right-aligned [buttons]
 * (usually text buttons: Cancel, then the confirming or destructive one). 400 dp wide on the inner display; on the
 * cover display it spans the width minus 28 dp margins (the caller's layout).
 */
@Composable
fun DialogSurface(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = MurmurTheme.colors
    Column(
        modifier
            .elevation(Elevation.e3, MurmurShapes.xl)
            .clip(MurmurShapes.xl)
            .background(c.surface)
            .padding(24.dp)
            .semantics { paneTitle = title },
    ) {
        Text(title, style = MurmurTheme.type.title, color = c.ink)
        Text(body, style = MurmurTheme.type.body, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
        // 20 dp in the prototype between body and the 48 dp buttons.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            content = buttons,
        )
    }
}

/**
 * Confirmation dialog with the design scrim (ink 40 % light, black 56 % dark): [dismissLabel] then [confirmLabel]
 * as text buttons; [destructive] draws the confirm button in err ("Delete"). Back and scrim taps dismiss.
 */
@Composable
fun MurmurDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    FullWindowDialog(onDismiss) {
        val wide = rememberIsWide()
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = if (wide) 0.dp else 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            DialogSurface(title, body, Modifier.then(if (wide) Modifier.width(400.dp) else Modifier.fillMaxWidth())) {
                MurmurButton(dismissLabel, onDismiss, style = ButtonStyle.Text)
                MurmurButton(confirmLabel, onConfirm, style = if (destructive) ButtonStyle.Destructive else ButtonStyle.Text)
            }
        }
    }
}

/** A dialog window covering the whole screen, platform dim off, our scrim drawn instead; taps on it dismiss. */
@Composable
private fun FullWindowDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MurmurTheme.colors.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            content()
        }
    }
}

// ---------------------------------------------------------------- Sheet

/** Side sheet width on the inner display (DESIGN 4 / 3.5). */
val SideSheetWidth: Dp = 420.dp

/** Sheet drag handle: 32 × 4, line2, 4 dp above and 12 dp below (prototype .sheet .hd). */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp, 4.dp).clip(MurmurShapes.full).background(MurmurTheme.colors.line2))
    }
}

/**
 * Sheet surface without a window (gallery, screenshot tests, embedding): [side] = false is the bottom sheet (xl top
 * corners, handle), true the 420 dp side sheet (xl leading corners, no handle).
 */
@Composable
fun SheetSurface(side: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = if (side) MurmurShapes.sheetSide else MurmurShapes.sheetBottom
    Column(
        modifier
            .then(if (side) Modifier.width(SideSheetWidth).fillMaxHeight() else Modifier.fillMaxWidth())
            .elevation(Elevation.e3, shape)
            .clip(shape)
            .background(MurmurTheme.colors.surface)
            .padding(top = if (side) 24.dp else 8.dp, bottom = 20.dp),
    ) {
        if (!side) SheetHandle()
        content()
    }
}

/**
 * Modal sheet (DESIGN 4, 3.5): a bottom sheet on compact width (slides up, drag to dismiss, handle); a 420 dp right
 * side sheet on medium+ (slides in from the right, 360 ms decelerate; a 150 ms fade under reduced motion). Scrim and
 * back dismiss. Content starts at the sheet's padding; give it 24 dp horizontal padding (prototype).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MurmurSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = MurmurTheme.colors
    if (!rememberIsWide()) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = MurmurShapes.sheetBottom,
            containerColor = c.surface,
            contentColor = c.ink,
            tonalElevation = 0.dp,
            scrimColor = c.scrim,
            dragHandle = { SheetHandle(Modifier.padding(top = 8.dp)) },
        ) {
            // ModalBottomSheet applies the bottom/system-bar insets itself (contentWindowInsets default).
            Column(Modifier.padding(bottom = 20.dp), content = content)
        }
        return
    }
    val reduced = MurmurTheme.reducedMotion
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    FullWindowDialog(onDismiss) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.CenterEnd) {
            AnimatedVisibility(
                visibleState = state,
                enter = if (reduced) fadeIn(tween(REDUCED_MOTION_FADE_MS)) else slideInHorizontally(tween(Motion.longMs, easing = Motion.decelerateEasing)) { it },
                exit = if (reduced) fadeOut(tween(REDUCED_MOTION_FADE_MS)) else slideOutHorizontally(tween(Motion.mediumMs, easing = Motion.accelerateEasing)) { it },
            ) {
                SheetSurface(side = true, content = content)
            }
        }
    }
}
