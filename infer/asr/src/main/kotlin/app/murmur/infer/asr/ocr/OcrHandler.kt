package app.murmur.infer.asr.ocr

import android.content.Context
import android.os.SharedMemory
import app.murmur.core.model.MurmurError
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi

/**
 * The seam between AsrService (WP-06) and OCR (WP-07): AsrService forwards loadOcr / recognizeScreen / unloadAll to
 * this handler and never touches OCR code itself, so the two packages never edit the same file
 * (review 2026-11-19). Calls arrive on binder threads; the implementation runs the work on its own OCR thread and
 * replies through [IResultCallback] (LoadInfo JSON / ScreenText JSON / MurmurError JSON).
 */
interface OcrHandler {
    fun load(detSpecJson: String, recSpecJson: String, cb: IResultCallback)
    /** [pixels]: read-only ARGB_8888 frame created by :main; close/unmap it when done (success or error). */
    fun recognize(pixels: SharedMemory, width: Int, height: Int, excludeJson: String, cb: IResultCallback)
    fun unload()
}

/** WP-07 replaces [create]'s body with its real handler (OrtOcrModels + OcrPipeline). */
object OcrHandlers {
    fun create(context: Context): OcrHandler = StubOcrHandler
}

/** Answers every call with EngineRuntimeMissing so that the rest of the pipeline runs without OCR ("no screen"). */
object StubOcrHandler : OcrHandler {
    private val missing = InferApi.encodeError(MurmurError.EngineRuntimeMissing("ort/ppocr"))
    override fun load(detSpecJson: String, recSpecJson: String, cb: IResultCallback) = cb.onError(missing)
    override fun recognize(pixels: SharedMemory, width: Int, height: Int, excludeJson: String, cb: IResultCallback) {
        pixels.close()
        cb.onError(missing)
    }
    override fun unload() {}
}
