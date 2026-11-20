package app.murmur.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.core.model.ProductName
import app.murmur.core.prompt.PromptEditRules
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.Sizes
import app.murmur.ui.design.components.MinTouch
import app.murmur.ui.design.elevation
import app.murmur.ui.design.components.Banner
import app.murmur.ui.design.components.BannerKind
import app.murmur.ui.design.components.ButtonSize
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.MurmurButton
import app.murmur.ui.design.components.Overline
import app.murmur.ui.design.components.Spinner
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** The editor for the open [SettingsViewModel.promptEditor] (nothing while it is being created). */
@Composable
internal fun PromptEditorHost(vm: SettingsViewModel, wide: Boolean, initialDialog: PromptDialog? = null) {
    val editor by vm.promptEditor.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    editor?.let { PromptEditorScreen(it, wide, useScreen = ui.settings.useScreen, onClose = vm::closeSub, initialDialog = initialDialog) }
}

/**
 * Settings -> Cleanup prompt (DESIGN 6.5 `prompt`, `prompt-err`, `prompt-reset`; ADR-019 replaces the variable chips
 * with "My preferences" and a read-only "What the model also receives" panel). Cover: a pushed screen on bg with a
 * back arrow; inner: the detail pane on surface with a close button. The footer holds Test again and Save.
 */
@Composable
internal fun PromptEditorScreen(editor: PromptEditor, wide: Boolean, useScreen: Boolean, onClose: () -> Unit, initialDialog: PromptDialog? = null) {
    val ui by editor.ui.collectAsStateWithLifecycle()
    val c = MurmurTheme.colors
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    SubBackHandler { if (ui.dirty) dialog = PromptDialog.DISCARD else onClose() }
    Column(Modifier.fillMaxSize().background(if (wide) c.surface else c.bg).testTag(SettingsTags.PROMPT)) {
        SubTopBar(stringResource(R.string.prompt_title), wide, onClose = { if (ui.dirty) dialog = PromptDialog.DISCARD else onClose() }) {
            if (ui.editable) {
                MurmurButton(
                    stringResource(R.string.prompt_reset), { dialog = PromptDialog.RESET }, style = ButtonStyle.Text,
                    enabled = ui.canReset, modifier = Modifier.testTag(SettingsTags.PROMPT_RESET),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        ) {
            Notice(ui.notice, onReset = { dialog = PromptDialog.RESET }, onKeepEditing = editor::keepEditing)
            Text(stringResource(R.string.prompt_explain, ProductName.NAME), style = MurmurTheme.type.body, color = c.ink2)
            val error = ui.problems.system.isNotEmpty()
            val editorLabel = stringResource(R.string.prompt_editor_label)
            PromptBox(Modifier.padding(top = 14.dp), error = error) {
                BasicTextField(
                    value = ui.text,
                    onValueChange = editor::setText,
                    readOnly = !ui.editable,
                    textStyle = editorStyle().copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.PROMPT_FIELD).semantics { contentDescription = editorLabel },
                )
            }
            ui.problems.system.firstOrNull()?.let { key -> ErrorLine(systemProblemText(key, ui.text.length)) }
            Preferences(ui, editor)
            Receives(useScreen)
            TryCard(ui.tryState)
        }
        Footer(
            tested = ui.tryState != TryState.Idle,
            running = ui.tryState == TryState.Polishing,
            canSave = ui.canSave,
            onTest = { editor.runTry(PromptEditor.SAMPLE) },
            onSave = { scope.launch { if (editor.save()) onClose() } },
        )
    }
    when (dialog) {
        PromptDialog.RESET -> SettingsDialog(
            stringResource(R.string.prompt_reset_title), stringResource(R.string.prompt_reset_body),
            stringResource(R.string.prompt_reset), { dialog = null; scope.launch { editor.reset() } },
            stringResource(R.string.settings_cancel), { dialog = null },
        )
        PromptDialog.DISCARD -> SettingsDialog(
            stringResource(R.string.prompt_discard_title), stringResource(R.string.prompt_discard_body),
            stringResource(R.string.prompt_discard), { dialog = null; onClose() },
            stringResource(R.string.prompt_keep_editing), { dialog = null }, destructive = true,
        )
        null -> Unit
    }
}

/** Dialogs of the prompt editor. */
enum class PromptDialog { RESET, DISCARD }

@Composable
private fun systemProblemText(key: String, length: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val f = NumberFormat.getIntegerInstance(locale)
    return when (key) {
        "empty" -> stringResource(R.string.prompt_err_empty)
        "too_long" -> stringResource(R.string.prompt_err_too_long, f.format(PromptEditRules.MAX_SYSTEM_CHARS), f.format(length))
        else -> stringResource(R.string.prompt_err_markup)
    }
}

/** Validation line (prototype prompt-err: err text with an 18 dp alert icon, 8 dp below the field). */
@Composable
private fun ErrorLine(text: String) {
    val c = MurmurTheme.colors
    Row(
        Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MurmurIcons.alert, null, tint = c.err, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = MurmurTheme.type.body, color = c.err)
    }
}

@Composable
private fun Notice(notice: PromptNotice, onReset: () -> Unit, onKeepEditing: () -> Unit) {
    when (notice) {
        PromptNotice.NONE -> Unit
        PromptNotice.FINE_TUNE -> Banner(
            BannerKind.Info, stringResource(R.string.prompt_finetune_title), stringResource(R.string.prompt_finetune_body),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        PromptNotice.FINE_TUNE_EDIT_KEPT -> Banner(
            BannerKind.Info, stringResource(R.string.prompt_kept_title), stringResource(R.string.prompt_kept_body),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        PromptNotice.OLDER_PROMPT -> TwoActionBanner(
            stringResource(R.string.prompt_older_title), stringResource(R.string.prompt_older_body),
            stringResource(R.string.prompt_reset), onReset, stringResource(R.string.prompt_keep_editing), onKeepEditing,
        )
    }
}

/**
 * The warn banner with two text buttons ("Your edit was made for an older prompt": Reset / Keep editing). No frame:
 * the design system's banner (md radius, warnSoft, 22 dp alert icon, bold first clause) with a second action.
 */
@Composable
private fun TwoActionBanner(title: String, body: String, first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit) {
    val c = MurmurTheme.colors
    Row(
        Modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .clip(MurmurShapes.md)
            .background(c.warnSoft)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(MurmurIcons.alert, null, tint = c.warn, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                    append(" ")
                    append(body)
                },
                style = MurmurTheme.type.body.copy(lineBreak = LineBreak.Simple), color = c.ink,
            )
            // As the design system's banner action (prototype .banner .acts {margin: 6px 0 -6px -12px}): 40 dp text
            // buttons 6 dp under the text, hanging 12 dp left; their 48 dp touch targets overhang by 4 dp.
            Row(
                Modifier.offset(x = (-12).dp).layout { m, cst ->
                    val p = m.measure(cst)
                    val hit = ((MinTouch - Sizes.buttonSm) / 2).roundToPx()
                    layout(p.width, p.height - 2 * hit) { p.place(0, 6.dp.roundToPx() - hit) }
                },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MurmurButton(first, onFirst, style = ButtonStyle.Text, size = ButtonSize.Small, modifier = Modifier.testTag("settings-older-reset"))
                MurmurButton(second, onSecond, style = ButtonStyle.Text, size = ButtonSize.Small, modifier = Modifier.testTag("settings-older-keep"))
            }
        }
    }
}

/** "My preferences" (≤ 1,000 chars, all models; prompts/README rule 3). */
@Composable
private fun Preferences(ui: PromptEditorUi, editor: PromptEditor) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val locale = LocalConfiguration.current.locales[0]
    val f = NumberFormat.getIntegerInstance(locale)
    val label = stringResource(R.string.prompt_prefs)
    Overline(label, Modifier.padding(top = 24.dp))
    Text(stringResource(R.string.prompt_prefs_help), style = t.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
    val hint = stringResource(R.string.prompt_prefs_hint)
    Box(Modifier.padding(top = 10.dp)) {
        PromptBox(error = ui.problems.preferencesTooLong) {
            BasicTextField(
                value = ui.preferences,
                onValueChange = editor::setPreferences,
                // Three lines: room for a few habits, and a 60 dp touch target.
                minLines = 3,
                textStyle = t.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().testTag(SettingsTags.PREFS_FIELD).semantics { contentDescription = label },
                decorationBox = { inner ->
                    if (ui.preferences.isEmpty()) Text(hint, style = t.body, color = c.ink3)
                    inner()
                },
            )
        }
    }
    val used = PromptEditor.normalizePreferences(ui.preferences)?.length ?: 0
    Text(
        stringResource(R.string.prompt_prefs_count, f.format(used), f.format(PromptEditRules.MAX_PREFERENCES_CHARS)),
        style = t.caption, color = if (ui.problems.preferencesTooLong) c.err else c.ink3, textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    if (ui.problems.preferencesTooLong) {
        ErrorLine(stringResource(R.string.prompt_prefs_too_long, f.format(PromptEditRules.MAX_PREFERENCES_CHARS)))
    }
}

/**
 * "What the model also receives" (ADR-019; no frame): the parts of the request the user cannot edit, by privacy
 * level (ADR-070): screen text and the text before the cursor only with "Use what's on screen" on and outside
 * private windows; a failed screenshot only leaves the screen text out.
 */
@Composable
private fun Receives(useScreen: Boolean) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    Overline(stringResource(R.string.prompt_receives), Modifier.padding(top = 24.dp))
    val items = listOf(
        R.string.prompt_receives_style to R.string.prompt_receives_style_body,
        R.string.prompt_receives_app to R.string.prompt_receives_app_body,
        R.string.prompt_receives_screen to if (useScreen) R.string.prompt_receives_screen_body else R.string.prompt_receives_screen_off,
        R.string.prompt_receives_before to if (useScreen) R.string.prompt_receives_before_body else R.string.prompt_receives_before_off,
        R.string.prompt_receives_transcript to R.string.prompt_receives_transcript_body,
    )
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(MurmurShapes.md)
            .background(c.surface2)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { (title, body) ->
            Column {
                Text(stringResource(title), style = t.label, color = c.ink)
                Text(stringResource(body), style = t.body, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** "Try it" card (prototype: surface card, lg radius, e1, 14/16 padding; Spoken, a 1 dp rule, Result). */
@Composable
private fun TryCard(state: TryState) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    Overline(stringResource(R.string.prompt_try), Modifier.padding(top = 24.dp))
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .elevation(Elevation.e1, MurmurShapes.lg)
            .clip(MurmurShapes.lg)
            .background(c.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(stringResource(R.string.prompt_spoken), style = t.caption, color = c.ink3)
        Text(PromptEditor.SAMPLE, style = t.body, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(c.line))
        Text(stringResource(R.string.prompt_result), style = t.caption, color = c.ink3)
        Box(Modifier.padding(top = 2.dp).testTag(SettingsTags.TRY_RESULT).semantics { liveRegion = LiveRegionMode.Polite }) {
            when (state) {
                TryState.Idle -> Text(stringResource(R.string.prompt_try_idle), style = t.body, color = c.ink3)
                TryState.Polishing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(color = c.ink2)
                    Text(stringResource(R.string.prompt_polishing), style = t.body, color = c.ink2)
                }
                is TryState.Done -> Text(state.text, style = t.body, color = c.ink)
                is TryState.Failed -> Text(tryFailureText(state.reason), style = t.body, color = c.err)
            }
        }
    }
}

@Composable
private fun tryFailureText(f: TryFailure): String = stringResource(
    when (f) {
        TryFailure.NO_MODEL -> R.string.prompt_try_no_model
        TryFailure.CLEANUP_OFF -> R.string.prompt_try_off
        TryFailure.NO_SPEECH_MODEL -> R.string.prompt_try_no_speech
        TryFailure.LOW_MEMORY -> R.string.prompt_try_memory
        TryFailure.REJECTED -> R.string.prompt_try_rejected
        TryFailure.FAILED -> R.string.prompt_try_failed
        TryFailure.INVALID -> R.string.prompt_try_invalid
    },
)

/** Footer (prototype: 1 dp top line, surface, 12/20/14 padding): Test again (secondary, refresh icon) and Save. */
@Composable
private fun Footer(tested: Boolean, running: Boolean, canSave: Boolean, onTest: () -> Unit, onSave: () -> Unit) {
    val c = MurmurTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MurmurButton(
            stringResource(if (tested) R.string.prompt_test_again else R.string.prompt_test), onTest,
            style = ButtonStyle.Secondary, leadingIcon = MurmurIcons.refresh, loading = running,
            modifier = Modifier.weight(1f).testTag(SettingsTags.PROMPT_TEST),
        )
        MurmurButton(
            stringResource(R.string.settings_save), onSave, enabled = canSave,
            modifier = Modifier.weight(1f).testTag(SettingsTags.PROMPT_SAVE),
        )
    }
}
