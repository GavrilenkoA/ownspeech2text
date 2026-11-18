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
    /** Runs every usable unit of [entry] when [candidates] has several (the "measure the available units" rule). */
    fun run(entry: ModelEntry, candidates: List<Candidate>): Flow<BenchProgress>
    fun stop()
}

sealed interface BenchProgress {
    /** DESIGN bench-run checklist: Load model / Russian sample / English sample / Unload and measure memory. */
    data class Step(val step: BenchStep, val unit: app.murmur.core.model.ComputeUnit, val elapsedMs: Long, val done: Boolean) : BenchProgress
    data class Finished(val records: List<BenchmarkRecord>) : BenchProgress
    data class Failed(val error: app.murmur.core.model.MurmurError) : BenchProgress
}

enum class BenchStep { LOAD, SAMPLE_RU, SAMPLE_EN, SAMPLE_MIXED, CLEANUP_CASE, OCR_PAGE, UNLOAD_MEMORY }

/**
 * Bundled benchmark inputs (resources /bench/...): CC-BY FLEURS clips (about 10-12 s each) + references, one OCR
 * page per display size, one cleanup case. [languages] = codes the clip needs ("ru", "en", or both for mixed); a
 * clip runs only when every code is in the model's Languages.codes. 8 kHz models (T-one) get the clip resampled.
 */
data class BenchClip(val id: String, val languages: Set<String>, val pcm16k: ShortArray, val reference: String)

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

/** WER/CER with one normaliser (lowercase, strip punctuation, numerals as-is), the same as the asr-ocr spike. */
object WordErrorRate {
    fun normalize(s: String): List<String> =
        s.lowercase().replace(Regex("[\\p{P}\\p{S}]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun wer(reference: String, hypothesis: String): Double {
        val r = normalize(reference); val h = normalize(hypothesis)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        val d = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            var prev = d[0]; d[0] = i
            for (j in 1..h.size) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (r[i - 1] == h[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[h.size].toDouble() / r.size
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
