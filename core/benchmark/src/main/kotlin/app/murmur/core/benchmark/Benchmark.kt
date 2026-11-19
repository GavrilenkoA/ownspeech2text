package app.murmur.core.benchmark

import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.store.BenchmarkRecord
import kotlinx.coroutines.flow.Flow

/**
 * Benchmark button (BRIEF "Every model has a Benchmark button", D2, ARCHITECTURE.md section 10).
 * Runs through the production processes and code paths (same services, same JNI), never a harness. Per unit:
 *   cold load (process killed first) -> warm-up -> measured pass(es) -> unload -> memory -> validity gate.
 * ASR clips are pushed through AsrSession as fast as accept() allows (not in real time) so RTFx shows processing
 * speed; one warm-up + one measured pass per clip; clips chosen by the model's language codes (review 2026-11-19).
 * Target: under 60 s per unit on the phone. A valid record clears UnitHealth for that unit.
 */
interface BenchmarkRunner {
    /**
     * Measures every candidate of [entry] that is usable or blocked only by [app.murmur.core.catalog.Blocker.UNIT_FAILED]
     * (a valid run clears that failure), CPU first so that accelerators are judged against a fresh CPU record.
     * [candidates] = `ArtifactSelector.candidates` of the **installed** artifacts (the caller knows the install state).
     * Cold flow: collecting starts the run; cancelling the collection behaves like [stop].
     */
    fun run(entry: ModelEntry, candidates: List<Candidate>): Flow<BenchProgress>
    /** Cancels the run at once (the step in flight is abandoned), unloads, shuts the processes down; no [BenchProgress.Finished]. */
    fun stop()
}

sealed interface BenchProgress {
    /** First event of a run: every step that will follow, in order (progress bar = done steps / planned steps). */
    data class Planned(val steps: List<PlannedStep>) : BenchProgress

    /**
     * DESIGN bench-run checklist: Load model / Russian sample / English sample / Unload and measure memory.
     * [elapsedMs] = time spent in this step (0 when it starts, its duration when [done]). [sampleId] names the clip or
     * page ("ru", "en", "mixed", "cover", "inner"); [audioMs] = the clip length for "Russian sample, 10 s of audio".
     */
    data class Step(
        val step: BenchStep,
        val unit: app.murmur.core.model.ComputeUnit,
        val elapsedMs: Long,
        val done: Boolean,
        val sampleId: String? = null,
        val audioMs: Long? = null,
    ) : BenchProgress

    /** One unit could not be measured (load failed, process died, timeout); the run goes on with the next unit. */
    data class UnitFailed(
        val artifact: app.murmur.core.model.ArtifactId,
        val unit: app.murmur.core.model.ComputeUnit,
        val error: app.murmur.core.model.MurmurError,
    ) : BenchProgress

    /** The saved records, one per measured unit (valid or not). Never emitted after [BenchmarkRunner.stop]. */
    data class Finished(val records: List<BenchmarkRecord>) : BenchProgress

    /** Nothing could be measured: no usable candidate, inputs missing, or every unit failed (the first error). */
    data class Failed(val error: app.murmur.core.model.MurmurError) : BenchProgress
}

data class PlannedStep(
    val unit: app.murmur.core.model.ComputeUnit,
    val step: BenchStep,
    val sampleId: String? = null,
    val audioMs: Long? = null,
)

enum class BenchStep { LOAD, SAMPLE_RU, SAMPLE_EN, SAMPLE_MIXED, CLEANUP_CASE, OCR_PAGE, UNLOAD_MEMORY }

/**
 * Bundled benchmark inputs (resources /bench/...): CC-BY FLEURS clips (about 10-12 s each) + references, one OCR
 * page per display size, one cleanup case. [languages] = codes the clip needs ("ru", "en", or both for mixed); a
 * clip runs only when every code is in the model's Languages.codes. 8 kHz models (T-one) get the same 16 kHz PCM as
 * the capture path: the :asr process resamples to the model's feature rate (WP-06), so the benchmark does not.
 */
class BenchClip(val id: String, val languages: Set<String>, val pcm16k: ShortArray, val reference: String) {
    val audioMs: Long get() = pcm16k.size * 1000L / 16_000

    /** The checklist row: one language -> its sample, both -> the mixed sample. */
    val step: BenchStep get() = when {
        languages.size > 1 -> BenchStep.SAMPLE_MIXED
        "ru" in languages -> BenchStep.SAMPLE_RU
        else -> BenchStep.SAMPLE_EN
    }

    /** Runs only when every language of the clip is one the model lists (GigaAM: ru only, ADR-051). */
    fun runsOn(modelCodes: Collection<String>): Boolean = modelCodes.containsAll(languages)
}

/** The correctness gate (ARCHITECTURE 10): a fast unit with broken numerics must never win. Pure. */
object BenchValidity {
    const val MAX_WER = 0.20
    /** An accelerator may be at most this much worse than the same model's CPU record. */
    const val MAX_WER_OVER_CPU = 0.05

    /** null = valid, else the invalidReason code. */
    fun asr(medianWer: Double, cpuWer: Double?): String? = when {
        medianWer > MAX_WER -> "wer_high"
        cpuWer != null && medianWer > cpuWer + MAX_WER_OVER_CPU -> "wer_above_cpu"
        else -> null
    }

    /** [expectCyrillic] from the fixed case (a Russian transcript). stopReason per LlmStats. */
    fun llm(output: String, guardAccepted: Boolean, stopReason: Int, expectCyrillic: Boolean): String? = when {
        output.isBlank() -> "empty"
        stopReason != 0 -> "early_stop"
        !guardAccepted -> "guard_fallback"
        expectCyrillic && output.count { it in '\u0400'..'\u04FF' } < output.count { it.isLetter() } / 2 -> "wrong_script"
        else -> null
    }
}

/** WER with one normaliser (lowercase, strip punctuation, numerals as-is), the same as the asr-ocr spike. */
object WordErrorRate {
    fun normalize(s: String): List<String> =
        s.lowercase().replace(Regex("[\\p{P}\\p{S}]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun wer(reference: String, hypothesis: String): Double {
        val r = normalize(reference); val h = normalize(hypothesis)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        return editDistance(r, h).toDouble() / r.size
    }

    /** Levenshtein distance (substitution, insertion, deletion all cost 1). */
    fun <T> editDistance(r: List<T>, h: List<T>): Int {
        val d = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            var prev = d[0]; d[0] = i
            for (j in 1..h.size) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (r[i - 1] == h[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[h.size]
    }
}

/** Plain sentence under the LLM result (DESIGN 6.4): "A 60-word dictation is cleaned up in about 1.3 s." */
object BenchSummary {
    /** Seconds for a dictation of [words] words given measured speeds; tokensPerWord from llm-models.md 6. */
    fun llmSecondsFor(words: Int, prefillTps: Double, decodeTps: Double, tokensPerWord: Double = 1.6, contextTokens: Int = 300): Double {
        val inTok = words * tokensPerWord + contextTokens
        val outTok = words * tokensPerWord
        return inTok / prefillTps + outTok / decodeTps
    }
}
