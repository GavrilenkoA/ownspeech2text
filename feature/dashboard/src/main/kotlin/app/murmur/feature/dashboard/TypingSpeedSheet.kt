package app.murmur.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.ButtonSize
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.MurmurButton
import app.murmur.ui.design.components.MurmurRadio
import app.murmur.ui.design.components.MurmurSheet
import java.text.NumberFormat

/** Test tags of the typing speed sheet. */
object TypingSpeedTags {
    const val SHEET = "dash-wpm-sheet"
    const val CUSTOM_FIELD = "dash-wpm-custom"
    const val SAVE = "dash-wpm-save"
    fun option(wpm: Int) = "dash-wpm-$wpm"
    const val CUSTOM = "dash-wpm-custom-row"
}

/**
 * Typing speed for "time saved" (DESIGN 6.2: 30/40/50/60/custom), in the layout of the retention sheet (title,
 * explanation, 60 dp radio rows). A preset applies at once and closes the sheet; Custom shows a number field
 * (10-200 wpm) with Save. No frame exists for this sheet: composed from WP-01 parts.
 */
@Composable
fun TypingSpeedSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val presets = DashboardViewModel.PRESETS
    var custom by rememberSaveable { mutableStateOf(current !in presets) }
    var text by rememberSaveable { mutableStateOf(if (current in presets) "" else current.toString()) }
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    MurmurSheet(onDismiss = onDismiss) {
        Column(Modifier.testTag(TypingSpeedTags.SHEET)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.dash_wpm_title), style = t.title, color = c.ink)
                Text(
                    stringResource(R.string.dash_wpm_body), style = t.body, color = c.ink2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).selectableGroup()) {
                presets.forEach { wpm ->
                    RadioRow(
                        title = stringResource(R.string.dash_wpm_option, format.format(wpm)),
                        subtitle = if (wpm == DEFAULT_WPM) stringResource(R.string.dash_wpm_default) else null,
                        selected = !custom && current == wpm,
                        onClick = { onPick(wpm) },
                        modifier = Modifier.testTag(TypingSpeedTags.option(wpm)),
                    )
                }
                RadioRow(
                    title = stringResource(R.string.dash_wpm_custom),
                    subtitle = if (current !in presets) stringResource(R.string.dash_wpm_option, format.format(current)) else null,
                    selected = custom,
                    onClick = { custom = true },
                    modifier = Modifier.testTag(TypingSpeedTags.CUSTOM),
                )
            }
            if (custom) {
                val value = text.toIntOrNull()
                val valid = value != null && value in DashboardViewModel.MIN_WPM..DashboardViewModel.MAX_WPM
                val label = stringResource(R.string.dash_wpm_custom_label)
                val range = stringResource(
                    R.string.dash_wpm_custom_range,
                    format.format(DashboardViewModel.MIN_WPM), format.format(DashboardViewModel.MAX_WPM),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(MurmurShapes.full)
                            .background(c.surface)
                            .border(2.dp, c.ink, MurmurShapes.full)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                            singleLine = true,
                            textStyle = t.bodyLg.copy(color = c.ink),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (valid) onPick(value!!) }),
                            modifier = Modifier.fillMaxWidth().testTag(TypingSpeedTags.CUSTOM_FIELD)
                                .semantics { contentDescription = label },
                            decorationBox = { inner ->
                                if (text.isEmpty()) Text(label, style = t.bodyLg, color = c.ink3)
                                inner()
                            },
                        )
                    }
                    MurmurButton(
                        stringResource(R.string.dash_wpm_save), { if (valid) onPick(value!!) },
                        style = ButtonStyle.Primary, size = ButtonSize.Small, enabled = valid,
                        modifier = Modifier.testTag(TypingSpeedTags.SAVE),
                    )
                }
                Text(
                    range, style = t.caption, color = c.ink3,
                    modifier = Modifier.padding(start = 40.dp, end = 24.dp, top = 6.dp),
                )
            }
        }
    }
}

/** Retention-sheet row: 60 dp, leading radio, title and optional subtitle; the whole row selects. */
@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .clip(MurmurShapes.md)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MurmurRadio(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = t.rowTitle, color = c.ink)
            if (subtitle != null) Text(subtitle, style = t.rowSub, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private const val DEFAULT_WPM = 40
