package app.murmur.core.model

import kotlinx.serialization.Serializable

/**
 * Error taxonomy (ARCHITECTURE.md section "Error taxonomy"). Every failure that crosses a module boundary is one of
 * these. [code] is stable: it is stored in history rows ("failure_code"), counted on the dashboard and
 * sent across binder. Never put user text, screen text or paths into [detail] (privacy, D9).
 */
@Serializable
sealed class MurmurError {
    abstract val code: String
    abstract val retryable: Boolean
    open val detail: String? get() = null

    // --- preflight / permissions
    @Serializable data object MicPermissionMissing : MurmurError() { override val code = "perm.mic"; override val retryable = false }
    @Serializable data object AccessibilityOff : MurmurError() { override val code = "perm.a11y"; override val retryable = false }
    @Serializable data object NoAsrModel : MurmurError() { override val code = "model.asr_missing"; override val retryable = false }
    /** A batch (VAD-segmented) ASR model is selected but no VAD artifact is installed. Failed with audio kept. */
    @Serializable data object NoVadModel : MurmurError() { override val code = "model.vad_missing"; override val retryable = false }
    @Serializable data object PasswordField : MurmurError() { override val code = "ctx.password"; override val retryable = false }
    @Serializable data object AppExcluded : MurmurError() { override val code = "ctx.excluded"; override val retryable = false }
    @Serializable data object LowStorage : MurmurError() { override val code = "sys.storage"; override val retryable = false }
    @Serializable data object ThermalCritical : MurmurError() { override val code = "sys.thermal"; override val retryable = true }

    // --- audio
    @Serializable data object MicBusy : MurmurError() { override val code = "audio.busy"; override val retryable = true }
    @Serializable data class MicStartFailed(override val detail: String?) : MurmurError() { override val code = "audio.start"; override val retryable = true }
    @Serializable data object MicSilenced : MurmurError() { override val code = "audio.silenced"; override val retryable = true }
    @Serializable data object NoSpeech : MurmurError() { override val code = "audio.no_speech"; override val retryable = true }

    // --- inference processes / engines
    @Serializable data class EngineLoadFailed(val kind: ModelKind, override val detail: String?) : MurmurError() { override val code = "engine.load.${kind.name.lowercase()}"; override val retryable = true }
    @Serializable data class EngineRuntimeMissing(val runtime: String) : MurmurError() { override val code = "engine.runtime_missing"; override val retryable = false; override val detail get() = runtime }
    @Serializable data class UnitUnavailable(val unit: ComputeUnit) : MurmurError() { override val code = "engine.unit_unavailable"; override val retryable = true; override val detail get() = unit.name }
    @Serializable data class ProcessDied(val process: InferProcess) : MurmurError() { override val code = "engine.process_died.${process.name.lowercase()}"; override val retryable = true }
    @Serializable data class Timeout(val stage: Stage) : MurmurError() { override val code = "timeout.${stage.name.lowercase()}"; override val retryable = true }
    @Serializable data class OutOfMemory(val kind: ModelKind) : MurmurError() { override val code = "engine.oom.${kind.name.lowercase()}"; override val retryable = true }
    @Serializable data class AsrFailed(override val detail: String?) : MurmurError() { override val code = "asr.failed"; override val retryable = true }
    @Serializable data class LlmFailed(override val detail: String?) : MurmurError() { override val code = "llm.failed"; override val retryable = true }
    /**
     * The request does not fit the model's context window (prefix + suffix + minimum output > n_ctx), detected in
     * :main by CleanupBudget or in :llm after exact tokenisation (murmur_llm MLLM_E_OVERFLOW). Cleanup is skipped,
     * the raw transcript is inserted ("skipped:too_long"). Numbers are token counts, never text.
     */
    @Serializable data class LlmContextOverflow(val neededTokens: Int, val nCtx: Int) : MurmurError() { override val code = "llm.too_long"; override val retryable = false; override val detail get() = "$neededTokens>$nCtx" }
    @Serializable data class GuardRejected(val reason: String) : MurmurError() { override val code = "llm.guard.$reason"; override val retryable = false }
    @Serializable data class OcrFailed(override val detail: String?) : MurmurError() { override val code = "ocr.failed"; override val retryable = true }

    // --- context / insertion
    @Serializable data class ScreenshotFailed(val platformCode: Int) : MurmurError() { override val code = "ctx.screenshot.$platformCode"; override val retryable = true }
    @Serializable data object SecureWindow : MurmurError() { override val code = "ctx.secure"; override val retryable = false }
    @Serializable data object NoEditableField : MurmurError() { override val code = "insert.no_field"; override val retryable = false }
    @Serializable data object InsertRejected : MurmurError() { override val code = "insert.rejected"; override val retryable = false }
    @Serializable data object TargetChanged : MurmurError() { override val code = "insert.target_changed"; override val retryable = false }

    // --- models / downloads
    @Serializable data class DownloadFailed(val httpStatus: Int?, override val detail: String?) : MurmurError() { override val code = "dl.failed"; override val retryable = true }
    @Serializable data object ChecksumMismatch : MurmurError() { override val code = "dl.checksum"; override val retryable = true }
    @Serializable data object NoNetwork : MurmurError() { override val code = "dl.offline"; override val retryable = true }
    @Serializable data class CatalogInvalid(override val detail: String?) : MurmurError() { override val code = "catalog.invalid"; override val retryable = false }

    // --- data
    @Serializable data class StorageFailed(override val detail: String?) : MurmurError() { override val code = "data.io"; override val retryable = true }

    @Serializable data class Internal(override val detail: String?) : MurmurError() { override val code = "internal"; override val retryable = false }
}

/** Pipeline stage, used by [MurmurError.Timeout] and latency records. */
@Serializable
enum class Stage { PREFLIGHT, MIC_START, ASR_LOAD, LLM_LOAD, LLM_PREFIX, LLM_READY, OCR, ASR_FINAL, LLM, INSERT, DOWNLOAD, BENCH, CALL }

/** Minimal result type for cross-module APIs (no exceptions across module boundaries). */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: MurmurError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value
