package app.murmur.feature.models

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.ProductName
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.components.Banner
import app.murmur.ui.design.components.BannerKind
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.LinearProgress
import app.murmur.ui.design.components.ListRow
import app.murmur.ui.design.components.MurmurButton
import app.murmur.ui.design.components.MurmurDialog
import app.murmur.ui.design.components.MurmurSheet
import app.murmur.ui.design.components.Overline
import app.murmur.ui.design.components.RowTrailing
import app.murmur.ui.design.components.SegmentedControl
import kotlinx.coroutines.launch

/** Sheets and dialogs a surface opened (each surface hosts only its own: list and detail can be on screen together). */
@Composable
internal fun HubOverlays(controller: ModelsController, deps: ModelsDeps, surface: Surface) {
    val bench by controller.bench.collectAsStateWithLifecycle()
    val add by controller.add.collectAsStateWithLifecycle()
    val delete by controller.delete.collectAsStateWithLifecycle()
    val input by controller.input.collectAsStateWithLifecycle()

    bench?.takeIf { it.origin == surface }?.let { b ->
        MurmurSheet(onDismiss = controller::closeBenchmark) {
            BenchSheetContent(
                state = b,
                deviceLabel = deviceLabel(deps, input),
                onStop = controller::stopBenchmark,
                onDone = controller::closeBenchmark,
                onUnit = controller::showBenchUnit,
            )
        }
    }
    if (surface == Surface.LIST) add?.let { a ->
        MurmurSheet(onDismiss = controller::closeAdd) {
            AddModelSheetContent(
                state = a,
                onEdit = controller::editAdd,
                onCheck = controller::resolveAdd,
                onSave = controller::saveAdd,
                onBack = controller::backToForm,
            )
        }
    }
    delete?.takeIf { it.origin == surface }?.let { d ->
        val body = when {
            d.custom -> stringResource(R.string.delete_body_custom)
            d.support -> stringResource(R.string.delete_body_support)
            input?.catalog?.model(d.model)?.kind == ModelKind.LLM -> stringResource(R.string.delete_body_llm, ProductName.NAME)
            else -> stringResource(R.string.delete_body_asr, ProductName.NAME)
        }
        MurmurDialog(
            title = stringResource(R.string.delete_title, d.name),
            body = body,
            confirmLabel = stringResource(R.string.delete_confirm),
            onConfirm = controller::confirmDelete,
            dismissLabel = stringResource(R.string.delete_cancel),
            onDismiss = controller::cancelDelete,
            destructive = true,
        )
    }
}

/** "Galaxy Z Fold7 · 12 GB": the phone's model name and its RAM from the device profile. */
internal fun deviceLabel(deps: ModelsDeps, input: HubInput?): DeviceLabel =
    DeviceLabel(deps.deviceName, input?.device?.totalRamMb?.let(Format::ramGb))

internal data class DeviceLabel(val name: String, val ramGb: Long?)

/**
 * Benchmark sheet content (DESIGN bench-run / bench-result). Keeps the screen on while running (DESIGN 6.4: the phone
 * must stay awake and the app in front).
 */
@Composable
internal fun ColumnScope.BenchSheetContent(
    state: BenchUi,
    deviceLabel: DeviceLabel,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onUnit: (ComputeUnit) -> Unit,
) {
    val view = LocalView.current
    val running = state.phase == BenchUi.Phase.RUNNING
    DisposableEffect(running) {
        val before = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    Column(
        Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
            .testTag(HubTags.BENCH_SHEET),
    ) {
        when (state.phase) {
            BenchUi.Phase.RUNNING -> BenchRunning(state, onStop)
            BenchUi.Phase.RESULT -> BenchResult(state, deviceLabel, onDone, onUnit)
            BenchUi.Phase.FAILED -> BenchFailed(state, onDone)
        }
    }
}

@Composable
private fun SheetTitle(overline: String, title: String) {
    Overline(overline)
    Text(title, style = MurmurTheme.type.title, color = MurmurTheme.colors.ink, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun BenchRunning(state: BenchUi, onStop: () -> Unit) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val loc = HubTexts.locale()
    SheetTitle(stringResource(R.string.bench_overline_run), state.name)
    Text(stringResource(R.string.bench_keep_open, ProductName.NAME), style = t.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
    Column(Modifier.padding(top = 16.dp)) {
        val several = state.units.size > 1
        state.units.forEach { unit ->
            if (several) Overline(stringResource(R.string.bench_on_unit, HubTexts.unit(unit)), Modifier.padding(top = 8.dp, bottom = 4.dp))
            state.steps.filter { it.unit == unit }.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepIcon(s.status)
                    Text(
                        HubTexts.step(s.step, s.sampleId, s.audioMs),
                        style = t.body, color = if (s.status == StepStatus.TODO) c.ink3 else c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.status == StepStatus.DONE) {
                        Text("${Format.seconds(s.elapsedMs.toDouble(), loc)} ${stringResource(R.string.bench_unit_s)}", style = t.mono, color = c.ink3)
                    }
                }
            }
            state.unitErrors.firstOrNull { it.first == unit }?.let { (_, e) ->
                Text(
                    stringResource(R.string.bench_unit_failed, HubTexts.unit(unit)) + " " + HubTexts.benchError(e),
                    style = t.caption, color = c.err, modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
                )
            }
        }
    }
    state.progress?.let { LinearProgress(it, Modifier.padding(top = 16.dp)) }
    MurmurButton(
        stringResource(R.string.bench_stop), onStop, style = ButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    )
}

@Composable
private fun BenchResult(state: BenchUi, deviceLabel: DeviceLabel, onDone: () -> Unit, onUnit: (ComputeUnit) -> Unit) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val rec = state.shownRecord ?: return BenchFailed(state, onDone)
    SheetTitle(stringResource(R.string.bench_overline_result), state.name)
    Text(sentence(rec), style = t.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
    if (state.records.size > 1) {
        SegmentedControl(
            options = state.records.map { HubTexts.unit(it.unit) },
            selectedIndex = state.records.indexOfFirst { it.unit == rec.unit }.coerceAtLeast(0),
            onSelect = { onUnit(state.records[it].unit) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    if (!rec.result.valid) {
        Banner(
            kind = BannerKind.Warn,
            title = stringResource(R.string.bench_invalid_title, HubTexts.unit(rec.unit)),
            body = stringResource(R.string.bench_invalid_body, ProductName.NAME),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Big4(resultCells(rec), Modifier.padding(top = 16.dp))
    KvTable(resultRows(rec, deviceLabel), Modifier.padding(top = 12.dp))
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyText = copyText(state, rec, deviceLabel)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MurmurButton(
            stringResource(R.string.bench_copy),
            { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(state.name, copyText))) } },
            Modifier.weight(1f), style = ButtonStyle.Secondary, leadingIcon = MurmurIcons.copy,
        )
        MurmurButton(stringResource(R.string.bench_done), onDone, Modifier.weight(1f))
    }
}

@Composable
private fun BenchFailed(state: BenchUi, onDone: () -> Unit) {
    SheetTitle(stringResource(R.string.bench_overline_run), state.name)
    Banner(
        kind = BannerKind.Err,
        title = stringResource(R.string.bench_failed_title),
        body = state.error?.let { HubTexts.benchError(it) } ?: state.unitErrors.firstOrNull()?.let { HubTexts.benchError(it.second) } ?: "",
        modifier = Modifier.padding(top = 16.dp),
    )
    MurmurButton(stringResource(R.string.bench_done), onDone, Modifier.fillMaxWidth().padding(top = 20.dp))
}

/** The plain sentence under the result title (DESIGN 6.4). */
@Composable
private fun sentence(rec: BenchmarkRecord): String {
    val loc = HubTexts.locale()
    return when (val r = rec.result) {
        is BenchmarkResultData.Asr -> stringResource(R.string.bench_sentence_asr, Format.seconds(r.tailMs.toDouble(), loc))
        is BenchmarkResultData.Llm -> stringResource(
            R.string.bench_sentence_llm,
            Format.sig2(app.murmur.core.benchmark.BenchSummary.llmSecondsFor(60, r.prefillTps, r.decodeTps), loc),
        )
        is BenchmarkResultData.Ocr -> stringResource(R.string.bench_sentence_ocr, Format.seconds(r.msPerScreenshot.toDouble(), loc))
    }
}

/** Result grid (DESIGN 6.4): ASR ×, load, memory, Ran on; LLM writing, reading, first word, memory; OCR per screenshot. */
@Composable
internal fun resultCells(rec: BenchmarkRecord): List<Big4Cell> {
    val loc = HubTexts.locale()
    val s = stringResource(R.string.bench_unit_s)
    val tps = stringResource(R.string.bench_unit_tps)
    return when (val r = rec.result) {
        is BenchmarkResultData.Asr -> listOf(
            Big4Cell(Format.rtf(r.rtfx, loc) + stringResource(R.string.bench_unit_x), "", stringResource(R.string.bench_rtf)),
            Big4Cell(Format.seconds(r.loadMsCold.toDouble(), loc), s, stringResource(R.string.bench_load)),
            memCell(r.peakMemMb),
            Big4Cell(ranOn(r.ranOn), "", stringResource(R.string.bench_ran_on)),
        )
        is BenchmarkResultData.Llm -> listOf(
            Big4Cell(Format.tps(r.decodeTps, loc), tps, stringResource(R.string.bench_decode)),
            Big4Cell(Format.tps(r.prefillTps, loc), tps, stringResource(R.string.bench_prefill)),
            Big4Cell(Format.seconds(r.ttftMs, loc), s, stringResource(R.string.bench_ttft)),
            memCell(r.peakMemMb),
        )
        is BenchmarkResultData.Ocr -> detailCells(rec)
    }
}

/** "Ran on" = the engine's device name; the unit names are shown as the hub spells them. */
@Composable
private fun ranOn(raw: String): String = when (raw.lowercase()) {
    "npu" -> HubTexts.unit(ComputeUnit.NPU)
    "gpu" -> HubTexts.unit(ComputeUnit.GPU)
    "cpu" -> HubTexts.unit(ComputeUnit.CPU)
    else -> raw
}

/** Per-sample rows (ASR: "Russian, 10 s" → processing time), load time for the others, then the device. */
@Composable
private fun resultRows(rec: BenchmarkRecord, device: DeviceLabel): List<KvRow> {
    val loc = HubTexts.locale()
    val s = stringResource(R.string.bench_unit_s)
    val rows = mutableListOf<KvRow>()
    when (val r = rec.result) {
        is BenchmarkResultData.Asr -> r.perClip.forEach { clip ->
            rows += KvRow(
                stringResource(R.string.bench_sample, HubTexts.clip(clip.clip), Format.sig2(clip.audioMs / 1000.0, loc)),
                "${Format.seconds(clip.processMs.toDouble(), loc)} $s",
            )
        }
        is BenchmarkResultData.Llm -> {
            rows += KvRow(stringResource(R.string.bench_case), "${Format.seconds(r.e2eMs, loc)} $s")
            rows += KvRow(stringResource(R.string.bench_load), "${Format.seconds(r.loadMsCold.toDouble(), loc)} $s")
        }
        is BenchmarkResultData.Ocr -> Unit
    }
    if (device.name.isNotEmpty()) {
        rows += KvRow(
            stringResource(R.string.bench_device),
            device.ramGb?.let { stringResource(R.string.bench_device_value, device.name, it.toInt()) } ?: device.name,
        )
    }
    return rows
}

/** Plain-text results for "Copy results": every measured unit, numbers only (D9: no user text). */
@Composable
private fun copyText(state: BenchUi, shown: BenchmarkRecord, device: DeviceLabel): String {
    val header = stringResource(
        R.string.bench_copy_header, state.name,
        device.ramGb?.let { stringResource(R.string.bench_device_value, device.name, it.toInt()) } ?: device.name,
    )
    val lines = mutableListOf(header)
    for (rec in state.records.sortedBy { if (it.unit == shown.unit) 0 else 1 }) {
        lines += ""
        lines += HubTexts.unit(rec.unit) + if (!rec.result.valid) " · " + stringResource(R.string.detail_results_wrong, HubTexts.unit(rec.unit)) else ""
        resultCells(rec).forEach { lines += "${it.label}: ${it.number}${if (it.unit.isEmpty() || it.unit == "×") it.unit else " " + it.unit}" }
        resultRows(rec, DeviceLabel("", null)).forEach { lines += "${it.key}: ${it.value}" }
        lines += "${rec.appVersion} · ${rec.buildFingerprint}"
    }
    return lines.joinToString("\n")
}

/** "Add a model" sheet content (no frame): the form, or the preview of the resolved entry before saving. */
@Composable
internal fun ColumnScope.AddModelSheetContent(
    state: AddUi,
    onEdit: ((AddUi) -> AddUi) -> Unit,
    onCheck: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val preview = state.preview
    if (preview != null) {
        Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).testTag(HubTags.ADD_SHEET)) {
            AddPreview(preview, onSave, onBack)
        }
        return
    }
    // The fields scroll; the error and "Check model" stay in view below them (the side sheet is only 776 dp tall).
    Column(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
            .testTag(HubTags.ADD_SHEET),
    ) {
        Text(stringResource(R.string.add_title), style = t.title, color = c.ink)
        Text(stringResource(R.string.add_body, ProductName.NAME), style = t.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
        HubTextField(
            label = stringResource(R.string.add_repo), value = state.repo, onValueChange = { v -> onEdit { it.copy(repo = v) } },
            hint = stringResource(R.string.add_repo_hint),
            error = if (state.repoInvalid) stringResource(R.string.add_repo_invalid) else null,
            modifier = Modifier.padding(top = 16.dp), fieldTag = "add-repo",
        )
        HubTextField(
            label = stringResource(R.string.add_revision), value = state.revision, onValueChange = { v -> onEdit { it.copy(revision = v) } },
            hint = stringResource(R.string.add_revision_hint), modifier = Modifier.padding(top = 12.dp), fieldTag = "add-revision",
        )
        Text(stringResource(R.string.add_kind), style = t.labelSm, color = c.ink2, modifier = Modifier.padding(top = 16.dp))
        Column(Modifier.padding(top = 4.dp)) {
            ListRow(
                title = stringResource(R.string.add_kind_speech), subtitle = stringResource(R.string.add_kind_speech_sub),
                trailing = RowTrailing.Radio(state.kind == ModelKind.ASR), onClick = { onEdit { it.copy(kind = ModelKind.ASR) } },
            )
            ListRow(
                title = stringResource(R.string.add_kind_cleanup), subtitle = stringResource(R.string.add_kind_cleanup_sub),
                trailing = RowTrailing.Radio(state.kind == ModelKind.LLM), onClick = { onEdit { it.copy(kind = ModelKind.LLM) } },
            )
        }
        if (state.kind == ModelKind.LLM) {
            HubTextField(
                label = stringResource(R.string.add_file), value = state.file, onValueChange = { v -> onEdit { it.copy(file = v) } },
                hint = stringResource(R.string.add_file_hint), modifier = Modifier.padding(top = 12.dp), fieldTag = "add-file",
            )
            Text(stringResource(R.string.add_format), style = t.labelSm, color = c.ink2, modifier = Modifier.padding(top = 16.dp))
            Column(Modifier.padding(top = 4.dp)) {
                FormatRow(stringResource(R.string.add_format_auto), stringResource(R.string.add_format_auto_sub), state.format == PromptFormat.AUTO) { onEdit { it.copy(format = PromptFormat.AUTO) } }
                FormatRow(stringResource(R.string.add_format_ft, ProductName.NAME), stringResource(R.string.add_format_ft_sub), state.format == PromptFormat.FINETUNE) { onEdit { it.copy(format = PromptFormat.FINETUNE) } }
                FormatRow(stringResource(R.string.add_format_std), stringResource(R.string.add_format_std_sub), state.format == PromptFormat.STANDARD) { onEdit { it.copy(format = PromptFormat.STANDARD) } }
            }
        }
    }
    Column(Modifier.padding(horizontal = 24.dp)) {
        state.error?.let { e ->
            Banner(BannerKind.Err, stringResource(R.string.add_err_title), HubTexts.addError(e), Modifier.padding(top = 16.dp))
        }
        MurmurButton(
            text = stringResource(if (state.busy) R.string.add_checking else R.string.add_check),
            onClick = onCheck, loading = state.busy, enabled = state.repo.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag("add-check"),
        )
    }
}

@Composable
private fun FormatRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    ListRow(title = title, subtitle = sub, trailing = RowTrailing.Radio(selected), onClick = onClick)
}

@Composable
private fun AddPreview(entry: ModelEntry, onSave: () -> Unit, onBack: () -> Unit) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    val loc = HubTexts.locale()
    val artifact = entry.artifacts.firstOrNull()
    Overline(stringResource(R.string.add_preview_title))
    Text(entry.displayName, style = t.title, color = c.ink, modifier = Modifier.padding(top = 6.dp))
    val rows = buildList {
        add(KvRow(stringResource(R.string.detail_source), entry.baseRepo ?: artifact?.files?.firstOrNull()?.repo.orEmpty(), mono = true))
        entry.llm?.let {
            add(KvRow(stringResource(R.string.add_preview_template), it.template, mono = true))
            add(KvRow(stringResource(R.string.add_preview_format), it.promptProfile, mono = true))
        }
        artifact?.let {
            add(KvRow(stringResource(R.string.detail_download), Format.sizeText(it.downloadBytes, loc)))
            add(KvRow(stringResource(R.string.add_preview_runtime), it.runtime, mono = true))
            add(KvRow(stringResource(R.string.add_preview_files), it.files.joinToString("\n") { f -> f.name }, mono = true))
        }
    }
    KvTable(rows, Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MurmurButton(stringResource(R.string.add_back), onBack, Modifier.weight(1f), style = ButtonStyle.Secondary)
        MurmurButton(stringResource(R.string.add_save), onSave, Modifier.weight(1f).testTag("add-save"))
    }
}
