package app.murmur.feature.models

import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.benchmark.BenchStep
import app.murmur.core.benchmark.PlannedStep
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormatAndBenchTest {
    private val en = Locale.US
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun sizesAndParamsAsInTheFrames() {
        assertEquals("938M", Format.params(938_008_576, en))
        assertEquals("72M", Format.params(71_697_827, en))
        assertEquals("1.73B", Format.params(1_728_801_536, en))
        assertEquals("2,27B", Format.params(2_274_069_824, ru))
        assertEquals("590 MB", Format.sizeText(590_000_000, en))
        assertEquals("1.10 GB", Format.sizeText(1_099_627_808, en))
        assertEquals("1.28 GB", Format.sizeText(1_280_835_840, en))
        assertEquals("2 MB", Format.sizeText(2_313_101, en))
        assertEquals("2.4 GB", Format.storageText(2_400_000_000, en))
        assertEquals("181 GB", Format.storageText(181_000_000_000, en))
        assertEquals("0.9 GB", Format.storageText(900_000_000, en))
        assertEquals("89 MB" to "144 MB", Format.progressPair(89_000_000, 144_000_000, en))
        assertEquals("12.3 MB/s", Format.speed(12_300_000, en))
    }

    @Test
    fun benchmarkNumbers() {
        assertEquals("0.8", Format.seconds(800.0, en))
        assertEquals("0.21", Format.seconds(210.0, en))
        assertEquals("2.1", Format.seconds(2_130.0, en))
        assertEquals("14", Format.seconds(14_250.0, en))
        assertEquals("0,4", Format.seconds(400.0, ru))
        assertEquals("14", Format.rtf(14.2, en))
        assertEquals("3.2", Format.rtf(3.24, en))
        assertEquals("31", Format.tps(30.9, en))
        assertEquals("2,597", Format.tps(2_597.0, en))
        assertEquals("720" to "MB", Format.memory(720, en))
        assertEquals("1.5" to "GB", Format.memory(1_536, en))
        assertEquals(12L, Format.ramGb(11_500))
        assertEquals(3L, Format.ramGb(3_072))
    }

    private fun ui() = BenchUi(ModelId("m"), "M", ModelKind.ASR, Surface.DETAIL)

    private fun record(unit: ComputeUnit, rtfx: Double, valid: Boolean) = BenchmarkRecord(
        ArtifactId("a"), unit, "1", "fp", 0, BenchmarkResultData.Asr(rtfx, 1, 1, 1, 1, unit.name, emptyList(), valid),
    )

    @Test
    fun reducerFollowsThePlanAndTheProgress() {
        var s = ui()
        assertNull(s.progress)
        s = BenchReducer.reduce(s, BenchProgress.Planned(listOf(
            PlannedStep(ComputeUnit.CPU, BenchStep.LOAD),
            PlannedStep(ComputeUnit.CPU, BenchStep.SAMPLE_RU, "ru", 10_000),
            PlannedStep(ComputeUnit.CPU, BenchStep.SAMPLE_EN, "en", 11_000),
            PlannedStep(ComputeUnit.CPU, BenchStep.UNLOAD_MEMORY),
        )))
        assertEquals(0f, s.progress)
        s = BenchReducer.reduce(s, BenchProgress.Step(BenchStep.LOAD, ComputeUnit.CPU, 0, false))
        assertEquals(StepStatus.NOW, s.steps[0].status)
        s = BenchReducer.reduce(s, BenchProgress.Step(BenchStep.LOAD, ComputeUnit.CPU, 800, true))
        s = BenchReducer.reduce(s, BenchProgress.Step(BenchStep.SAMPLE_RU, ComputeUnit.CPU, 0, false, "ru", 10_000))
        assertEquals(listOf(StepStatus.DONE, StepStatus.NOW, StepStatus.TODO, StepStatus.TODO), s.steps.map { it.status })
        assertEquals(800L, s.steps[0].elapsedMs)
        assertEquals(0.25f, s.progress)
        s = BenchReducer.reduce(s, BenchProgress.UnitFailed(ArtifactId("a"), ComputeUnit.CPU, MurmurError.OutOfMemory(ModelKind.ASR)))
        assertEquals(listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.FAILED, StepStatus.FAILED), s.steps.map { it.status })
        assertEquals(1f, s.progress)
        s = BenchReducer.reduce(s, BenchProgress.Failed(MurmurError.OutOfMemory(ModelKind.ASR)))
        assertEquals(BenchUi.Phase.FAILED, s.phase)
    }

    @Test
    fun withoutAPlanStepsAppearAsTheyComeAndThereIsNoProgressBar() {
        var s = ui()
        s = BenchReducer.reduce(s, BenchProgress.Step(BenchStep.LOAD, ComputeUnit.NPU, 0, false))
        s = BenchReducer.reduce(s, BenchProgress.Step(BenchStep.LOAD, ComputeUnit.NPU, 300, true))
        s = BenchReducer.reduce(s, BenchProgress.Step(BenchStep.SAMPLE_RU, ComputeUnit.NPU, 0, false))
        assertEquals(listOf(BenchStep.LOAD, BenchStep.SAMPLE_RU), s.steps.map { it.step })
        assertNull(s.progress)
    }

    @Test
    fun resultShowsTheBestValidUnitFirst() {
        val npuWrong = record(ComputeUnit.NPU, 40.0, valid = false)
        val cpu = record(ComputeUnit.CPU, 8.0, valid = true)
        val gpu = record(ComputeUnit.GPU, 12.0, valid = true)
        val s = BenchReducer.reduce(ui(), BenchProgress.Finished(listOf(cpu, npuWrong, gpu)))
        assertEquals(BenchUi.Phase.RESULT, s.phase)
        assertEquals(listOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU), s.records.map { it.unit })
        assertEquals(ComputeUnit.GPU, s.shownUnit)
        assertEquals(gpu, s.shownRecord)
    }
}
