package app.murmur.core.benchmark

import app.murmur.core.catalog.BenchLookup
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData

/**
 * Benchmark records -> numbers the app decides with (ARCHITECTURE 8.2, D2). Lower score = better:
 * ASR = processing ms per second of audio (1000 / RTFx), LLM = end-to-end ms of the fixed cleanup case,
 * OCR = ms per screenshot. Pure.
 */
object BenchScores {
    /**
     * [BenchLookup] for [app.murmur.core.catalog.ArtifactSelector]: the score of the latest record of (artifact, unit),
     * or null when there is none or when that latest record is invalid ("Results look wrong on NPU"): a newer invalid
     * run supersedes an older valid one, so a unit that broke after an update is never chosen for its old speed.
     */
    fun lookup(records: List<BenchmarkRecord>): BenchLookup {
        val latest = records.groupBy { it.artifactId to it.unit }.mapValues { (_, l) -> l.maxBy { it.atEpochMs } }
        return BenchLookup { artifact, unit -> latest[artifact to unit]?.let(::score) }
    }

    /** null for an invalid record (ARCHITECTURE 10: invalid records never score). */
    fun score(record: BenchmarkRecord): Double? {
        val r = record.result
        if (!r.valid) return null
        return when (r) {
            is BenchmarkResultData.Asr -> if (r.rtfx > 0) 1000.0 / r.rtfx else null
            is BenchmarkResultData.Llm -> r.e2eMs
            is BenchmarkResultData.Ocr -> r.msPerScreenshot.toDouble()
        }
    }

    /**
     * Measured speed of (artifact, unit) for `ActiveLlm.speed` (CleanupBudget, adaptive timeouts): prefill and decode
     * tok/s of the fixed case in the latest **valid** LLM record. null = never measured validly; the resolver then
     * uses `DictationConfig.defaultSpeed[unit]`. (The type lives here because :core:benchmark sits below
     * :core:dictation; the resolver maps it to `LlmSpeed(prefillTps, decodeTps)`.)
     */
    fun llmSpeed(records: List<BenchmarkRecord>, artifact: ArtifactId, unit: ComputeUnit): MeasuredLlmSpeed? =
        records.filter { it.artifactId == artifact && it.unit == unit && it.result.valid }
            .maxByOrNull { it.atEpochMs }
            ?.let { it.result as? BenchmarkResultData.Llm }
            ?.takeIf { it.prefillTps > 0 && it.decodeTps > 0 }
            ?.let { MeasuredLlmSpeed(prefillTps = it.prefillTps, decodeTps = it.decodeTps) }
}

/** Fixed-case prefill and decode speed of one (artifact, unit), tokens per second. */
data class MeasuredLlmSpeed(val prefillTps: Double, val decodeTps: Double)
