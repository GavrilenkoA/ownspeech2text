package app.murmur.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.murmur.core.store.Tone
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.AppTile
import app.murmur.ui.design.components.AppTileSize
import app.murmur.ui.design.components.BadgeKind
import app.murmur.ui.design.components.ListRow
import app.murmur.ui.design.components.MurmurBadge
import app.murmur.ui.design.components.MurmurChip
import app.murmur.ui.design.components.Overline

/**
 * Tone per app (DESIGN 6.5 `tone`, ADR-020): the listed apps with their real icons and a tone dropdown, "Add an
 * app", "All other apps", and what the tones do. A stored tone that has no style text yet is marked "Preview" (no
 * frame: a badge next to the app name and a note under the glossary).
 */
@Composable
internal fun ToneScreen(vm: SettingsViewModel, ui: SettingsUi, actions: SettingsActions, wide: Boolean) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    SubBackHandler(vm::closeSub)
    Column(Modifier.fillMaxSize().testTag(SettingsTags.TONE)) {
        SubTopBar(stringResource(R.string.tone_title), wide, onClose = vm::closeSub)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.tone_body), style = t.body, color = c.ink2,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
            SettingsGroup(
                ui.tones.map { row -> GroupItem(indented = false) { ToneAppRow(row, vm) } } + GroupItem(indented = false) {
                    ListRow(
                        stringResource(R.string.tone_add),
                        leading = {
                            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                                Icon(MurmurIcons.plus, null, tint = c.ink2, modifier = Modifier.size(22.dp))
                            }
                        },
                        titleColor = c.accentInk,
                        onClick = { actions.openSheet(SheetKind.TONE_ADD) },
                        modifier = Modifier.testTag(SettingsTags.row("tone-add")),
                    )
                },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            SettingsGroup(
                listOf(
                    GroupItem(indented = false) {
                        ToneRowLayout(
                            title = stringResource(R.string.tone_others),
                            subtitle = stringResource(R.string.tone_others_sub),
                            leading = null,
                            preview = ui.defaultTonePreview,
                            tone = ui.defaultTone,
                            removable = false,
                            tag = "tone-others",
                            onPick = vm::setDefaultTone,
                            onRemove = {},
                        )
                    },
                ),
                Modifier.padding(16.dp),
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Overline(stringResource(R.string.tone_glossary), Modifier.padding(vertical = 8.dp))
                Tone.entries.forEach { tone ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(toneLabel(tone), style = t.label, color = c.ink, modifier = Modifier.width(72.dp))
                        Text(stringResource(toneDescription(tone)), style = t.body, color = c.ink2, modifier = Modifier.weight(1f))
                    }
                }
                if (ui.tones.any { it.preview } || ui.defaultTonePreview) {
                    Text(
                        stringResource(R.string.tone_preview_note), style = t.caption, color = c.ink3,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

private fun toneDescription(t: Tone): Int = when (t) {
    Tone.CASUAL -> R.string.tone_casual_desc
    Tone.NEUTRAL -> R.string.tone_neutral_desc
    Tone.FORMAL -> R.string.tone_formal_desc
    Tone.VERBATIM -> R.string.tone_verbatim_desc
}

@Composable
private fun ToneAppRow(row: ToneRow, vm: SettingsViewModel) {
    val label = row.label ?: row.pkg
    ToneRowLayout(
        title = label,
        subtitle = null,
        leading = { AppTile(label, vm.appIcon(row.pkg), size = AppTileSize.Large, packageName = row.pkg) },
        preview = row.preview,
        tone = row.tone,
        removable = row.removable,
        tag = "tone-${row.pkg}",
        onPick = { vm.setTone(row.pkg, it) },
        onRemove = { vm.removeToneApp(row.pkg) },
    )
}

/**
 * One tone row (prototype: `.row` with a 36 dp tile, the name, and a 36 dp dropdown chip). The chip opens a menu with
 * the four tones and, for added apps, "Remove from list".
 */
@Composable
private fun ToneRowLayout(
    title: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)?,
    preview: Boolean,
    tone: Tone,
    removable: Boolean,
    tag: String,
    onPick: (Tone) -> Unit,
    onRemove: () -> Unit,
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag(SettingsTags.row(tag)),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = t.rowTitle, color = c.ink, modifier = Modifier.weight(1f, fill = false))
                if (preview) MurmurBadge(stringResource(R.string.tone_preview), BadgeKind.InTraining)
            }
            if (subtitle != null) Text(subtitle, style = t.rowSub, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
        Box {
            val toneName = toneLabel(tone)
            val cd = stringResource(R.string.tone_chip_cd, title, toneName)
            MurmurChip(
                toneName, onClick = { open = true }, dropdown = true,
                modifier = Modifier.testTag(SettingsTags.toneChip(tag)).semantics { contentDescription = cd },
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = MurmurShapes.md,
                containerColor = c.surface,
                shadowElevation = Elevation.e3.shadowElevation,
            ) {
                Tone.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(toneLabel(option), style = t.body, color = c.ink) },
                        trailingIcon = if (option == tone) {
                            { Icon(MurmurIcons.check, null, tint = c.accent, modifier = Modifier.size(20.dp)) }
                        } else null,
                        onClick = { open = false; onPick(option) },
                        modifier = Modifier.testTag("$tag-option-${option.name.lowercase()}"),
                    )
                }
                if (removable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tone_remove), style = t.body, color = c.err) },
                        onClick = { open = false; onRemove() },
                        modifier = Modifier.testTag("$tag-remove"),
                    )
                }
            }
        }
    }
}
