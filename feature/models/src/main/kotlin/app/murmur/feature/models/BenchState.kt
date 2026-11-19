package app.murmur.feature.models

import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.benchmark.BenchScores
import app.murmur.core.benchmark.BenchStep
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.store.BenchmarkRecord

/** Which surface opened a sheet or dialog: the list and the detail can be on screen together (inner display). */
enum class Surface { LIST, DETAIL }

enum class StepStatus { TODO, NOW, DONE, FAILED }

/** One checklist row of the running sheet (DESIGN bench-run). */
data class BenchStepUi(
    val unit: ComputeUnit,
    val step: BenchStep,
    val sampleId: String?,
    val audioMs: Long?,
    val status: StepStatus,
    val elapsedMs: Long,
)

/** The benchmark sheet: running checklist, then the result grid, or the error. */
data class BenchUi(
    val model: ModelId,
    val name: String,
    val kind: ModelKind,
    val origin: Surface,
    val phase: Phase = Phase.RUNNING,
    val steps: List<BenchStepUi> = emptyList(),
    /** True once the runner announced its plan (BenchProgress.Planned): only then is the progress bar meaningful. */
    val planned: Boolean = false,
    val records: List<BenchmarkRecord> = emptyList(),
    val unitErrors: List<Pair<ComputeUnit, MurmurError>> = emptyList(),
    val error: MurmurError? = null,
    /** Result view: the unit whose numbers are shown when several were measured. */
    val shownUnit: ComputeUnit? = null,
) {
    enum class Phase { RUNNING, RESULT, FAILED }

    /** Done steps / planned steps; null before the plan is known. */
    val progress: Float? get() = if (!planned || steps.isEmpty()) null else steps.count { it.status == StepStatus.DONE || it.status == StepStatus.FAILED }.toFloat() / steps.size

    val units: List<ComputeUnit> get() = steps.map { it.unit }.distinct()

    val shownRecord: BenchmarkRecord? get() = records.firstOrNull { it.unit == shownUnit } ?: records.firstOrNull()
}

/** Pure reducer of [BenchProgress] events into the sheet state. */
object BenchReducer {
    fun reduce(s: BenchUi, e: BenchProgress): BenchUi = when (e) {
        is BenchProgress.Planned -> s.copy(
            planned = true,
            steps = e.steps.map { BenchStepUi(it.unit, it.step, it.sampleId, it.audioMs, StepStatus.TODO, 0) },
        )
        is BenchProgress.Step -> {
            val i = s.steps.indexOfFirst {
                it.unit == e.unit && it.step == e.step && (e.sampleId == null || it.sampleId == null || it.sampleId == e.sampleId) &&
                    it.status != StepStatus.DONE && it.status != StepStatus.FAILED
            }
            val status = if (e.done) StepStatus.DONE else StepStatus.NOW
            val row = BenchStepUi(e.unit, e.step, e.sampleId ?: s.steps.getOrNull(i)?.sampleId, e.audioMs ?: s.steps.getOrNull(i)?.audioMs, status, e.elapsedMs)
            s.copy(steps = if (i < 0) s.steps + row else s.steps.toMutableList().also { it[i] = row })
        }
        is BenchProgress.UnitFailed -> s.copy(
            steps = s.steps.map { if (it.unit == e.unit && it.status != StepStatus.DONE) it.copy(status = StepStatus.FAILED) else it },
            unitErrors = s.unitErrors + (e.unit to e.error),
        )
        is BenchProgress.Finished -> s.copy(
            phase = BenchUi.Phase.RESULT,
            records = e.records.sortedBy { UnitView.UNIT_ORDER.indexOf(it.unit) },
            shownUnit = bestUnit(e.records),
        )
        is BenchProgress.Failed -> s.copy(phase = BenchUi.Phase.FAILED, error = e.error)
    }

    /** The unit shown first: the best valid score (what Auto would choose), else the first measured unit. */
    fun bestUnit(records: List<BenchmarkRecord>): ComputeUnit? =
        records.filter { BenchScores.score(it) != null }.minByOrNull { BenchScores.score(it)!! }?.unit
            ?: records.minByOrNull { UnitView.UNIT_ORDER.indexOf(it.unit) }?.unit
}
