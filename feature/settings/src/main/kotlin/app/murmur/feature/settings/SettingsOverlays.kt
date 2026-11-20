package app.murmur.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.DialogSurface
import app.murmur.ui.design.components.MurmurButton
import app.murmur.ui.design.components.MurmurDialog
import app.murmur.ui.design.components.MurmurRadio
import app.murmur.ui.design.components.MurmurSheet
import app.murmur.ui.design.components.SheetSurface
import app.murmur.ui.design.rememberIsWide

/**
 * Where Settings draws its sheets and dialogs. Normally (no host) they are windows: [MurmurSheet] and [MurmurDialog]
 * of the design system. A screenshot harness provides a host instead, and the same content is drawn inside the
 * prototype frame (scrim over the whole frame, bottom sheet / 420 dp side sheet / centred dialog), so that the
 * `retention` and `prompt-reset` frames can be compared pixel by pixel. Production never sets it.
 */
@Stable
class InlineOverlayHost {
    var overlay: (@Composable () -> Unit)? by mutableStateOf(null)
        internal set
}

val LocalInlineOverlayHost = staticCompositionLocalOf<InlineOverlayHost?> { null }

/** Draws the host's overlay; the harness puts this on top of its frame. */
@Composable
fun InlineOverlay(host: InlineOverlayHost) {
    host.overlay?.invoke()
}

@Composable
private fun RegisterInline(host: InlineOverlayHost, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(content)
    DisposableEffect(host) {
        val draw: @Composable () -> Unit = { current() }
        host.overlay = draw
        onDispose { if (host.overlay === draw) host.overlay = null }
    }
}

/** A Settings sheet: [MurmurSheet] (bottom sheet on compact width, side sheet on medium+), or inline in a harness. */
@Composable
internal fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalInlineOverlayHost.current
    if (host == null) {
        MurmurSheet(onDismiss = onDismiss, content = content)
        return
    }
    val wide = rememberIsWide()
    RegisterInline(host) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(MurmurTheme.colors.scrim))
            if (wide) {
                // Prototype .inner .sheet.side: below the 36 dp status bar, above the 20 dp gesture bar.
                Box(Modifier.fillMaxSize().padding(top = 36.dp, bottom = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    SheetSurface(side = true, content = content)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    SheetSurface(side = false, content = content)
                }
            }
        }
    }
}

/** A Settings confirm dialog: [MurmurDialog], or inline in a harness. */
@Composable
internal fun SettingsDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val host = LocalInlineOverlayHost.current
    if (host == null) {
        MurmurDialog(title, body, confirmLabel, onConfirm, dismissLabel, onDismiss, destructive)
        return
    }
    val wide = rememberIsWide()
    RegisterInline(host) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(MurmurTheme.colors.scrim))
            Box(Modifier.fillMaxSize().padding(horizontal = if (wide) 0.dp else 28.dp), contentAlignment = Alignment.Center) {
                DialogSurface(title, body, if (wide) Modifier.width(400.dp) else Modifier.fillMaxWidth()) {
                    MurmurButton(dismissLabel, onDismiss, style = ButtonStyle.Text)
                    MurmurButton(confirmLabel, onConfirm, style = if (destructive) ButtonStyle.Destructive else ButtonStyle.Text)
                }
            }
        }
    }
}

/** Sheet title and explanation (prototype: 0/24/4 padding, `title` + `body` in ink2, 6 dp apart). */
@Composable
internal fun SheetHeader(title: String, body: String?) {
    val c = MurmurTheme.colors
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
        Text(title, style = MurmurTheme.type.title, color = c.ink)
        if (body != null) Text(body, style = MurmurTheme.type.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Sheet footnote (caption in ink3, 8/24 padding). */
@Composable
internal fun SheetFootnote(text: String) {
    Text(
        text, style = MurmurTheme.type.caption, color = MurmurTheme.colors.ink3,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
    )
}

/**
 * Retention-sheet row (prototype: `.row` min 60 dp, 10/16 padding, 14 dp gap): leading radio, title and optional
 * subtitle; the whole row selects.
 */
@Composable
internal fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MurmurRadio(selected, onClick = null)
        RowTexts(title, subtitle, Modifier.weight(1f))
    }
}

/** A sheet row with a leading checkbox (languages, "Hidden in"); [leading] = e.g. an app tile. */
@Composable
internal fun CheckRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    minHeight: Dp = 56.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, enabled)
        leading?.invoke()
        RowTexts(title, subtitle, Modifier.weight(1f))
    }
}

@Composable
internal fun RowTexts(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    Column(modifier) {
        Text(title, style = MurmurTheme.type.rowTitle, color = c.ink)
        if (subtitle != null) {
            Text(subtitle, style = MurmurTheme.type.rowSub, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
