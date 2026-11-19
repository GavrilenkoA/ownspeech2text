package app.murmur.feature.models

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.model.ModelKind
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.floor

/**
 * `models`, `models-llm`, `models-storage`, `model-detail`, `bench-run`, `bench-result`, `bench-result-llm` inside the
 * prototype frame at 1 dp = 2 px (same size as `docs/design/screens/<frame>--{cover,inner}.png`), written to
 * `<externalFiles>/models-shots/` for the pixel diff; the pieces without a frame (unit picker, Add-a-model sheet,
 * Support models) go to `<externalFiles>/models-shots/no-frame/`. Each shot also checks: no text overflow, and every
 * clickable ≥ 48 × 48 dp at the device's real density (outside the frame).
 */
@RunWith(AndroidJUnit4::class)
class ModelsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wide = context.resources.configuration.screenWidthDp >= 600
    private val device = if (wide) Device.Inner else Device.Cover
    private val suffix = if (wide) "inner" else "cover"
    private val d = FrameData

    @Composable
    private fun List(tab: HubTab, input: HubInput, selected: ModelEntry?) = ModelsListContent(
        list = HubLogic.list(tab, input), selected = selected?.id, onTab = {}, onOpen = {}, onSelect = {}, onSelectNone = {},
        onDownload = {}, onPause = {}, onResume = {}, onBenchmark = {}, onDelete = {}, onAdd = {},
    )

    @Composable
    private fun Detail(e: ModelEntry, input: HubInput, cover: Boolean) = ModelDetailContent(
        view = HubLogic.detail(e, input), kind = e.kind, onBack = if (cover) ({}) else null, nowMs = d.nowMs,
        onUse = {}, onDownload = {}, onPause = {}, onResume = {}, onDelete = {}, onPin = {}, onBenchmark = {},
    )

    /** The models screen as the shell shows it: list alone on the cover, list + detail of [detail] on the inner. */
    @Composable
    private fun Screen(tab: HubTab, input: HubInput, detail: ModelEntry) {
        if (device.wide) InnerPanes(list = { HeaderedList(tab, input, detail) }, detail = { Detail(detail, input, cover = false) })
        else HeaderedList(tab, input, null)
    }

    @Composable
    private fun HeaderedList(tab: HubTab, input: HubInput, selected: ModelEntry?) {
        androidx.compose.foundation.layout.Column {
            app.murmur.ui.design.components.ScreenHeader(androidx.compose.ui.res.stringResource(R.string.models_title))
            List(tab, input, selected)
        }
    }

    @Test
    fun models() = shot("models") { HubFrame(device) { Screen(HubTab.SPEECH, d.speech, d.asrInUse) } }

    @Test
    fun modelsLlm() = shot("models-llm") { HubFrame(device) { Screen(HubTab.CLEANUP, d.speech, d.llmInUse) } }

    @Test
    fun modelsStorage() = shot("models-storage") { HubFrame(device) { Screen(HubTab.CLEANUP, d.storage, d.llmInUse) } }

    /** Cover only in the design (on the inner display the detail is the right pane of `models`). */
    @Test
    fun modelDetail() = shot("model-detail") {
        if (device.wide) HubFrame(device) { Screen(HubTab.SPEECH, d.speech, d.asrInUse) }
        else HubFrame(device, nav = false) { Detail(d.asrInUse, d.speech, cover = true) }
    }

    @Test
    fun benchRun() = shot("bench-run") {
        val noRecords = d.input(d.speech.states, records = emptyList())
        HubFrame(device, nav = device.wide, overlay = {
            SheetOverlay(device) { BenchSheetContent(d.benchRun(d.asrInUse), d.deviceLabel, {}, {}, {}) }
        }) {
            if (device.wide) Screen(HubTab.SPEECH, d.speech, d.asrInUse) else Detail(d.asrInUse, noRecords, cover = true)
        }
    }

    @Test
    fun benchResult() = shot("bench-result") {
        HubFrame(device, nav = device.wide, overlay = {
            SheetOverlay(device) { BenchSheetContent(d.benchResult(d.asrInUse, d.asrRecord), d.deviceLabel, {}, {}, {}) }
        }) {
            if (device.wide) Screen(HubTab.SPEECH, d.speech, d.asrInUse) else Detail(d.asrInUse, d.speech, cover = true)
        }
    }

    @Test
    fun benchResultLlm() = shot("bench-result-llm") {
        HubFrame(device, nav = device.wide, overlay = {
            SheetOverlay(device) { BenchSheetContent(d.benchResult(d.llmInUse, d.llmRecord), d.deviceLabel, {}, {}, {}) }
        }) {
            if (device.wide) Screen(HubTab.CLEANUP, d.speech, d.llmInUse) else Detail(d.llmInUse, d.speech, cover = true)
        }
    }

    // ------------------------------------------------------------------ pieces without a frame (design owner)

    /** The whole detail page with the unit picker (Auto / NPU / CPU, CPU pinned but not downloaded). */
    @Test
    fun noFrameUnitPicker() = shot("no-frame/unit-picker", tall = true) {
        val pinned = Settings(unitPreference = mapOf(d.asrInUse.id.value to UnitPreference.Pinned(app.murmur.core.model.ComputeUnit.CPU)))
        val onlyNpu = d.speech.states.filterKeys { k -> d.asrInUse.artifacts.none { it.id == k } || k == d.asrInUse.artifacts.first().id }
        val inp = d.speech.copy(settings = pinned, states = onlyNpu)
        HubFrame(Device.TallCover, nav = false) { Detail(d.asrInUse, inp, cover = true) }
    }

    /** Speech list scrolled out in full: the "Support models" group, the storage line and "Add a model". */
    @Test
    fun noFrameSupportModels() = shot("no-frame/support-models", tall = true) {
        HubFrame(Device.TallCover, nav = false) { HeaderedList(HubTab.SPEECH, d.speech, null) }
    }

    @Test
    fun noFrameAddModel() = shot("no-frame/add-model") {
        HubFrame(device, overlay = { SheetOverlay(device) { AddModelSheetContent(AddUi(ModelKind.LLM, repo = "owner/Model-GGUF"), {}, {}, {}, {}) } }) {
            Screen(HubTab.CLEANUP, d.speech, d.llmInUse)
        }
    }

    @Test
    fun noFrameAddModelError() = shot("no-frame/add-model-error") {
        val err = AddUi(ModelKind.LLM, repo = "owner/Model-GGUF", error = app.murmur.core.model.MurmurError.CatalogInvalid("unsupported GGUF architecture 'gemma3'; supported: qwen35"))
        HubFrame(device, overlay = { SheetOverlay(device) { AddModelSheetContent(err, {}, {}, {}, {}) } }) {
            Screen(HubTab.CLEANUP, d.speech, d.llmInUse)
        }
    }

    @Test
    fun noFrameAddModelPreview() = shot("no-frame/add-model-preview") {
        val entry = d.llmReady.copy(id = app.murmur.core.model.ModelId("custom-preview"), displayName = "Model-GGUF", origin = app.murmur.core.catalog.Origin.CUSTOM,
            artifacts = d.llmReady.artifacts.take(1))
        HubFrame(device, overlay = { SheetOverlay(device) { AddModelSheetContent(AddUi(ModelKind.LLM, repo = "owner/Model-GGUF", preview = entry), {}, {}, {}, {}) } }) {
            Screen(HubTab.CLEANUP, d.speech, d.llmInUse)
        }
    }

    // ------------------------------------------------------------------ harness

    private fun shot(name: String, tall: Boolean = false, content: @Composable () -> Unit) {
        // One composition per test: the 2x frame for the capture, then the same content at the real density for the
        // touch-target check.
        var framed by mutableStateOf(true)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalUseFrameDensity provides framed) { content() }
        }
        rule.waitForIdle()
        val file = save(if (tall) name else "$name--$suffix", tall)
        assertTrue("$file written", file.length() > 0)
        assertNoTextOverflow(name)
        rule.runOnIdle { framed = false }
        rule.waitForIdle()
        assertTouchTargets(name)
    }

    private fun save(name: String, tall: Boolean): File {
        val bmp: Bitmap = rule.onNodeWithTag(FRAME_TAG).captureToImage().asAndroidBitmap()
        if (!tall) {
            assertEquals("frame width px", device.width * 2, bmp.width)
            assertEquals("frame height px", device.height * 2, bmp.height)
        }
        val file = File(requireNotNull(context.getExternalFilesDir(null)), "models-shots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$name: ${bmp.width}x${bmp.height} -> $file")
        return file
    }

    private fun assertTouchTargets(name: String) {
        val density = context.resources.displayMetrics.density
        val min = floor(48 * density) - 0.5f
        val nodes = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        val small = nodes.filter { it.size.width < min || it.size.height < min }.map { describe(it, density) }
        Log.i(TAG, "$name: ${nodes.size} clickables")
        assertTrue("clickables below 48 dp in $name:\n" + small.joinToString("\n"), small.isEmpty())
    }

    private fun assertNoTextOverflow(name: String) {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        val bad = mutableListOf<String>()
        rule.runOnIdle {
            for (n in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val r = results.firstOrNull() ?: continue
                val overflow = r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
                    (0 until r.lineCount).any { i -> r.isLineEllipsized(i) || r.getLineRight(i) - r.getLineLeft(i) > r.size.width + 1f }
                if (overflow) bad += describe(n, 2f)
            }
        }
        Log.i(TAG, "$name: ${nodes.size} texts checked")
        assertTrue("text overflow in $name:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun describe(n: SemanticsNode, density: Float): String {
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        return "#${n.id} '$text' ${n.size.width / density}x${n.size.height / density} dp"
    }

    private companion object {
        const val TAG = "ModelsShot"
    }
}
