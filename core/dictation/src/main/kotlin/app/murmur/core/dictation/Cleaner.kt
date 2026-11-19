package app.murmur.core.dictation

import app.murmur.core.context.CtxV2
import app.murmur.core.engine.LlmEngine
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.MurmurError
import app.murmur.core.prompt.GuardAction
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.prompt.PromptAssets
import app.murmur.core.prompt.PromptEditRules
import app.murmur.core.prompt.PromptProfile
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.prompt.RenderInput
import app.murmur.core.store.Settings

/**
 * One cleanup call (prompts/README "App flow around the LLM"): render -> generate -> guard. Shared by the dictation
 * (RunCleanup), Re-polish, Try it and Preparing (prefix text). Outcome strings are the history cleanup_outcome
 * vocabulary of ARCHITECTURE 15 (guard action/reason, or skipped:<code>).
 */
internal class Cleaner(
    private val llm: LlmEngine,
    private val renderer: PromptRenderer,
    private val guard: OutputGuard,
    private val prompts: PromptAssets,
    private val clock: MurmurClock,
) {
    data class Result(val text: String, val cleaned: Boolean, val outcome: String, val llmMs: Long)

    fun profile(active: ActiveLlm): PromptProfile = prompts.profile(active.profile)

    /** The user's edit applies only to the stock profile it was written for (ADR-046). */
    fun systemOverride(profile: PromptProfile, settings: Settings): String? =
        settings.promptOverride?.takeIf { PromptEditRules.overrideApplies(profile, it.baseProfile) }?.systemText

    fun promptVersion(profile: PromptProfile, override: String?): String = profile.name + if (override != null) "+edited" else ""

    /** What :llm prefills and snapshots (render.cache_prefix). */
    fun prefix(active: ActiveLlm, preferences: String?, override: String?): String =
        renderer.cachePrefix(profile(active), active.template, preferences, override)

    /** Tone per app (ADR-020): named tones have no style texts yet, so the category style line is used (no override). */
    suspend fun run(
        active: ActiveLlm,
        transcript: String,
        ctx: CtxV2?,
        preferences: String?,
        override: String?,
        budget: CleanupBudget.Plan,
    ): Result {
        val rendered = renderer.render(RenderInput(profile(active), active.template, ctx, transcript, preferences, styleOverride = null, systemOverride = override))
        val t0 = clock.elapsedMs()
        var done: LlmEvent.Done? = null
        var failed: MurmurError? = null
        llm.generate(LlmRequest(rendered.suffix, budget.maxTokens, budget.minTokens)).collect { ev ->
            when (ev) {
                is LlmEvent.Piece -> Unit
                is LlmEvent.Done -> done = ev
                is LlmEvent.Failed -> failed = ev.error
            }
        }
        val ms = clock.elapsedMs() - t0
        return interpret(transcript, done, failed, ctx, ms)
    }

    private fun interpret(transcript: String, done: LlmEvent.Done?, failed: MurmurError?, ctx: CtxV2?, ms: Long): Result {
        fun raw(outcome: String) = Result(transcript, cleaned = false, outcome = outcome, llmMs = ms)
        return when {
            failed is MurmurError.LlmContextOverflow -> raw("skipped:too_long")
            failed is MurmurError.ProcessDied -> raw("skipped:llm_died")
            failed != null || done == null -> raw("skipped:llm_failed")
            // ARCHITECTURE 4.3: a max_tokens stop is truncated output and never inserted.
            done.stats.stopReason == LlmStats.STOP_MAX_TOKENS -> raw("fallback:truncated")
            else -> {
                val v = guard.guard(transcript, done.text, ctx)
                when (v.action) {
                    GuardAction.ACCEPT -> Result(v.text, cleaned = true, outcome = "accept", llmMs = ms)
                    GuardAction.FALLBACK -> Result(v.text, cleaned = false, outcome = "fallback:${v.reason}", llmMs = ms)
                    GuardAction.EMPTY -> Result("", cleaned = false, outcome = "empty:${v.reason}", llmMs = ms)
                }
            }
        }
    }

    companion object {
        /** history failure_code for a cleanup that did not apply (ARCHITECTURE 15); null when it is not a failure. */
        fun failureCodeOf(outcome: String, llmLoadError: MurmurError?): String? = when {
            outcome == "accept" -> null
            outcome == "fallback:truncated" -> MurmurError.GuardRejected("truncated").code
            outcome.startsWith("fallback:") -> MurmurError.GuardRejected(outcome.removePrefix("fallback:")).code
            outcome == "skipped:timeout" -> MurmurError.Timeout(app.murmur.core.model.Stage.LLM).code
            outcome == "skipped:llm_not_ready" -> MurmurError.Timeout(app.murmur.core.model.Stage.LLM_READY).code
            outcome == "skipped:too_long" -> "llm.too_long"
            outcome == "skipped:low_memory" -> MurmurError.OutOfMemory(app.murmur.core.model.ModelKind.LLM).code
            outcome == "skipped:llm_died" -> MurmurError.ProcessDied(app.murmur.core.model.InferProcess.LLM).code
            outcome == "skipped:llm_load" -> (llmLoadError ?: MurmurError.EngineLoadFailed(app.murmur.core.model.ModelKind.LLM, null)).code
            outcome == "skipped:llm_failed" -> MurmurError.LlmFailed(null).code
            else -> null   // skipped:none / no_model / password: the user's choice, not a failure
        }
    }
}
