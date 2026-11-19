package app.murmur.feature.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.Settings
import app.murmur.testing.FakeBenchmarkRunner
import app.murmur.testing.FakeCatalogRepository
import app.murmur.testing.FakeCustomModelResolver
import app.murmur.testing.FakeDeviceProfileRepository
import app.murmur.testing.FakeInstalledModelsRepository
import app.murmur.testing.FakeUnitHealth
import app.murmur.testing.InMemoryBenchmarkRepository
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.ui.design.MurmurTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real screens over the :testing fakes (Fold7 profile): selection, unit pin, delete, benchmark, add a model. */
@RunWith(AndroidJUnit4::class)
class ModelsInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private fun s(id: Int, vararg args: Any) = res.getString(id, *args)

    private val catalog = CatalogParser.bundled()
    private val asr = catalog.model(catalog.defaults.asr.getValue("ru+en"))!!
    private val llm = catalog.model(catalog.defaults.llm!!)!!
    private val otherLlm = catalog.models.first { it.kind == ModelKind.LLM && it.id != llm.id && !it.noRussian && it.artifacts.size > 1 }

    private inner class World(installed: Set<ArtifactId>, settings: Settings = Settings(), stepMs: Long = 150) {
        val installed = FakeInstalledModelsRepository(catalog.models.flatMap { m -> m.artifacts.map { it.id } }.filterNot { it in installed }.toMutableSet())
        val benchmarks = InMemoryBenchmarkRepository()
        val settings = InMemorySettingsRepository(settings)
        val runner = FakeBenchmarkRunner(stepMs = stepMs, repo = benchmarks)
        val custom = FakeCustomModelResolver()
        val catalogRepo = FakeCatalogRepository()
        val deps = ModelsDeps(
            catalogRepo, this.installed, benchmarks, runner, this.settings, custom,
            MutableStateFlow(FakeDeviceProfileRepository.FOLD7), FakeUnitHealth(), deviceName = "Test phone",
        )
    }

    private val allAsr get() = asr.artifacts.map { it.id }.toSet()

    @Test
    fun everyCatalogModelDetailRenders() {
        val w = World(catalog.models.flatMap { m -> m.artifacts.map { it.id } }.toSet())
        var id by mutableStateOf(catalog.models.first().id)
        rule.setContent { MurmurTheme(darkTheme = false) { ModelDetail(w.deps, id, onBack = {}) } }
        assertEquals(27, catalog.models.size)
        for (m in catalog.models) {
            rule.runOnIdle { id = m.id }
            rule.waitForIdle()
            rule.onNode(hasText(m.displayName) and !hasTestTag(HubTags.row(m.id)), useUnmergedTree = true).assertExists()
            rule.onNodeWithTag(HubTags.ACTION_BAR).assertExists()
        }
    }

    @Test
    fun noneAndRadioSelectTheCleanupModel() {
        val w = World(allAsr + llm.artifacts.map { it.id } + otherLlm.artifacts.map { it.id })
        rule.setContent { MurmurTheme(darkTheme = false) { ModelsScreen(w.deps, onOpenModel = {}) } }
        rule.onNodeWithText(s(R.string.models_tab_cleanup)).performClick()
        rule.onNodeWithTag(HubTags.NONE_ROW).performClick()
        rule.waitForIdle()
        assertEquals(CleanupChoice.None, w.settings.settings.value.cleanup)
        rule.onNode(hasContentDescriptionExactly(s(R.string.models_cd_select, otherLlm.displayName))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(CleanupChoice.Model(otherLlm.id), w.settings.settings.value.cleanup)
    }

    @Test
    fun theUnitPickerPinsPerModel() {
        val w = World(allAsr)
        rule.setContent { MurmurTheme(darkTheme = false) { ModelDetail(w.deps, asr.id, onBack = {}) } }
        rule.onNodeWithTag(HubTags.unitOption("CPU")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(UnitPreference.Pinned(ComputeUnit.CPU), w.settings.settings.value.unitPreference[asr.id.value])
        rule.onNodeWithTag(HubTags.unitOption("AUTO")).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(w.settings.settings.value.unitPreference.isEmpty())
    }

    @Test
    fun deletingTheModelInUseAsksThenSwitches() {
        val w = World(allAsr + llm.artifacts.map { it.id } + otherLlm.artifacts.map { it.id })
        rule.setContent { MurmurTheme(darkTheme = false) { ModelDetail(w.deps, llm.id, onBack = {}) } }
        rule.onNodeWithText(s(R.string.detail_delete)).performClick()
        rule.onNodeWithText(s(R.string.delete_title, llm.displayName)).assertIsDisplayed()
        rule.onNode(hasText(s(R.string.delete_confirm)) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(CleanupChoice.Model(otherLlm.id), w.settings.settings.value.cleanup)
        assertTrue(llm.artifacts.none { w.installed.isInstalled(it.id) })
    }

    @Test
    fun benchmarkSheetRunsAndShowsTheResult() {
        // 2 units x 4 steps x 1 s: long enough to see the running checklist.
        val w = World(allAsr, stepMs = 1_000)
        rule.setContent { MurmurTheme(darkTheme = false) { ModelDetail(w.deps, asr.id, onBack = {}) } }
        rule.onNodeWithText(s(R.string.bench_run)).performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(HubTags.BENCH_SHEET)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.bench_overline_run).uppercase()).assertExists()
        rule.onNodeWithText(s(R.string.bench_step_load)).assertExists()
        rule.onNodeWithText(s(R.string.bench_stop)).assertExists()
        rule.waitUntil(30_000) { rule.onAllNodes(hasText(s(R.string.bench_overline_result).uppercase())).fetchSemanticsNodes().isNotEmpty() }
        // Two units measured (NPU and CPU on the Fold7 profile): the segmented control switches between them.
        val inSheet = hasAnyAncestor(hasTestTag(HubTags.BENCH_SHEET))
        assertTrue(rule.onAllNodes(hasText(s(R.string.unit_npu)) and inSheet).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText(s(R.string.unit_cpu)) and inSheet).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(s(R.string.bench_done)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(s(R.string.bench_run_again)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun addAModelFromHuggingFace() {
        val w = World(allAsr)
        rule.setContent { MurmurTheme(darkTheme = false) { ModelsScreen(w.deps, onOpenModel = {}) } }
        rule.onNodeWithTag(HubTags.ADD).performScrollTo().performClick()
        rule.onNodeWithTag("add-repo").performTextInput("someone/Speech-Model")
        rule.onNodeWithTag("add-check").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("add-save")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("add-save").performScrollTo().performClick()
        rule.waitForIdle()
        val added = w.catalogRepo.catalog.value.models.single { it.origin == app.murmur.core.catalog.Origin.CUSTOM }
        assertEquals(ModelKind.ASR, added.kind)
        assertEquals("someone/Speech-Model", w.custom.requests.single().repo)
        rule.onNodeWithTag(HubTags.row(added.id)).performScrollTo().assertIsDisplayed()
    }

    private fun hasContentDescriptionExactly(text: String) = androidx.compose.ui.test.hasContentDescription(text, substring = false)
}
