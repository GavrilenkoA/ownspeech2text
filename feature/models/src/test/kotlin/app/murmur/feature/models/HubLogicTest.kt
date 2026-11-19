package app.murmur.feature.models

import app.murmur.core.catalog.Blocker
import app.murmur.core.catalog.Status
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.InstallState
import app.murmur.core.store.Settings
import app.murmur.feature.models.HubFixtures.catalog
import app.murmur.feature.models.HubFixtures.cpuArtifact
import app.murmur.feature.models.HubFixtures.emulator
import app.murmur.feature.models.HubFixtures.fold7
import app.murmur.feature.models.HubFixtures.input
import app.murmur.feature.models.HubFixtures.installed
import app.murmur.feature.models.HubFixtures.npuArtifact
import app.murmur.testing.FakeUnitHealth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubLogicTest {
    private val asr = HubFixtures.npuAndCpuAsr

    @Test
    fun emulatorProfileBlocksTheNpuArtifactAndChoosesCpu() {
        // Nothing installed: the planned download is the CPU artifact; the NPU one is blocked (no SM8750).
        val fresh = HubLogic.units(asr, input(emulator))
        assertNull(fresh.chosen)
        assertEquals(cpuArtifact(asr).id, fresh.planned!!.artifact.id)
        val npu = fresh.candidates.single { it.unit == ComputeUnit.NPU }
        assertFalse(npu.usable)
        assertEquals(Blocker.WRONG_SOC, npu.blocker)
        // Installed: CPU is chosen and is the filled badge; the blocked NPU is not offered on the row.
        val inp = input(emulator, installed(cpuArtifact(asr).id))
        val u = HubLogic.units(asr, inp)
        assertEquals(ComputeUnit.CPU, u.chosen!!.unit)
        assertEquals(listOf(UnitBadge(ComputeUnit.CPU, true)), HubLogic.row(asr, inp).units)
        val runsOn = HubLogic.detail(asr, inp).facts.filterIsInstance<Fact.RunsOn>().single().lines
        assertEquals(listOf(ComputeUnit.NPU, ComputeUnit.CPU), runsOn.map { it.unit })
        assertEquals(Blocker.WRONG_SOC, runsOn[0].blocker)
        assertEquals(listOf("SM8750"), runsOn[0].socs)
        assertTrue(runsOn[1].chosen)
    }

    @Test
    fun fold7ProfileChoosesNpu() {
        val both = input(fold7, installed(npuArtifact(asr).id, cpuArtifact(asr).id))
        val u = HubLogic.units(asr, both)
        assertEquals(ComputeUnit.NPU, u.chosen!!.unit)
        assertEquals(npuArtifact(asr).id, u.chosen!!.artifact.id)
        assertEquals(listOf(UnitBadge(ComputeUnit.NPU, true), UnitBadge(ComputeUnit.CPU, false)), HubLogic.row(asr, both).units)
        // Nothing installed: a download fetches the NPU build.
        assertEquals(npuArtifact(asr).id, HubLogic.units(asr, input(fold7)).planned!!.artifact.id)
        // Unit picker: Auto + NPU + CPU.
        assertEquals(listOf(null, ComputeUnit.NPU, ComputeUnit.CPU), HubLogic.detail(asr, both).unitOptions.map { it.unit })
    }

    @Test
    fun npuMarkedFailedChoosesCpuWithTheFailedLine() {
        val health = FakeUnitHealth()
        runBlocking { health.markFailed(npuArtifact(asr).id, ComputeUnit.NPU, "engine.load.asr") }
        val inp = input(fold7, installed(npuArtifact(asr).id, cpuArtifact(asr).id), health = health)
        assertEquals(ComputeUnit.CPU, HubLogic.units(asr, inp).chosen!!.unit)
        val d = HubLogic.detail(asr, inp)
        assertTrue(d.notes.toString(), DetailNote.UnitFailed(ComputeUnit.NPU, ComputeUnit.CPU) in d.notes)
        assertNull("CPU artifact installed: no extra download", d.cpuFallbackDownload)
        // CPU artifact missing: "Download the CPU version (N MB)".
        val onlyNpu = input(fold7, installed(npuArtifact(asr).id), health = health)
        assertEquals(cpuArtifact(asr).id, HubLogic.detail(asr, onlyNpu).cpuFallbackDownload!!.id)
    }

    @Test
    fun aPinnedUnitWhoseArtifactIsMissingIsOfferedForDownload() {
        val s = Settings(unitPreference = mapOf(asr.id.value to UnitPreference.Pinned(ComputeUnit.CPU)))
        val inp = input(fold7, installed(npuArtifact(asr).id), settings = s)
        val u = HubLogic.units(asr, inp)
        // The resolver's rule: a pinned unit with nothing installed falls back to Auto, so NPU still runs.
        assertEquals(ComputeUnit.NPU, u.chosen!!.unit)
        assertEquals(cpuArtifact(asr).id, HubLogic.detail(asr, inp).pinnedDownload!!.id)
        val options = HubLogic.detail(asr, inp).unitOptions
        assertTrue(options.single { it.unit == ComputeUnit.CPU }.selected)
        assertFalse(options.single { it.unit == ComputeUnit.CPU }.installed)
    }

    @Test
    fun anInvalidBenchmarkRecordShowsResultsLookWrongAndDoesNotChangeTheChosenUnit() {
        val states = installed(npuArtifact(asr).id, cpuArtifact(asr).id)
        val before = HubLogic.units(asr, input(fold7, states)).chosen!!.unit
        // The NPU run is faster but its text is wrong: it gets no score (ADR-071).
        val records = listOf(HubFixtures.asrRecord(npuArtifact(asr).id, ComputeUnit.NPU, rtfx = 40.0, valid = false))
        val inp = input(fold7, states, records = records)
        assertEquals(before, HubLogic.units(asr, inp).chosen!!.unit)
        val d = HubLogic.detail(asr, inp)
        assertEquals(listOf(DetailNote.ResultsWrong(ComputeUnit.NPU)), d.notes.filterIsInstance<DetailNote.ResultsWrong>())
        // A newer valid run replaces the label.
        val fixed = records + HubFixtures.asrRecord(npuArtifact(asr).id, ComputeUnit.NPU, rtfx = 30.0, valid = true, at = 2_000)
        assertTrue(HubLogic.detail(asr, input(fold7, states, records = fixed)).notes.none { it is DetailNote.ResultsWrong })
        // Valid scores do decide: a measured fast CPU beats an unmeasured NPU.
        val cpuFast = listOf(HubFixtures.asrRecord(cpuArtifact(asr).id, ComputeUnit.CPU, rtfx = 20.0))
        assertEquals(ComputeUnit.CPU, HubLogic.units(asr, input(fold7, states, records = cpuFast)).chosen!!.unit)
    }

    @Test
    fun previewOnlyModelsCannotBeSelected() {
        val e = HubFixtures.previewOnly
        val inp = input(emulator, installed(e.artifacts.map { it.id }), settings = Settings(asrModel = e.id))
        val row = HubLogic.row(e, inp)
        assertTrue(row.previewOnly)
        assertFalse(row.selectable)
        assertFalse(row.selected)
        assertEquals(RowState.Ready, row.state)
        val d = HubLogic.detail(e, inp)
        assertEquals(DetailAction.None, d.action)
        assertTrue(DetailNote.PreviewOnly in d.notes)
        // The speech model of record falls back to the catalog default.
        assertEquals(catalog.defaults.asr["ru+en"], HubLogic.speechModel(inp))
    }

    @Test
    fun noneSelectionAndCleanupModelOfRecord() {
        val llm = HubFixtures.defaultLlm
        val inp = input(emulator, installed(llm.artifacts.map { it.id }))
        assertEquals(RowState.InUse, HubLogic.row(llm, inp).state)
        val none = input(emulator, installed(llm.artifacts.map { it.id }), settings = Settings(cleanup = CleanupChoice.None))
        assertTrue(HubLogic.list(HubTab.CLEANUP, none).noneSelected)
        assertEquals(RowState.Ready, HubLogic.row(llm, none).state)
        assertNull(HubLogic.cleanupModel(none))
    }

    @Test
    fun selectedButNotDownloadedShowsTheDownloadPill() {
        val llm = HubFixtures.defaultLlm
        val row = HubLogic.row(llm, input(emulator))
        assertTrue(row.selected)
        assertTrue(row.state is RowState.NotDownloaded)
        assertFalse(row.selectable)
    }

    @Test
    fun everyCatalogModelProducesARowAndADetail() {
        assertEquals(27, catalog.models.size)
        val all = catalog.models.flatMap { it.artifacts.map { a -> a.id } }
        for (device in listOf(null, emulator, fold7)) for (states in listOf(emptyMap(), installed(all))) {
            val inp = input(device, states)
            for (e in catalog.models) {
                val row = HubLogic.row(e, inp)
                val d = HubLogic.detail(e, inp)
                assertEquals(e.displayName, row.name)
                assertTrue(e.id.value, d.facts.any { it is Fact.License })
                if (e.status != Status.AVAILABLE) assertEquals(e.id.value, RowState.InTraining, row.state)
                if (e.asr?.previewOnly == true) assertFalse(row.selectable)
            }
            for (tab in HubTab.entries) HubLogic.list(tab, inp)
        }
    }

    @Test
    fun cleanupListPutsModelsInTrainingLastAndSpeechListHasTheSupportModels() {
        val inp = input(emulator)
        val cleanup = HubLogic.list(HubTab.CLEANUP, inp)
        val firstTraining = cleanup.rows.indexOfFirst { it.state == RowState.InTraining }
        assertTrue(firstTraining > 0)
        assertTrue(cleanup.rows.drop(firstTraining).all { it.state == RowState.InTraining })
        assertTrue(cleanup.support.isEmpty())
        val speech = HubLogic.list(HubTab.SPEECH, inp)
        assertEquals(setOf(ModelKind.VAD, ModelKind.OCR_DET, ModelKind.OCR_REC), speech.support.map { it.kind }.toSet())
        assertEquals(3, speech.support.size)
        assertTrue(speech.rows.all { it.kind == ModelKind.ASR })
    }

    @Test
    fun rowFactsComeFromTheCatalog() {
        val inp = input(fold7)
        val qwenAsr = catalog.models.first { it.kind == ModelKind.ASR && it.languages.count == 30 }
        val row = HubLogic.row(qwenAsr, inp)
        assertEquals("938M", row.params)
        assertEquals(listOf("RU", "EN", "+28"), row.languages)
        val noRu = catalog.models.first { it.kind == ModelKind.LLM && it.noRussian && it.languages.count == 8 }
        val r2 = HubLogic.row(noRu, inp)
        assertTrue(r2.noRussian)
        assertEquals(listOf("EN", "+7"), r2.languages)
        assertEquals("1.17B", r2.params)
        val approx = catalog.models.first { it.paramsLabel?.startsWith("~") == true }
        assertTrue(HubLogic.row(approx, inp).params!!.startsWith("~"))
    }

    @Test
    fun downloadPlanFollowsThePackRules() {
        // Batch ASR on the emulator: its CPU artifact + the VAD + the OCR pair ("Use what's on screen" is on).
        val plan = HubLogic.downloadPlan(asr, input(emulator))
        val expected = listOf(cpuArtifact(asr).id) +
            listOf(catalog.defaults.vad, catalog.defaults.ocrDet, catalog.defaults.ocrRec).map { catalog.model(it)!!.artifacts.first().id }
        assertEquals(expected, plan)
        // Already installed artifacts are skipped; screen context off drops the OCR pair.
        val vad = catalog.model(catalog.defaults.vad)!!.artifacts.first().id
        val plan2 = HubLogic.downloadPlan(asr, input(emulator, installed(vad), settings = Settings(useScreen = false)))
        assertEquals(listOf(cpuArtifact(asr).id), plan2)
        // A forced artifact replaces the requested one (CPU version after an NPU failure on the Fold7).
        val forced = HubLogic.downloadPlan(asr, input(fold7, settings = Settings(useScreen = false)), cpuArtifact(asr))
        assertEquals(listOf(cpuArtifact(asr).id, vad), forced)
    }

    @Test
    fun lowStorageShowsTheBannerOnTheModelsTab() {
        val llm = HubLogic.list(HubTab.CLEANUP, input()).rows.first { it.state is RowState.NotDownloaded }.let { catalog.model(it.id)!! }
        val a = llm.artifacts.first()
        val inp = input(states = mapOf(a.id to InstallState.Paused(0, a.downloadBytes, MurmurError.LowStorage)),
            storage = app.murmur.core.store.StorageInfo(2_400_000_000, 900_000_000))
        val p = HubLogic.list(HubTab.CLEANUP, inp).storageProblem!!
        assertEquals(llm.displayName, p.model)
        assertEquals(a.downloadBytes, p.neededBytes)
        assertEquals(900_000_000, p.freeBytes)
        assertNull(HubLogic.list(HubTab.SPEECH, inp).storageProblem)
        assertTrue(HubLogic.row(llm, inp).state is RowState.Paused)
    }

    @Test
    fun rowStatesFollowTheInstallState() {
        val a = cpuArtifact(asr)
        fun state(s: InstallState) = HubLogic.row(asr, input(emulator, mapOf(a.id to s))).state
        assertEquals(RowState.Downloading(89, 144, 12), state(InstallState.Downloading(89, 144, 12)))
        assertEquals(RowState.Checking, state(InstallState.Verifying))
        assertEquals(RowState.Preparing(0.4f, ComputeUnit.CPU), state(InstallState.Preparing(0.4f)))
        assertEquals(RowState.Failed(MurmurError.ChecksumMismatch, a.downloadBytes), state(InstallState.Failed(MurmurError.ChecksumMismatch)))
        assertEquals(RowState.InUse, state(InstallState.Installed("/m", 1)))
        // A model nothing can run on this phone: NPU-only builds on the emulator.
        val npuOnly = catalog.models.first { it.kind == ModelKind.ASR && it.artifacts.isNotEmpty() && it.artifacts.all { a -> a.units == listOf(ComputeUnit.NPU) } }
        val st = HubLogic.row(npuOnly, input(emulator)).state
        assertTrue(st.toString(), st is RowState.Unavailable)
        assertNotNull((st as RowState.Unavailable).blocker)
    }

    @Test
    fun nextAfterDeleteIsTheNextDownloadedModelOfTheSameKind() {
        val llm = HubFixtures.defaultLlm
        val other = HubFixtures.otherLlm
        val inp = input(emulator, installed((llm.artifacts + other.artifacts).map { it.id }))
        assertEquals(other.id, HubLogic.nextAfterDelete(llm, inp))
        assertNull(HubLogic.nextAfterDelete(llm, input(emulator, installed(llm.artifacts.map { it.id }))))
    }
}
