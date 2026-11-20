package app.murmur.infer.asr.ocr

import ai.onnxruntime.OrtException
import app.murmur.core.ocr.OcrCancelledException

/**
 * Newest frame wins, the part of [OrtOcrHandler] that reaches into the models (WP-21; no Android types, so the JVM
 * tests drive it with real ORT calls):
 *  - [supersede] marks the older frame; one still queued is left to the caller to answer, one that is running has its
 *    model call in flight terminated ([OrtOcrModels.terminateRunning]);
 *  - [run] runs a claimed frame's pipeline so that every later model call of a superseded frame starts terminated
 *    ([OrtOcrModels.stopRequested]) and a terminated call, like a stop between calls, ends as [Superseded].
 * The flag is always set before the terminate looks for a call, so no call of a superseded frame runs to its end:
 * either it is registered in time to be terminated, or it starts after the flag and sees it.
 */
internal class OcrFrameRunner {
    /** One frame as the runner sees it. */
    interface Frame {
        val superseded: Boolean

        /** Marks the frame superseded (before anyone tries to claim or stop it). */
        fun markSuperseded()

        /** True for the one party that takes the frame: the OCR thread that runs it or the newer frame that answers it. */
        fun claim(): Boolean
    }

    /** The frame was superseded: before, between or inside its model calls. */
    class Superseded : RuntimeException("superseded")

    /** The models of the run in progress (OCR thread writes; a superseding thread terminates their call in flight). */
    @Volatile private var running: OrtOcrModels? = null

    /** Any thread. True when [old] was still queued: the caller answers it and releases its frame. */
    fun supersede(old: Frame): Boolean {
        old.markSuperseded()
        if (old.claim()) return true
        running?.terminateRunning() // running: stop the model call in flight, not only the next one
        return false
    }

    /** OCR thread, [frame] claimed: [block] is its pipeline over [models]. */
    fun <T> run(models: OrtOcrModels, frame: Frame, block: () -> T): T {
        models.stopRequested = { frame.superseded }
        running = models
        try {
            return block()
        } catch (e: OcrCancelledException) {
            throw Superseded()
        } catch (e: OrtException) {
            // a terminated model call is the same answer as a stop between calls
            if (frame.superseded) throw Superseded()
            throw e
        } finally {
            running = null
            models.stopRequested = { false }
        }
    }
}
