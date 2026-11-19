package app.murmur.infer.api

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Stage

/**
 * Every binder round trip the clients make, with the stage its timeout reports and what a timeout does to the
 * process (ARCHITECTURE.md 4.3 and 4.4: "binder call exceeds its timeout -> treated as death of that process").
 *
 * The one exception is OCR: it runs on its own thread inside :asr next to a live ASR session, and a late OCR only
 * means "cleanup without screen" (4.4), so an OCR timeout never kills the dictation's ASR.
 */
enum class BinderCall(val stage: Stage, val killOnTimeout: Boolean) {
    API_VERSION(Stage.CALL, true),
    PROBE(Stage.CALL, true),
    MEMORY(Stage.CALL, true),
    UNLOAD(Stage.CALL, true),
    CANCEL(Stage.CALL, true),
    ASR_LOAD(Stage.ASR_LOAD, true),
    ASR_START_SESSION(Stage.CALL, true),
    ASR_FINISH(Stage.ASR_FINAL, true),
    OCR_LOAD(Stage.OCR, false),
    OCR_RECOGNIZE(Stage.OCR, false),
    LLM_LOAD(Stage.LLM_LOAD, true),
    LLM_PREFIX(Stage.LLM_PREFIX, true),
    LLM_BENCH(Stage.BENCH, true),
    /** Only the start of a generation; the generation itself is bounded by the orchestrator's adaptive timer. */
    LLM_GENERATE(Stage.CALL, true),
    ;

    /** The error a timeout of this call returns. */
    fun timeoutError(): MurmurError = MurmurError.Timeout(stage)
}
